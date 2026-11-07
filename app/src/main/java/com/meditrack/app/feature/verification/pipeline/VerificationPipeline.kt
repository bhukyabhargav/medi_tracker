package com.meditrack.app.feature.verification.pipeline

import android.graphics.Bitmap
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.feature.verification.quality.ImageQualityGate
import com.meditrack.app.feature.verification.quality.PerceptualHasher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VerificationPipeline @Inject constructor(
    private val stripDetector: StripDetector
) {

    /**
     * Executes the verification pipeline in the exact specified order:
     * 1. Quality gate (BLURRY / TOO_DARK / TOO_BRIGHT / GLARE)
     * 2. Duplicate check (DUPLICATE_PHOTO)
     * 3. Cell detection (STRIP_NOT_FULLY_VISIBLE)
     * 4. Baseline compare ledger rule (PILLS_NOT_REMOVED / WRONG_PILL_COUNT / PASS)
     */
    suspend fun verify(
        bitmap: Bitmap,
        medicine: Medicine,
        lastVerifiedEmptyCells: Int?,
        existingHashes: List<Long> = emptyList(),
        currentHashOverride: Long? = null
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

        val validCells = detection.cells.filter { it.confidence >= AppConfig.DETECTOR_MIN_CONFIDENCE }

        // Step 4: Baseline compare (Ledger rule)
        // previousEmpty = emptyCellsAfter of the last verified dose for this medicine, or baselineEmptyCells if none
        val previousEmpty = lastVerifiedEmptyCells ?: medicine.baselineEmptyCells

        // PARTIAL cells count as FULL (not removed)
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
