package com.meditrack.app.core.util

import kotlin.math.max
import kotlin.math.min

/** Pixel rectangle of a crop inside the original image. */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Plans the second-stage crop around a detected strip (pure Kotlin so it is unit-testable).
 *
 * The first detector pass locates the strip even when it is small in the frame. The cells inside it are then
 * detected on a crop so they cover many more model-input pixels.
 */
object StripCropPlanner {

    private const val MIN_CROP_SIDE_PX = 48

    /**
     * @param left/top/right/bottom strip box, normalized 0..1 in the original image
     * @param marginFraction extra margin as a fraction of the box size on each side
     * @return the crop, or null when no useful crop exists (box invalid or tiny)
     */
    fun plan(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        imageWidth: Int,
        imageHeight: Int,
        marginFraction: Float
    ): CropRect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (!(right > left) || !(bottom > top)) return null

        val boxW = right - left
        val boxH = bottom - top
        val x0 = max(0f, left - marginFraction * boxW)
        val y0 = max(0f, top - marginFraction * boxH)
        val x1 = min(1f, right + marginFraction * boxW)
        val y1 = min(1f, bottom + marginFraction * boxH)

        val px0 = (x0 * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val py0 = (y0 * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val px1 = (x1 * imageWidth).toInt().coerceIn(px0 + 1, imageWidth)
        val py1 = (y1 * imageHeight).toInt().coerceIn(py0 + 1, imageHeight)

        val w = px1 - px0
        val h = py1 - py0
        if (w < MIN_CROP_SIDE_PX || h < MIN_CROP_SIDE_PX) return null
        return CropRect(px0, py0, w, h)
    }

    /** Fraction of the image area covered by a normalized box. */
    fun areaFraction(left: Float, top: Float, right: Float, bottom: Float): Float {
        val w = (right - left).coerceIn(0f, 1f)
        val h = (bottom - top).coerceIn(0f, 1f)
        return w * h
    }
}
