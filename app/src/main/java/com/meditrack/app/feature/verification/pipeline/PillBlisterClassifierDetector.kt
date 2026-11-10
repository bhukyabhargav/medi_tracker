package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.meditrack.app.core.config.VerificationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Classical Computer Vision blister pack detector and empty-cavity classifier,
 * ported from the open-source pipeline (https://github.com/aziz-hizem/pill-blister-classifier.git).
 *
 * Algorithm breakdown:
 * 1. Foil-referenced White Balance correction (uses achromatic metallic foil as neutral reference).
 * 2. Adaptive Pill Color Classification (distinguishes white pills from colored formulations via HSV saturation).
 * 3. Segmentation:
 *    - White pills: Otsu brightness thresholding + morphological open/close + hole-fill.
 *    - Colored pills: Local contrast enhancement + saturation and darkness masks.
 * 4. Component analysis & contour filtering:
 *    - Connected component labeling with union-find.
 *    - Filtering by area bounds, bounding solidity, and aspect ratio.
 * 5. Grid structure inference & Empty Cell extrapolation:
 *    - Y-clustering into rows and X-clustering into columns.
 *    - Robust row and column spacing estimation via minimum within-row gaps.
 *    - Interior row interpolation and outward grid extrapolation.
 *    - Bare-foil color verification on unoccupied grid intersections (achromatic S <= 75, medium brightness 45 <= V <= 225).
 * 6. Geometric frame containment verification (stripFullyInFrame).
 */
@Singleton
class PillBlisterClassifierDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : StripDetector, Closeable {

    companion object {
        const val MAX_PROCESS_DIMENSION = 800
        const val BRIGHTNESS_FLOOR_WHITE = 90
        const val FOIL_SATURATION_MAX = 75
        const val FOIL_VALUE_MIN = 45
        const val FOIL_VALUE_MAX = 225
    }

    private val mutex = Mutex()

    data class PillCandidate(
        val box: RectF,
        val cx: Float,
        val cy: Float,
        val area: Float,
        val isFull: Boolean = true
    )

    override suspend fun detect(bitmap: Bitmap): Detection = mutex.withLock {
        withContext(Dispatchers.Default) {
            val origW = bitmap.width
            val origH = bitmap.height

            // Scale down to standard processing dimension (preserving aspect ratio)
            val scale = min(1.0f, MAX_PROCESS_DIMENSION.toFloat() / max(origW, origH).toFloat())
            val procW = max(1, (origW * scale).roundToInt())
            val procH = max(1, (origH * scale).roundToInt())

            val scaledBitmap = if (procW == origW && procH == origH) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, procW, procH, true)
            }

            val pixels = IntArray(procW * procH)
            scaledBitmap.getPixels(pixels, 0, procW, 0, 0, procW, procH)
            if (scaledBitmap !== bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }

            // 1. Foil-referenced White Balance
            val wbPixels = correctWhiteBalance(pixels, procW, procH)

            // 2. Pill Color Classification
            val isColored = classifyPillColor(wbPixels)

            // 3. Segmentation
            val pillMask = if (isColored) {
                segmentColoredPills(wbPixels, procW, procH)
            } else {
                segmentWhitePills(wbPixels, procW, procH)
            }

            // 4. Extract connected components for pills
            val pillCandidates = extractPillCandidates(pillMask, procW, procH)

            // 5. Grid inference & empty cell detection
            val emptyCandidates = detectEmptyCells(
                pillCandidates = pillCandidates,
                pixels = wbPixels,
                width = procW,
                height = procH
            )

            // 6. Convert to Cells
            val allCells = mutableListOf<Cell>()
            for (p in pillCandidates) {
                allCells.add(
                    Cell(
                        box = p.box,
                        state = CellState.FULL,
                        confidence = 0.92f
                    )
                )
            }
            for (e in emptyCandidates) {
                allCells.add(
                    Cell(
                        box = e.box,
                        state = CellState.EMPTY,
                        confidence = 0.88f
                    )
                )
            }

            // 7. Enclosing strip box & in-frame check
            val stripBox = deriveStripBox(allCells)
            val isFullyInFrame = computeStripFullyInFrame(
                stripBox = stripBox,
                cells = allCells,
                tolerance = VerificationConfig.borderToleranceFraction,
                cellCutoffTolerance = VerificationConfig.cellCutoffToleranceFraction
            )

            Detection(
                cells = allCells,
                stripFullyInFrame = isFullyInFrame,
                stripConfidence = if (allCells.isNotEmpty()) 0.90f else 0.0f,
                stripBox = stripBox
            )
        }
    }

    /**
     * Normalizes camera color temperature by measuring achromatic metallic foil.
     */
    internal fun correctWhiteBalance(pixels: IntArray, width: Int, height: Int): IntArray {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var foilCount = 0

        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val maxVal = maxOf(r, maxOf(g, b))
            val minVal = minOf(r, minOf(g, b))
            val delta = maxVal - minVal
            val s = if (maxVal == 0) 0 else (delta * 255) / maxVal
            val v = maxVal

            if (s <= 60 && v in 70..230) {
                sumR += r
                sumG += g
                sumB += b
                foilCount++
            }
        }

        val totalPixels = width * height
        if (foilCount < totalPixels * 0.04) {
            return pixels // Not enough foil detected, keep original colors
        }

        val meanR = sumR / foilCount
        val meanG = sumG / foilCount
        val meanB = sumB / foilCount

        val target = 180.0
        val gainR = target / max(meanR, 1.0)
        val gainG = target / max(meanG, 1.0)
        val gainB = target / max(meanB, 1.0)

        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val nr = (r * gainR).roundToInt().coerceIn(0, 255)
            val ng = (g * gainG).roundToInt().coerceIn(0, 255)
            val nb = (b * gainB).roundToInt().coerceIn(0, 255)

            out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return out
    }

    /**
     * Determines whether formulation is colored or white based on HSV saturation of non-dark pixels.
     */
    internal fun classifyPillColor(pixels: IntArray): Boolean {
        var nonDarkCount = 0
        var vividCount = 0

        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val maxVal = maxOf(r, maxOf(g, b))
            val minVal = minOf(r, minOf(g, b))
            val delta = maxVal - minVal
            val s = if (maxVal == 0) 0 else (delta * 255) / maxVal
            val v = maxVal

            if (v > 40) {
                nonDarkCount++
                if (s > 45) {
                    vividCount++
                }
            }
        }

        if (nonDarkCount == 0) return false
        val ratio = vividCount.toFloat() / nonDarkCount.toFloat()
        return ratio > 0.04f
    }

    /**
     * White pill segmentation via Otsu thresholding + morphological cleanup + hole fill.
     */
    internal fun segmentWhitePills(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val total = width * height
        val gray = IntArray(total)
        val hist = IntArray(256)

        for (i in 0 until total) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (299 * r + 587 * g + 114 * b) / 1000
            gray[i] = luma
            hist[luma]++
        }

        // Otsu calculation
        var sumAll = 0.0
        for (t in 0..255) sumAll += t * hist[t]

        var sumB = 0.0
        var wB = 0
        var maxVariance = 0.0
        var bestT1 = BRIGHTNESS_FLOOR_WHITE
        var bestT2 = BRIGHTNESS_FLOOR_WHITE

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val variance = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)

            if (variance > maxVariance) {
                maxVariance = variance
                bestT1 = t
                bestT2 = t
            } else if (variance == maxVariance && maxVariance > 0.0) {
                bestT2 = t
            }
        }

        val otsuThresh = (bestT1 + bestT2) / 2
        val thresh = max(otsuThresh, BRIGHTNESS_FLOOR_WHITE)
        val binary = BooleanArray(total) { gray[it] >= thresh }

        // Morphological open (radius 1) then close (radius 3)
        val opened = morphOpen(binary, width, height, radius = 1)
        val closed = morphClose(opened, width, height, radius = 3)
        return fillHoles(closed, width, height)
    }

    /**
     * Colored pill segmentation via local adaptive contrast + saturation mask + dark mask.
     */
    internal fun segmentColoredPills(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val total = width * height
        val binary = BooleanArray(total)

        // Integral image of gray for fast adaptive threshold
        val gray = IntArray(total)
        for (i in 0 until total) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (299 * r + 587 * g + 114 * b) / 1000
        }

        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += gray[y * width + x]
                integral[(y + 1) * (width + 1) + (x + 1)] =
                    integral[y * (width + 1) + (x + 1)] + rowSum
            }
        }

        val radius = 17 // 35x35 block size
        val bias = 15

        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height, y + radius + 1)
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width, x + radius + 1)

                val count = (x1 - x0) * (y1 - y0)
                val sum = integral[y1 * (width + 1) + x1] -
                        integral[y0 * (width + 1) + x1] -
                        integral[y1 * (width + 1) + x0] +
                        integral[y0 * (width + 1) + x0]
                val localMean = sum / count

                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val maxVal = maxOf(r, maxOf(g, b))
                val minVal = minOf(r, minOf(g, b))
                val delta = maxVal - minVal
                val s = if (maxVal == 0) 0 else (delta * 255) / maxVal
                val v = maxVal

                val isAdaptive = (gray[y * width + x] < localMean - bias)
                val isSat = (s >= 35)
                val isDark = (v <= 80)

                binary[y * width + x] = (isAdaptive || isSat || isDark)
            }
        }

        // Check border ratio: if > 50% border is white, invert
        var borderWhite = 0
        var borderTotal = 0
        val bMarginW = max(1, (width * 0.05).toInt())
        val bMarginH = max(1, (height * 0.05).toInt())

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < bMarginW || x >= width - bMarginW || y < bMarginH || y >= height - bMarginH) {
                    borderTotal++
                    if (binary[y * width + x]) borderWhite++
                }
            }
        }

        val effective = if (borderTotal > 0 && (borderWhite.toFloat() / borderTotal) > 0.5f) {
            BooleanArray(total) { !binary[it] }
        } else {
            binary
        }

        val opened = morphOpen(effective, width, height, radius = 1)
        val closed = morphClose(opened, width, height, radius = 2)
        return fillHoles(closed, width, height)
    }

    /**
     * Connected Component Labeling using Two-Pass Disjoint Set Union (DSU).
     */
    internal fun extractPillCandidates(
        mask: BooleanArray,
        width: Int,
        height: Int
    ): List<PillCandidate> {
        val total = width * height
        val labels = IntArray(total)
        val parent = IntArray(total + 1) { it }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) root = parent[root]
            var curr = i
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) parent[rootJ] = rootI
        }

        var nextLabel = 1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!mask[idx]) continue

                val left = if (x > 0 && mask[idx - 1]) labels[idx - 1] else 0
                val top = if (y > 0 && mask[idx - width]) labels[idx - width] else 0

                if (left == 0 && top == 0) {
                    labels[idx] = nextLabel++
                } else if (left != 0 && top == 0) {
                    labels[idx] = left
                } else if (left == 0 && top != 0) {
                    labels[idx] = top
                } else {
                    labels[idx] = left
                    if (left != top) union(left, top)
                }
            }
        }

        class CompStats {
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var sumX = 0L
            var sumY = 0L
            var area = 0
        }

        val compMap = HashMap<Int, CompStats>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val rawLabel = labels[idx]
                if (rawLabel > 0) {
                    val root = find(rawLabel)
                    val stats = compMap.getOrPut(root) { CompStats() }
                    stats.area++
                    stats.sumX += x
                    stats.sumY += y
                    stats.minX = min(stats.minX, x)
                    stats.minY = min(stats.minY, y)
                    stats.maxX = max(stats.maxX, x)
                    stats.maxY = max(stats.maxY, y)
                }
            }
        }

        val minArea = 0.0015f * total
        val maxArea = 0.25f * total
        val results = mutableListOf<PillCandidate>()

        for (stats in compMap.values) {
            if (stats.area < minArea || stats.area > maxArea) continue

            val bw = (stats.maxX - stats.minX + 1)
            val bh = (stats.maxY - stats.minY + 1)
            val boxArea = bw * bh
            val solidity = stats.area.toFloat() / max(boxArea, 1).toFloat()

            if (solidity >= 0.35f) {
                val cxNorm = (stats.sumX.toFloat() / stats.area.toFloat()) / width.toFloat()
                val cyNorm = (stats.sumY.toFloat() / stats.area.toFloat()) / height.toFloat()
                val left = (stats.minX.toFloat() / width.toFloat()).coerceIn(0f, 1f)
                val top = (stats.minY.toFloat() / height.toFloat()).coerceIn(0f, 1f)
                val right = (stats.maxX.toFloat() / width.toFloat()).coerceIn(0f, 1f)
                val bottom = (stats.maxY.toFloat() / height.toFloat()).coerceIn(0f, 1f)

                results.add(
                    PillCandidate(
                        box = RectF(left, top, right, bottom),
                        cx = cxNorm,
                        cy = cyNorm,
                        area = stats.area.toFloat()
                    )
                )
            }
        }

        return results
    }

    /**
     * Infers the blister regular grid layout and detects empty cavities by extrapolating
     * missing positions and verifying bare-foil signatures.
     */
    internal fun detectEmptyCells(
        pillCandidates: List<PillCandidate>,
        pixels: IntArray,
        width: Int,
        height: Int
    ): List<PillCandidate> {
        if (pillCandidates.size < 2) return emptyList()

        val avgArea = pillCandidates.map { it.area }.average().toFloat()
        val avgRadiusPx = max(5f, sqrt(avgArea / Math.PI.toFloat()))
        val avgRadiusNorm = avgRadiusPx / max(width, height).toFloat()

        // Group into rows by Y proximity
        val rowTol = avgRadiusNorm * 0.95f
        val sortedByY = pillCandidates.sortedBy { it.cy }
        val rows = mutableListOf<MutableList<PillCandidate>>()
        rows.add(mutableListOf(sortedByY[0]))

        for (i in 1 until sortedByY.size) {
            val curr = sortedByY[i]
            val lastRow = rows.last()
            val meanY = lastRow.map { it.cy }.average().toFloat()
            if (abs(curr.cy - meanY) <= rowTol) {
                lastRow.add(curr)
            } else {
                rows.add(mutableListOf(curr))
            }
        }

        for (r in rows) r.sortBy { it.cx }

        // Column spacing: minimum gap per row
        val rowMinGaps = mutableListOf<Float>()
        for (r in rows) {
            if (r.size >= 2) {
                var minG = Float.MAX_VALUE
                for (j in 1 until r.size) {
                    val gap = r[j].cx - r[j - 1].cx
                    if (gap > 0f) minG = min(minG, gap)
                }
                if (minG != Float.MAX_VALUE) rowMinGaps.add(minG)
            }
        }

        val colSpacing = if (rowMinGaps.isNotEmpty()) {
            rowMinGaps.sorted()[rowMinGaps.size / 2]
        } else {
            avgRadiusNorm * 2.5f
        }

        val rowYs = rows.map { it.map { p -> p.cy }.average().toFloat() }
        val rowSpacing = if (rowYs.size >= 2) {
            val diffs = mutableListOf<Float>()
            for (i in 1 until rowYs.size) diffs.add(rowYs[i] - rowYs[i - 1])
            diffs.sorted()[diffs.size / 2]
        } else {
            colSpacing
        }

        // Column positions: cluster pill cx coordinates
        val allX = pillCandidates.map { it.cx }.sorted()
        val colGroups = mutableListOf<MutableList<Float>>()
        colGroups.add(mutableListOf(allX[0]))

        for (i in 1 until allX.size) {
            val x = allX[i]
            val lastGroup = colGroups.last()
            val meanX = lastGroup.average().toFloat()
            if (abs(x - meanX) <= colSpacing * 0.45f) {
                lastGroup.add(x)
            } else {
                colGroups.add(mutableListOf(x))
            }
        }
        val colPos = colGroups.map { it.average().toFloat() }.toMutableList()

        // Interpolate missing interior rows
        val fullRowYs = mutableListOf(rowYs[0])
        for (i in 1 until rowYs.size) {
            val y = rowYs[i]
            val gap = y - fullRowYs.last()
            val nFill = (gap / max(rowSpacing, 0.01f)).roundToInt() - 1
            if (nFill in 1..4) {
                val step = gap / (nFill + 1)
                for (k in 1..nFill) {
                    fullRowYs.add(fullRowYs.last() + step)
                }
            }
            fullRowYs.add(y)
        }

        // Extrapolate columns outward within image boundary
        val borderMargin = avgRadiusNorm * 0.8f
        for (direction in listOf(-1, 1)) {
            var x = (if (direction == -1) colPos.first() else colPos.last()) + direction * colSpacing
            while (x in borderMargin..(1.0f - borderMargin)) {
                if (direction == -1) colPos.add(0, x) else colPos.add(x)
                x += direction * colSpacing
            }
        }

        val matchR = colSpacing * 0.45f
        val rSamplePx = max(3, (avgRadiusPx / 4).toInt())
        val emptyCells = mutableListOf<PillCandidate>()

        for (gy in fullRowYs) {
            for (gx in colPos) {
                // Check if any real pill is present at this grid slot
                val pillPresent = pillCandidates.any { p ->
                    abs(p.cx - gx) <= matchR && abs(p.cy - gy) <= rowTol
                }
                if (pillPresent) continue

                // Check foil signature in a central patch
                val cxPx = (gx * width).roundToInt()
                val cyPx = (gy * height).roundToInt()

                val y0 = max(0, cyPx - rSamplePx)
                val y1 = min(height, cyPx + rSamplePx + 1)
                val x0 = max(0, cxPx - rSamplePx)
                val x1 = min(width, cxPx + rSamplePx + 1)

                val patchS = mutableListOf<Int>()
                val patchV = mutableListOf<Int>()

                for (py in y0 until y1) {
                    for (px in x0 until x1) {
                        val pixel = pixels[py * width + px]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF

                        val maxVal = maxOf(r, maxOf(g, b))
                        val minVal = minOf(r, minOf(g, b))
                        val delta = maxVal - minVal
                        val s = if (maxVal == 0) 0 else (delta * 255) / maxVal
                        val v = maxVal
                        patchS.add(s)
                        patchV.add(v)
                    }
                }

                if (patchS.isEmpty()) continue
                patchS.sort()
                patchV.sort()
                val medianS = patchS[patchS.size / 2]
                val medianV = patchV[patchV.size / 2]

                // Foil condition: achromatic & medium brightness
                if (medianS <= FOIL_SATURATION_MAX && medianV in FOIL_VALUE_MIN..FOIL_VALUE_MAX) {
                    val halfW = (avgRadiusPx / width.toFloat())
                    val halfH = (avgRadiusPx / height.toFloat())
                    val box = RectF(
                        (gx - halfW).coerceIn(0f, 1f),
                        (gy - halfH).coerceIn(0f, 1f),
                        (gx + halfW).coerceIn(0f, 1f),
                        (gy + halfH).coerceIn(0f, 1f)
                    )
                    emptyCells.add(
                        PillCandidate(
                            box = box,
                            cx = gx,
                            cy = gy,
                            area = avgArea,
                            isFull = false
                        )
                    )
                }
            }
        }

        return emptyCells
    }

    // ── Morphological primitives ────────────────────────────────────────────────

    private fun morphOpen(src: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        return morphDilate(morphErode(src, width, height, radius), width, height, radius)
    }

    private fun morphClose(src: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        return morphErode(morphDilate(src, width, height, radius), width, height, radius)
    }

    private fun morphErode(src: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            for (x in 0 until width) {
                if (!src[y * width + x]) continue
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                var allSet = true
                checkNeighbor@ for (ny in y0..y1) {
                    for (nx in x0..x1) {
                        if (!src[ny * width + nx]) {
                            allSet = false
                            break@checkNeighbor
                        }
                    }
                }
                out[y * width + x] = allSet
            }
        }
        return out
    }

    private fun morphDilate(src: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            for (x in 0 until width) {
                if (src[y * width + x]) {
                    val x0 = max(0, x - radius)
                    val x1 = min(width - 1, x + radius)
                    for (ny in y0..y1) {
                        for (nx in x0..x1) {
                            out[ny * width + nx] = true
                        }
                    }
                }
            }
        }
        return out
    }

    private fun fillHoles(src: BooleanArray, width: Int, height: Int): BooleanArray {
        val total = width * height
        val visited = BooleanArray(total)
        val queue = ArrayDeque<Int>()

        // Seed with outer boundaries
        for (x in 0 until width) {
            if (!src[x]) {
                visited[x] = true
                queue.add(x)
            }
            val bottomIdx = (height - 1) * width + x
            if (!src[bottomIdx] && !visited[bottomIdx]) {
                visited[bottomIdx] = true
                queue.add(bottomIdx)
            }
        }
        for (y in 0 until height) {
            val leftIdx = y * width
            if (!src[leftIdx] && !visited[leftIdx]) {
                visited[leftIdx] = true
                queue.add(leftIdx)
            }
            val rightIdx = y * width + (width - 1)
            if (!src[rightIdx] && !visited[rightIdx]) {
                visited[rightIdx] = true
                queue.add(rightIdx)
            }
        }

        // BFS flood-fill background
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            val cy = curr / width
            val cx = curr % width

            val dxs = intArrayOf(1, -1, 0, 0)
            val dys = intArrayOf(0, 0, 1, -1)
            for (k in 0..3) {
                val nx = cx + dxs[k]
                val ny = cy + dys[k]
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    if (!src[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        queue.add(nIdx)
                    }
                }
            }
        }

        // Anything not reached by background flood is inside foreground or enclosed hole
        val out = BooleanArray(total)
        for (i in 0 until total) {
            out[i] = !visited[i]
        }
        return out
    }

    internal fun deriveStripBox(cells: List<Cell>, marginFraction: Float = 0.0f): RectF? {
        if (cells.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (c in cells) {
            minX = min(minX, c.box.left)
            minY = min(minY, c.box.top)
            maxX = max(maxX, c.box.right)
            maxY = max(maxY, c.box.bottom)
        }

        val w = maxX - minX
        val h = maxY - minY
        return RectF(
            (minX - w * marginFraction).coerceIn(0f, 1f),
            (minY - h * marginFraction).coerceIn(0f, 1f),
            (maxX + w * marginFraction).coerceIn(0f, 1f),
            (maxY + h * marginFraction).coerceIn(0f, 1f)
        )
    }

    internal fun computeStripFullyInFrame(
        stripBox: RectF?,
        cells: List<Cell>,
        tolerance: Float = VerificationConfig.borderToleranceFraction,
        cellCutoffTolerance: Float = VerificationConfig.cellCutoffToleranceFraction
    ): Boolean {
        if (stripBox == null && cells.isEmpty()) return false

        for (cell in cells) {
            val b = cell.box
            if (b.left <= cellCutoffTolerance ||
                b.top <= cellCutoffTolerance ||
                b.right >= (1.0f - cellCutoffTolerance) ||
                b.bottom >= (1.0f - cellCutoffTolerance)
            ) {
                return false
            }
        }

        if (stripBox != null) {
            if (stripBox.left <= tolerance ||
                stripBox.top <= tolerance ||
                stripBox.right >= (1.0f - tolerance) ||
                stripBox.bottom >= (1.0f - tolerance)
            ) {
                return false
            }
        }

        return true
    }

    override fun close() {
        // Stateless CPU implementation: no persistent unmanaged native memory to clean
    }
}
