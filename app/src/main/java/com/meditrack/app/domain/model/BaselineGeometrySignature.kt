package com.meditrack.app.domain.model

import android.graphics.RectF
import com.meditrack.app.feature.verification.pipeline.Cell
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Task 5: Baseline geometry signature.
 * Stores normalized, rotation-invariant geometric characteristics of an enrolled blister strip:
 * - totalCellCount: total physical cavities
 * - normalizedCenters: normalized cell center positions relative to the strip coordinate frame
 * - relativeCellSize: median cell area / strip area
 * - estimatedRows: estimated row count
 * - estimatedColumns: estimated column count
 * - stripAspectRatio: rotation-invariant aspect ratio max(w/h, h/w)
 * - baselineVersion: version of the signature format (default 1)
 */
data class BaselineGeometrySignature(
    val totalCellCount: Int,
    val normalizedCenters: List<Pair<Float, Float>>,
    val relativeCellSize: Float,
    val estimatedRows: Int,
    val estimatedColumns: Int,
    val stripAspectRatio: Float,
    val baselineVersion: Int = 1
) {
    fun toCentersJson(): String {
        val arr = JSONArray()
        for (pt in normalizedCenters) {
            val ptArr = JSONArray()
            ptArr.put((round(pt.first * 1000f) / 1000f).toDouble())
            ptArr.put((round(pt.second * 1000f) / 1000f).toDouble())
            arr.put(ptArr)
        }
        return arr.toString()
    }

    companion object {
        fun fromCentersJson(json: String?): List<Pair<Float, Float>> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = ArrayList<Pair<Float, Float>>(arr.length())
                for (i in 0 until arr.length()) {
                    val ptArr = arr.getJSONArray(i)
                    list.add(Pair(ptArr.getDouble(0).toFloat(), ptArr.getDouble(1).toFloat()))
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }

        /**
         * Extracts a rotation-invariant geometric signature from detected cells and strip bounds.
         */
        fun extract(cells: List<Cell>, stripBox: RectF?): BaselineGeometrySignature? {
            if (cells.size < 4) return null
            val box = stripBox ?: run {
                var l = Float.MAX_VALUE
                var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE
                var b = -Float.MAX_VALUE
                for (c in cells) {
                    l = min(l, c.box.left)
                    t = min(t, c.box.top)
                    r = max(r, c.box.right)
                    b = max(b, c.box.bottom)
                }
                if (r > l && b > t) RectF(l, t, r, b) else null
            } ?: return null

            val bw = box.width()
            val bh = box.height()
            if (bw <= 0f || bh <= 0f) return null

            // Rotation-invariant aspect ratio: max / min
            val maxSide = max(bw, bh)
            val minSide = min(bw, bh)
            val aspectRatio = if (minSide > 0.001f) maxSide / minSide else 1.0f

            // Median cell size relative to strip area
            val stripArea = bw * bh
            val cellAreas = cells.map { it.box.width() * it.box.height() }.sorted()
            val medianCellArea = cellAreas[cellAreas.size / 2]
            val relativeCellSize = if (stripArea > 0f) medianCellArea / stripArea else 0f

            // Principal axis alignment to achieve rotation-invariant normalized centers
            val n = cells.size
            val centers = cells.map { Pair((it.box.left + it.box.right) / 2f, (it.box.top + it.box.bottom) / 2f) }
            val mx = centers.map { it.first }.average().toFloat()
            val my = centers.map { it.second }.average().toFloat()

            var sxx = 0f
            var syy = 0f
            var sxy = 0f
            for (pt in centers) {
                val dx = pt.first - mx
                val dy = pt.second - my
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
            val angle = 0.5f * atan2(2f * sxy, sxx - syy)
            val ca = cos(angle)
            val sa = sin(angle)

            // Rotated coordinates around centroid
            val rotated = centers.map { pt ->
                val dx = pt.first - mx
                val dy = pt.second - my
                val rx = dx * ca + dy * sa
                val ry = -dx * sa + dy * ca
                Pair(rx, ry)
            }

            val rMinX = rotated.minOf { it.first }
            val rMaxX = rotated.maxOf { it.first }
            val rMinY = rotated.minOf { it.second }
            val rMaxY = rotated.maxOf { it.second }
            val rSpanX = max(0.001f, rMaxX - rMinX)
            val rSpanY = max(0.001f, rMaxY - rMinY)

            // Orient consistently along longest dimension
            val isXLonger = rSpanX >= rSpanY
            val normalized = rotated.map { (rx, ry) ->
                val u = if (isXLonger) (rx - rMinX) / rSpanX else (ry - rMinY) / rSpanY
                val v = if (isXLonger) (ry - rMinY) / rSpanY else (rx - rMinX) / rSpanX
                Pair(u, v)
            }.sortedWith(compareBy({ it.first }, { it.second }))

            // Row and column estimate from clustered projection
            val uCoords = normalized.map { it.first }.sorted()
            val vCoords = normalized.map { it.second }.sorted()

            // Count clusters separated by at least 0.12 in normalized coordinates
            var cols = 1
            for (i in 1 until uCoords.size) {
                if (uCoords[i] - uCoords[i - 1] > 0.12f) cols++
            }
            var rows = 1
            for (i in 1 until vCoords.size) {
                if (vCoords[i] - vCoords[i - 1] > 0.12f) rows++
            }

            // Bound rows and cols
            cols = cols.coerceIn(1, n)
            rows = rows.coerceIn(1, n)

            return BaselineGeometrySignature(
                totalCellCount = n,
                normalizedCenters = normalized,
                relativeCellSize = relativeCellSize,
                estimatedRows = min(rows, cols),
                estimatedColumns = max(rows, cols),
                stripAspectRatio = aspectRatio,
                baselineVersion = 1
            )
        }

        /**
         * Compares current strip detection geometry to the enrolled signature.
         * Returns a [GeometryComparisonResult] with pass/fail and a descriptive user message.
         */
        fun compare(current: BaselineGeometrySignature, baseline: BaselineGeometrySignature): GeometryComparisonResult {
            // 1. Cell count check
            val countDiff = abs(current.totalCellCount - baseline.totalCellCount)
            if (countDiff > 1) {
                return GeometryComparisonResult(
                    matches = false,
                    reason = "Cell count mismatch (detected ${current.totalCellCount} cavities, baseline has ${baseline.totalCellCount})"
                )
            }

            // 2. Aspect ratio check (rotation-invariant)
            val ratioDelta = abs(current.stripAspectRatio - baseline.stripAspectRatio) / max(0.01f, baseline.stripAspectRatio)
            if (ratioDelta > 0.40f) {
                return GeometryComparisonResult(
                    matches = false,
                    reason = "Strip shape mismatch: strip aspect ratio differs significantly from the enrolled blister pack"
                )
            }

            // 3. Grid arrangement check (rows x cols)
            val rowDiff = abs(current.estimatedRows - baseline.estimatedRows)
            val colDiff = abs(current.estimatedColumns - baseline.estimatedColumns)
            if (rowDiff > 1 && colDiff > 1) {
                return GeometryComparisonResult(
                    matches = false,
                    reason = "Cavity arrangement mismatch: cavity layout differs from enrolled baseline"
                )
            }

            return GeometryComparisonResult(matches = true)
        }
    }
}

data class GeometryComparisonResult(
    val matches: Boolean,
    val reason: String? = null
)
