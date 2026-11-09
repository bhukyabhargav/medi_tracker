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
     * 3. Strip cell detection & confidence guards (STRIP_NOT_FULLY_VISIBLE / BLURRY / WRONG_PILL_COUNT)
     * 4. Baseline compare ledger rule (PILLS_NOT_REMOVED / WRONG_PILL_COUNT / PASS)
     */
    suspend fun verify(
        bitmap: Bitmap,
        medicine: Medicine,
        lastVerifiedEmptyCells: Int?,
        existingHashes: List<Long> = emptyList(),
        currentHashOverride: Long? = null,
        previousTotalCells: Int? = null
    ): VerificationResult {

        // Step 1: Quality Gate
        val quality = ImageQualityGate.analyze(bitmap)
        if (!quality.passed) {
            return VerificationResult(
                passed = false,
                rejectReason = quality.reason ?: RejectReason.BLURRY,
                friendlyMessage = quality.friendlyMessage
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
                friendlyMessage = "Duplicate photo detected. Please take a new photo of your blister strip."
            )
        }

        // Step 3: Strip cell detection
        val detection = stripDetector.detect(bitmap)
        if (!detection.stripFullyInFrame) {
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.STRIP_NOT_FULLY_VISIBLE,
                friendlyMessage = "The blister strip is not fully in frame. Please align the entire strip within the guide."
            )
        }

        // Conservative Guard: Confirm blister cells or strip were detected
        if (detection.cells.isEmpty()) {
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.STRIP_NOT_FULLY_VISIBLE,
                friendlyMessage = "No blister pack detected. Please align your blister strip within the frame."
            )
        }

        // Conservative Guard: Strip detection confidence check
        if (detection.stripConfidence != null && detection.stripConfidence < VerificationConfig.confidenceThreshold) {
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.STRIP_NOT_FULLY_VISIBLE,
                friendlyMessage = "Strip detection confidence too low. Please center the blister pack within the frame."
            )
        }

        // Conservative Guard: Cell confidence check (A6)
        val hasLowConfidenceCell = detection.cells.any { it.confidence < VerificationConfig.minCellConfidence }
        if (hasLowConfidenceCell) {
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.BLURRY,
                friendlyMessage = "Some blister cells could not be identified with high confidence. Please retake the photo with better lighting."
            )
        }

        // Conservative Guard: Ambiguous partial cell check (A1: cell_partial not trained; treat as unknown/needs retake)
        val hasPartial = detection.cells.any { it.state == CellState.PARTIAL }
        if (hasPartial) {
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.WRONG_PILL_COUNT,
                friendlyMessage = "A damaged or partially pushed pill was detected. Please retake the photo with a clear view or verify manually."
            )
        }

        val validCells = detection.cells.filter { it.confidence >= AppConfig.DETECTOR_MIN_CONFIDENCE }

        // Conservative Guard: Cell count tolerance check against previous capture of the same strip (A6)
        if (previousTotalCells != null && abs(validCells.size - previousTotalCells) > VerificationConfig.maxCellCountTolerance) {
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.WRONG_PILL_COUNT,
                friendlyMessage = "Detected ${validCells.size} cavities, but previous scans showed $previousTotalCells. Please ensure the full blister pack is visible."
            )
        }

        // Step 4: Baseline compare (Ledger rule)
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
