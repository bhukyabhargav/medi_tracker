package com.meditrack.app.feature.verification.pipeline

import com.meditrack.app.core.config.VerificationConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single source of truth for "is there really a blister strip in this photo?".
 * Used by enrollment (baseline photo) AND dose verification so both behave identically.
 *
 * The detector always runs on the WHOLE photo (the on-screen guide is only a framing hint); this class only
 * judges what the detector found, wherever in the photo the strip is.
 *
 * WHY detector confidence is not enough: the bundled model has no "background" training photos, so on ordinary
 * pictures it still reports a strip (0.5-0.9) and several cells. So presence is decided by LAYOUT, which random
 * scenes do not satisfy but a blister always does:
 *  1. at least [VerificationConfig.minBlisterCells] cells,
 *  2. cells have similar width and height,
 *  3. cells sit on regular rows / columns (a grid, also when the strip is rotated),
 *  4. cells fill a real part of the strip box (a huge "strip" with a few tiny cells is a false detection),
 *  5. average cell confidence is reasonable.
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
        if (detection == null || detection.cells.isEmpty()) return Result(false, message = MSG_NOT_FOUND)

        val cells = detection.cells
        val valid = cells.filter { it.confidence >= VerificationConfig.minCellConfidence }
        fun fail() = Result(false, valid, MSG_NOT_FOUND)

        if (cells.size < VerificationConfig.minBlisterCells) return fail()

        val stripConf = detection.stripConfidence
        if (stripConf != null && stripConf < VerificationConfig.confidenceThreshold) return fail()

        if (cells.map { it.confidence }.average() < VerificationConfig.minMeanCellConfidence) return fail()

        val consistent = sizeConsistentCells(cells) ?: return fail()
        if (consistent.size < VerificationConfig.minBlisterCells) return fail()

        if (!isRegularGrid(consistent)) return fail()
        if (!fillsStrip(consistent, detection.stripBox)) return fail()

        return Result(true, valid)
    }

    private fun w(c: Cell) = c.box.right - c.box.left
    private fun h(c: Cell) = c.box.bottom - c.box.top
    private fun cx(c: Cell) = (c.box.left + c.box.right) / 2f
    private fun cy(c: Cell) = (c.box.top + c.box.bottom) / 2f
    private fun median(v: List<Float>): Float = v.sorted()[v.size / 2]

    /** Cells whose width and height are close to the median, or null when too few are (or no geometry at all). */
    internal fun sizeConsistentCells(cells: List<Cell>): List<Cell>? {
        if (cells.any { w(it) <= 0f || h(it) <= 0f }) return null // no usable geometry: cannot confirm a blister
        val mw = median(cells.map { w(it) })
        val mh = median(cells.map { h(it) })
        val lo = VerificationConfig.cellSizeToleranceLow
        val hi = VerificationConfig.cellSizeToleranceHigh
        val ok = cells.filter { w(it) / mw in lo..hi && h(it) / mh in lo..hi }
        return if (ok.size.toFloat() / cells.size >= VerificationConfig.minConsistentCellFraction) ok else null
    }

    /**
     * True when enough cells lie on evenly spaced rows or columns (missing cavities are allowed).
     * Checked in the photo's own axes and again in the strip's principal-axis frame, so a strip lying
     * diagonally is judged like an upright one.
     */
    internal fun isRegularGrid(cells: List<Cell>): Boolean {
        if (cells.isEmpty()) return false
        val mw = median(cells.map { w(it) })
        val mh = median(cells.map { h(it) })
        val cellSize = min(mw, mh)
        val xs = cells.map { cx(it) }
        val ys = cells.map { cy(it) }
        val minFraction = VerificationConfig.minAlignedCellFraction

        if (alignedFraction(xs, ys, 0.6f * mh, 0.6f * mw, cellSize) >= minFraction) return true

        // Principal axis of the cell centres
        val mx = xs.average().toFloat()
        val my = ys.average().toFloat()
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in xs.indices) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val angle = 0.5f * atan2(2f * sxy, sxx - syy)
        val ca = cos(angle)
        val sa = sin(angle)
        val rx = xs.indices.map { (xs[it] - mx) * ca + (ys[it] - my) * sa }
        val ry = xs.indices.map { -(xs[it] - mx) * sa + (ys[it] - my) * ca }
        return alignedFraction(rx, ry, 0.6f * cellSize, 0.6f * cellSize, cellSize) >= minFraction
    }

    private fun alignedFraction(
        xs: List<Float>,
        ys: List<Float>,
        rowTolerance: Float,
        colTolerance: Float,
        cellSize: Float
    ): Float {
        val aligned = HashSet<Int>()
        for (row in groupByCoordinate(ys, rowTolerance)) if (evenlySpaced(row, xs, cellSize)) aligned.addAll(row)
        for (col in groupByCoordinate(xs, colTolerance)) if (evenlySpaced(col, ys, cellSize)) aligned.addAll(col)
        return aligned.size.toFloat() / xs.size
    }

    private fun groupByCoordinate(values: List<Float>, tolerance: Float): List<List<Int>> {
        val order = values.indices.sortedBy { values[it] }
        val groups = mutableListOf(mutableListOf(order[0]))
        for (i in order.drop(1)) {
            if (values[i] - values[groups.last().last()] <= tolerance) groups.last().add(i) else groups.add(mutableListOf(i))
        }
        return groups
    }

    /** Members of one row/column must not overlap and their gaps must be whole multiples of the smallest gap. */
    private fun evenlySpaced(group: List<Int>, along: List<Float>, cellSize: Float): Boolean {
        if (group.size < 2) return false
        val pos = group.map { along[it] }.sorted()
        val gaps = pos.zipWithNext { a, b -> b - a }
        val base = gaps.minOrNull() ?: return false
        if (base < 0.64f * cellSize) return false
        return gaps.all { g ->
            val ratio = g / base
            abs(ratio - ratio.roundToInt()) < 0.3f
        }
    }

    /** Cells must cover a real part of the strip box; a big "strip" with a few small cells is a false detection. */
    internal fun fillsStrip(cells: List<Cell>, stripBox: android.graphics.RectF?): Boolean {
        val box = stripBox ?: bounding(cells)
        val stripArea = (box.right - box.left) * (box.bottom - box.top)
        if (stripArea <= 0f) return false
        val cellArea = cells.sumOf { (w(it) * h(it)).toDouble() }.toFloat()
        return cellArea / stripArea >= VerificationConfig.minStripCoverage
    }

    private fun bounding(cells: List<Cell>): android.graphics.RectF {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (c in cells) { l = min(l, c.box.left); t = min(t, c.box.top); r = max(r, c.box.right); b = max(b, c.box.bottom) }
        return android.graphics.RectF(l, t, r, b)
    }
}
