package com.meditrack.app.core.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Handles aspect-ratio preserving letterbox scaling and coordinate transformations
 * for neural network vision models (such as YOLOv8 320x320).
 *
 * Prevents aspect ratio distortion by uniformly scaling the source image and
 * padding remaining margins with YOLO-standard gray (114/255, #727272).
 *
 * Provides exact forward and inverse mappings between:
 * - Normalized original coordinates [0..1]
 * - Model letterbox pixel coordinates [0..targetSize]
 */
class LetterboxTransform(
    val srcWidth: Int,
    val srcHeight: Int,
    val targetWidth: Int = DEFAULT_TARGET_SIZE,
    val targetHeight: Int = DEFAULT_TARGET_SIZE
) {
    companion object {
        const val DEFAULT_TARGET_SIZE = 320
        const val DEFAULT_PAD_COLOR = 114 // 114/255.0f (0x727272) standard YOLO padding
    }

    init {
        require(srcWidth > 0) { "srcWidth must be > 0, was $srcWidth" }
        require(srcHeight > 0) { "srcHeight must be > 0, was $srcHeight" }
        require(targetWidth > 0) { "targetWidth must be > 0, was $targetWidth" }
        require(targetHeight > 0) { "targetHeight must be > 0, was $targetHeight" }
    }

    val scale: Float = min(targetWidth.toFloat() / srcWidth, targetHeight.toFloat() / srcHeight)
    val scaledWidth: Float = srcWidth * scale
    val scaledHeight: Float = srcHeight * scale
    val padX: Float = (targetWidth - scaledWidth) / 2f
    val padY: Float = (targetHeight - scaledHeight) / 2f

    /**
     * Maps normalized point (normX, normY) in original image [0..1]
     * to letterbox pixel coordinates [0..targetSize].
     */
    fun toLetterboxPixels(normX: Float, normY: Float): Pair<Float, Float> {
        val x = padX + normX.coerceIn(0f, 1f) * scaledWidth
        val y = padY + normY.coerceIn(0f, 1f) * scaledHeight
        return Pair(x, y)
    }

    /**
     * Maps normalized point (normX, normY) in original image [0..1]
     * to normalized letterbox coordinates [0..1].
     */
    fun toLetterboxNormalized(normX: Float, normY: Float): Pair<Float, Float> {
        val (x, y) = toLetterboxPixels(normX, normY)
        return Pair(x / targetWidth, y / targetHeight)
    }

    /**
     * Forward transforms a normalized bounding box [0..1] in original image
     * to letterbox pixel center and size (xc, yc, w, h).
     */
    fun toLetterboxCenterSize(normBox: RectF): FloatArray {
        val left = padX + normBox.left.coerceIn(0f, 1f) * scaledWidth
        val right = padX + normBox.right.coerceIn(0f, 1f) * scaledWidth
        val top = padY + normBox.top.coerceIn(0f, 1f) * scaledHeight
        val bottom = padY + normBox.bottom.coerceIn(0f, 1f) * scaledHeight

        val w = right - left
        val h = bottom - top
        val xc = left + w / 2f
        val yc = top + h / 2f
        return floatArrayOf(xc, yc, w, h)
    }

    /**
     * Inverse transform: Maps letterbox bounding box (xc, yc, w, h)
     * back to normalized coordinates in the original image [0..1].
     *
     * Handles both:
     * - Model pixel coordinates (xc, yc, w, h in 0..targetSize)
     * - Normalized letterbox coordinates (xc, yc, w, h in 0..1)
     */
    fun toOriginalNormalized(
        xc: Float,
        yc: Float,
        w: Float,
        h: Float
    ): RectF {
        // If xc, yc, w, h are normalized (<= 1.0f), convert to letterbox pixels first
        val isNormalized = xc <= 1.0f && yc <= 1.0f && w <= 1.0f && h <= 1.0f && targetWidth > 1
        val pixelXc = if (isNormalized) xc * targetWidth else xc
        val pixelYc = if (isNormalized) yc * targetHeight else yc
        val pixelW = if (isNormalized) w * targetWidth else w
        val pixelH = if (isNormalized) h * targetHeight else h

        val normXc = (pixelXc - padX) / scaledWidth
        val normYc = (pixelYc - padY) / scaledHeight
        val normW = pixelW / scaledWidth
        val normH = pixelH / scaledHeight

        val xmin = (normXc - normW / 2f).coerceIn(0f, 1f)
        val ymin = (normYc - normH / 2f).coerceIn(0f, 1f)
        val xmax = (normXc + normW / 2f).coerceIn(0f, 1f)
        val ymax = (normYc + normH / 2f).coerceIn(0f, 1f)

        return RectF(xmin, ymin, xmax, ymax)
    }

    /**
     * Inverse transform for SSD corner coordinates [ymin, xmin, ymax, xmax].
     */
    fun toOriginalNormalizedBox(
        ymin: Float,
        xmin: Float,
        ymax: Float,
        xmax: Float
    ): RectF {
        val isNormalized = xmin <= 1.0f && ymin <= 1.0f && xmax <= 1.0f && ymax <= 1.0f && targetWidth > 1
        val pixelXmin = if (isNormalized) xmin * targetWidth else xmin
        val pixelYmin = if (isNormalized) ymin * targetHeight else ymin
        val pixelXmax = if (isNormalized) xmax * targetWidth else xmax
        val pixelYmax = if (isNormalized) ymax * targetHeight else ymax

        val origXmin = ((pixelXmin - padX) / scaledWidth).coerceIn(0f, 1f)
        val origYmin = ((pixelYmin - padY) / scaledHeight).coerceIn(0f, 1f)
        val origXmax = ((pixelXmax - padX) / scaledWidth).coerceIn(0f, 1f)
        val origYmax = ((pixelYmax - padY) / scaledHeight).coerceIn(0f, 1f)

        return RectF(
            min(origXmin, origXmax),
            min(origYmin, origYmax),
            max(origXmin, origXmax),
            max(origYmin, origYmax)
        )
    }

    /**
     * Scales and paints [src] onto a letterboxed Bitmap of size [targetWidth] x [targetHeight]
     * with uniform background color [padColor] (default 114 gray).
     */
    fun applyLetterbox(src: Bitmap, padColor: Int = DEFAULT_PAD_COLOR): Bitmap {
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val gray = padColor.coerceIn(0, 255)
        val padRgb = Color.rgb(gray, gray, gray)
        output.eraseColor(padRgb)

        val canvas = Canvas(output)
        canvas.drawColor(padRgb)

        val srcRect = Rect(0, 0, src.width, src.height)
        val dstRect = RectF(padX, padY, padX + scaledWidth, padY + scaledHeight)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return output
    }
}
