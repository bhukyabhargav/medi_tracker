package com.meditrack.app.feature.verification.pipeline

import com.meditrack.app.core.config.VerificationConfig

/**
 * Single source of truth for "is there really a blister strip in this photo?".
 *
 * Used by enrollment (baseline photo) AND dose verification so both behave identically.
 * The detector always runs on the WHOLE photo (the on-screen guide is only a framing hint), so this only
 * judges what the detector found, wherever in the photo the strip is.
 *
 * Why this exists: "at least one cell found" is not proof of a blister. On a normal photo (table, hand,
 * pattern) a stray low-confidence "cell" can appear. A strip needs real evidence:
 *  - the model located the strip itself with enough confidence, OR
 *  - there is no strip box but a convincing number of confident cells.
 * Cell sizes must also look like one repeated cavity (when box geometry is available).
 */
object BlisterPresenceValidator {

    const val MSG_NOT_FOUND =
        "No blister strip found in this photo. Place the blister strip flat and fully in view, then retake."

    data class Result(
        val present: Boolean,
        val validCells: List<Cell> = emptyList(),
        val message: String? = null
    )

    fun evaluate(detection: Detection?): Result {
        if (detection == null || detection.cells.isEmpty()) {
            return Result(false, message = MSG_NOT_FOUND)
        }

        // The detector already drops detections below its own threshold, so judge presence on all cells
        // it returned; the stricter per-cell confidence is applied later for counting empty cells.
        val evidence = detection.cells
        val valid = evidence.filter { it.confidence >= VerificationConfig.minCellConfidence }
        if (evidence.size < VerificationConfig.minBlisterCells) {
            return Result(false, valid, MSG_NOT_FOUND)
        }

        val stripConf = detection.stripConfidence
        if (stripConf != null) {
            // The model located a strip: it must be a confident detection.
            if (stripConf < VerificationConfig.confidenceThreshold) {
                return Result(false, valid, MSG_NOT_FOUND)
            }
        } else {
            // No strip box at all: accept only strong cell-only evidence.
            val meanConf = evidence.map { it.confidence }.average().toFloat()
            if (evidence.size < VerificationConfig.minCellsWithoutStrip ||
                meanConf < VerificationConfig.reliableCellConfidence
            ) {
                return Result(false, valid, MSG_NOT_FOUND)
            }
        }

        if (!hasConsistentCellSizes(evidence)) {
            return Result(false, valid, MSG_NOT_FOUND)
        }
        return Result(true, valid)
    }

    /** Cavities on one strip have similar size. Skipped when boxes carry no geometry (e.g. unit-test cells). */
    internal fun hasConsistentCellSizes(cells: List<Cell>): Boolean {
        val areas = cells.map { (it.box.right - it.box.left) * (it.box.bottom - it.box.top) }
            .filter { it > 0f }
        if (areas.size < 3) return true
        val median = areas.sorted()[areas.size / 2]
        val consistent = areas.count { it >= median * 0.4f && it <= median * 2.5f }
        return consistent.toFloat() / areas.size >= VerificationConfig.minConsistentCellFraction
    }
}
