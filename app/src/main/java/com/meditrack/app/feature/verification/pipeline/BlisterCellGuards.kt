package com.meditrack.app.feature.verification.pipeline

import com.meditrack.app.core.config.VerificationConfig

/**
 * Shared cell-level integrity guards for both dose verification and baseline enrollment.
 * Ensures consistent handling of partial cells and low-confidence cells across all flows.
 */
object BlisterCellGuards {

    sealed class GuardResult {
        object Passed : GuardResult()
        data class Rejected(
            val failureReason: VerificationFailureReason,
            val message: String,
            val retryHint: String? = null
        ) : GuardResult()
    }

    /**
     * Checks if the detected cells meet quality, confidence, and completeness standards:
     * 1. Rejects if too large a fraction of cells has low confidence (< reliableCellConfidence).
     * 2. Rejects if any cell is damaged or partially pushed (PARTIAL).
     */
    fun evaluate(cells: List<Cell>): GuardResult {
        if (cells.isEmpty()) return GuardResult.Passed

        val unreliableCells = cells.count { it.confidence < VerificationConfig.reliableCellConfidence }
        val unreliableFraction = unreliableCells.toFloat() / cells.size
        if (unreliableFraction > VerificationConfig.maxUnreliableCellFraction) {
            val fail = VerificationFailureReason.IMAGE_BLURRY
            return GuardResult.Rejected(
                failureReason = fail,
                message = "Some blister cells could not be identified with high confidence. Please retake the photo with better lighting.",
                retryHint = fail.retryHint
            )
        }

        if (cells.any { it.state == CellState.PARTIAL }) {
            val fail = VerificationFailureReason.AMBIGUOUS_CELL_DETECTION
            return GuardResult.Rejected(
                failureReason = fail,
                message = "A damaged or partially pushed pill was detected. Please retake the photo with a clear view or verify manually.",
                retryHint = fail.retryHint
            )
        }

        return GuardResult.Passed
    }
}
