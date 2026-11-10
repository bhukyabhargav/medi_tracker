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
import kotlin.math.max

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
        previousTotalCells: Int? = null,
        lastVerifiedDetectorSource: String? = null
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
        val presence = if (detection.isRoboflowWorkflow) {
            val accepted = detection.roboflowPackStatus.equals("accepted", ignoreCase = true)
            BlisterPresenceValidator.Result(
                present = accepted,
                validCells = detection.cells,
                message = if (accepted) null else BlisterPresenceValidator.MSG_NOT_FOUND
            )
        } else {
            BlisterPresenceValidator.evaluate(detection)
        }

        if (!presence.present) {
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

        // Conservative Guard: Shared cell confidence & partial-cell check
        when (val cellGuard = BlisterCellGuards.evaluate(detection.cells)) {
            is BlisterCellGuards.GuardResult.Rejected -> {
                return VerificationResult(
                    passed = false,
                    rejectReason = cellGuard.failureReason.mappedRejectReason,
                    failureReason = cellGuard.failureReason,
                    friendlyMessage = cellGuard.message,
                    retryHint = cellGuard.retryHint
                )
            }
            is BlisterCellGuards.GuardResult.Passed -> { /* passed */ }
        }

        val validCells = detection.cells.filter { it.confidence >= VerificationConfig.minCellConfidence }

        // Conservative Guard: Cell count tolerance check against previous capture of the same strip (A6)
        if (!detection.isRoboflowWorkflow && previousTotalCells != null && abs(validCells.size - previousTotalCells) > VerificationConfig.maxCellCountTolerance) {
            val fail = VerificationFailureReason.AMBIGUOUS_CELL_DETECTION
            return VerificationResult(
                passed = false,
                rejectReason = RejectReason.WRONG_PILL_COUNT,
                failureReason = fail,
                friendlyMessage = "Detected ${validCells.size} cavities, but previous scans showed $previousTotalCells. Please ensure the full blister pack is visible.",
                retryHint = fail.retryHint
            )
        }

        // Step 5: Baseline compare (Ledger rule)
        val isFirstDose = (lastVerifiedEmptyCells == null)
        val previousEmpty = lastVerifiedEmptyCells ?: medicine.baselineEmptyCells

        val storedSourceStr = if (isFirstDose) {
            medicine.baselineDetectorSource
        } else {
            lastVerifiedDetectorSource ?: medicine.baselineDetectorSource
        }
        val storedSource = storedSourceStr?.let {
            runCatching { DetectorSource.valueOf(it.uppercase()) }.getOrNull()
        }
        val isCrossDetectorMismatch = (storedSource != null && storedSource != detection.source)
        val allowTolerance = isFirstDose || isCrossDetectorMismatch

        val newEmpty = if (detection.isRoboflowWorkflow && detection.roboflowMissingCount != null) {
            detection.roboflowMissingCount
        } else {
            validCells.count { it.state == CellState.EMPTY }
        }
        val delta = newEmpty - previousEmpty

        val minAllowedDelta = max(1, medicine.pillsPerDose - VerificationConfig.firstDoseCountTolerance)
        val maxAllowedDelta = medicine.pillsPerDose + VerificationConfig.firstDoseCountTolerance

        return when {
            delta == 0 -> {
                VerificationResult(
                    passed = false,
                    rejectReason = RejectReason.PILLS_NOT_REMOVED,
                    emptyCellsAfter = newEmpty,
                    detectorSource = detection.source,
                    friendlyMessage = "No new pills removed. Please push out your dose."
                )
            }
            allowTolerance -> {
                if (delta in minAllowedDelta..maxAllowedDelta) {
                    VerificationResult(
                        passed = true,
                        emptyCellsAfter = newEmpty,
                        detectorSource = detection.source,
                        friendlyMessage = "Dose verified successfully."
                    )
                } else if (isCrossDetectorMismatch && delta < 0) {
                    // Cross detector mismatch: never fail with "different strip"
                    VerificationResult(
                        passed = false,
                        rejectReason = RejectReason.WRONG_PILL_COUNT,
                        emptyCellsAfter = newEmpty,
                        detectorSource = detection.source,
                        friendlyMessage = "Photo processed with ${detection.source.name.lowercase()} detector while baseline was ${storedSource?.name?.lowercase()}. Please push out your dose or retake when online."
                    )
                } else if (delta < -VerificationConfig.firstDoseCountTolerance) {
                    VerificationResult(
                        passed = false,
                        rejectReason = RejectReason.WRONG_PILL_COUNT,
                        emptyCellsAfter = newEmpty,
                        detectorSource = detection.source,
                        friendlyMessage = "This looks like a different or new strip. If you started a new strip, " +
                            "open Medicines > Refill and switch on \"I started a new strip\"."
                    )
                } else {
                    VerificationResult(
                        passed = false,
                        rejectReason = RejectReason.WRONG_PILL_COUNT,
                        emptyCellsAfter = newEmpty,
                        detectorSource = detection.source,
                        friendlyMessage = "Expected ${medicine.pillsPerDose} pill(s) removed, but counted $delta."
                    )
                }
            }
            delta == medicine.pillsPerDose -> {
                VerificationResult(
                    passed = true,
                    emptyCellsAfter = newEmpty,
                    detectorSource = detection.source,
                    friendlyMessage = "Dose verified successfully."
                )
            }
            delta < 0 -> {
                VerificationResult(
                    passed = false,
                    rejectReason = RejectReason.WRONG_PILL_COUNT,
                    emptyCellsAfter = newEmpty,
                    detectorSource = detection.source,
                    friendlyMessage = "This looks like a different or new strip. If you started a new strip, " +
                        "open Medicines > Refill and switch on \"I started a new strip\"."
                )
            }
            else -> {
                VerificationResult(
                    passed = false,
                    rejectReason = RejectReason.WRONG_PILL_COUNT,
                    emptyCellsAfter = newEmpty,
                    detectorSource = detection.source,
                    friendlyMessage = "Expected ${medicine.pillsPerDose} pill(s) removed, but counted $delta."
                )
            }
        }
    }
}
