package com.meditrack.app.feature.verification.pipeline

import android.graphics.Bitmap
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.core.config.VerificationConfig
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.feature.verification.quality.ImageQualityGate
import com.meditrack.app.feature.verification.quality.PerceptualHasher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class VerificationPipeline @Inject constructor(
    private val stripDetector: StripDetector
) {

    /**
     * Executes the verification pipeline in the exact specified order:
     * 1. Quality gate (BLURRY / TOO_DARK / TOO_BRIGHT / GLARE)
     * 2. Duplicate check (DUPLICATE_PHOTO)
     * 3. Strip cell detection & in-frame check (STRIP_NOT_FULLY_VISIBLE)
     * 4. Blister presence layout check (STRIP_NOT_FULLY_VISIBLE)
     * 5. Level-2 Quality Gate on the blister crop (BLURRY / TOO_DARK / TOO_BRIGHT / GLARE / LOW_RESOLUTION)
     * 6. Confidence & tolerance guards (STRIP_NOT_FULLY_VISIBLE / BLURRY / WRONG_PILL_COUNT)
     * 7. Baseline compare ledger rule (PILLS_NOT_REMOVED / WRONG_PILL_COUNT / PASS)
     */
    suspend fun verify(
        bitmap: Bitmap,
        medicine: Medicine,
        lastVerifiedEmptyCells: Int?,
        existingHashes: List<Long> = emptyList(),
        currentHashOverride: Long? = null,
        previousTotalCells: Int? = null
    ): VerificationResult {

        // Step 1: Level-1 Quality Gate (light check on original image: decode, min size, not near-black/near-white)
        val level1Quality = ImageQualityGate.analyzeLevel1(bitmap)
        if (!level1Quality.passed) {
            val fail = level1Quality.failureReason ?: VerificationFailureReason.fromQualityReason(level1Quality.reason)
            return VerificationResult(
                passed = false,
                rejectReason = level1Quality.reason ?: fail?.mappedRejectReason ?: RejectReason.BLURRY,
                failureReason = fail,
                friendlyMessage = level1Quality.friendlyMessage ?: fail?.userMessage,
                retryHint = level1Quality.retryHint ?: fail?.retryHint
            )
        }

        // Step 2: Duplicate photo check
        val currentHash = currentHashOverride ?: PerceptualHasher.computeHash(bitmap)
        val isDuplicate = existingHashes.any { existing ->
            existing != 0L && PerceptualHasher.isDuplicate(currentHash, existing)
        }
        if (isDuplicate) {
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.DUPLICATE_PHOTO,
                friendlyMessage = "Duplicate photo detected. Please take a new photo of your blister strip.",
                retryHint = "Take a fresh photo of your current blister pack."
            )
        }

        // Step 3: Strip cell detection
        val detection = stripDetector.detect(bitmap)

        // Step 3a: Real stripFullyInFrame geometric check (Task 2)
        if (!detection.stripFullyInFrame) {
            val fail = VerificationFailureReason.BLISTER_PARTIALLY_VISIBLE
            return VerificationResult(
                passed = false,
                rejectReason = fail.mappedRejectReason,
                failureReason = fail,
                friendlyMessage = "Blister strip is partially outside the photo. Please place the entire blister pack fully in view and retake.",
                retryHint = fail.retryHint
            )
        }

        // Step 3b: Confirm a real blister strip is present anywhere in the photo (shared with enrollment).
        // Stray cells without strip evidence (normal photo) are rejected here.
        val presence = BlisterPresenceValidator.evaluate(detection)
        if (detection.cells.isEmpty() || !presence.present) {
            val fail = VerificationFailureReason.BLISTER_NOT_FOUND
            return VerificationResult(
                passed = false,
                rejectReason = fail.mappedRejectReason,
                failureReason = fail,
                friendlyMessage = presence.message ?: fail.userMessage,
                retryHint = fail.retryHint
            )
        }

        // Step 4: Level-2 Quality Gate on the Blister Crop (blur, brightness, contrast, glare, resolution)
        val stripBox = ImageQualityGate.resolveStripBox(detection.stripBox, detection.cells)
        val level2Quality = ImageQualityGate.analyzeBlisterCrop(bitmap, stripBox)
        if (!level2Quality.passed) {
            val fail = level2Quality.failureReason ?: VerificationFailureReason.fromQualityReason(level2Quality.reason)
            return VerificationResult(
                passed = false,
                rejectReason = level2Quality.reason ?: fail?.mappedRejectReason ?: RejectReason.BLURRY,
                failureReason = fail,
                friendlyMessage = level2Quality.friendlyMessage ?: fail?.userMessage,
                retryHint = level2Quality.retryHint ?: fail?.retryHint
            )
        }

        // Conservative Guard: Strip detection confidence check
        if (detection.stripConfidence != null && detection.stripConfidence < VerificationConfig.confidenceThreshold) {
            val fail = VerificationFailureReason.BLISTER_NOT_FOUND
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.STRIP_NOT_FULLY_VISIBLE,
                failureReason = fail,
                friendlyMessage = "Strip detection confidence too low. Please center the blister pack within the frame.",
                retryHint = fail.retryHint
            )
        }

        // Conservative Guard: Cell confidence check (A6).
        // A single weaker cell is normal on a reflective foil strip (real photos regularly have one or two cells
        // at 0.45-0.60), so only reject when a large share of the detected cells is unreliable.
        val unreliableCells = detection.cells.count { it.confidence < VerificationConfig.reliableCellConfidence }
        val unreliableFraction = unreliableCells.toFloat() / detection.cells.size
        val hasLowConfidenceCell = unreliableFraction > VerificationConfig.maxUnreliableCellFraction
        if (hasLowConfidenceCell) {
            val fail = VerificationFailureReason.IMAGE_BLURRY
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.BLURRY,
                failureReason = fail,
                friendlyMessage = "Some blister cells could not be identified with high confidence. Please retake the photo with better lighting.",
                retryHint = fail.retryHint
            )
        }

        // Conservative Guard: Ambiguous partial cell check (A1: cell_partial not trained; treat as unknown/needs retake)
        val hasPartial = detection.cells.any { it.state == CellState.PARTIAL }
        if (hasPartial) {
            val fail = VerificationFailureReason.AMBIGUOUS_CELL_DETECTION
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.WRONG_PILL_COUNT,
                failureReason = fail,
                friendlyMessage = "A damaged or partially pushed pill was detected. Please retake the photo with a clear view or verify manually.",
                retryHint = fail.retryHint
            )
        }

        val validCells = detection.cells.filter { it.confidence >= VerificationConfig.minCellConfidence }

        // Conservative Guard: Cell count tolerance check against previous capture of the same strip (A6)
        if (previousTotalCells != null && abs(validCells.size - previousTotalCells) > VerificationConfig.maxCellCountTolerance) {
            val fail = VerificationFailureReason.AMBIGUOUS_CELL_DETECTION
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.WRONG_PILL_COUNT,
                failureReason = fail,
                friendlyMessage = "Detected ${validCells.size} cavities, but previous scans showed $previousTotalCells. Please ensure the full blister pack is visible.",
                retryHint = fail.retryHint
            )
        }

        // Task 5: Baseline geometry signature check (rotation-invariant count, aspect ratio, arrangement)
        val baselineSignature = medicine.getBaselineSignature()
        if (baselineSignature != null && validCells.size >= 4) {
            val currentSignature = com.meditrack.app.domain.model.BaselineGeometrySignature.extract(validCells, stripBox)
            if (currentSignature != null) {
                val comparison = com.meditrack.app.domain.model.BaselineGeometrySignature.compare(currentSignature, baselineSignature)
                if (!comparison.matches) {
                    val fail = VerificationFailureReason.AMBIGUOUS_CELL_DETECTION
                    return VerificationResult(
                        passed = false,
                        rejectReason = RejectReason.WRONG_PILL_COUNT,
                        failureReason = fail,
                        friendlyMessage = "Blister layout does not match enrolled baseline (${comparison.reason ?: "different strip"}). Please re-enroll this strip or confirm in Medicines.",
                        retryHint = "If using a new brand or pack, re-enroll your strip baseline in Medicines."
                    )
                }
            }
        }

        // Step 5: Baseline compare (Ledger rule)
        // previousEmpty = emptyCellsAfter of the last verified dose for this medicine, or baselineEmptyCells if none
        val previousEmpty = lastVerifiedEmptyCells ?: medicine.baselineEmptyCells

        val newEmpty = validCells.count { it.state == CellState.EMPTY }
        val delta = newEmpty - previousEmpty

        return when {
            delta == medicine.pillsPerDose -> {
                VerificationResult(
                    passed = true,
                    emptyCellsAfter = newEmpty,
                    friendlyMessage = "Dose verified successfully."
                )
            }
            delta == 0 -> {
                VerificationResult(
                    passed = false,
                    rejectReason = RejectReason.PILLS_NOT_REMOVED,
                    emptyCellsAfter = newEmpty,
                    friendlyMessage = "No new pills removed. Please push out your dose."
                )
            }
            delta < 0 -> {
                // Fewer empty cells than last time: this looks like a different or fresh strip.
                VerificationResult(
                    passed = false,
                    rejectReason = RejectReason.WRONG_PILL_COUNT,
                    emptyCellsAfter = newEmpty,
                    friendlyMessage = "This looks like a different or new strip. If you started a new strip, " +
                        "open Medicines > Refill and switch on \"I started a new strip\"."
                )
            }
            else -> {
                VerificationResult(
                    passed = false,
                    rejectReason = RejectReason.WRONG_PILL_COUNT,
                    emptyCellsAfter = newEmpty,
                    friendlyMessage = "Expected ${medicine.pillsPerDose} pill(s) removed, but counted $delta."
                )
            }
        }
    }
}
