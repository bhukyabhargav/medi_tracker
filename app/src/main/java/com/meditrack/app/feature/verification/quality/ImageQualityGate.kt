package com.meditrack.app.feature.verification.quality

import android.graphics.Bitmap
import android.graphics.RectF
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.core.config.VerificationConfig
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.feature.verification.pipeline.Cell
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class QualityMetrics(
    val blurVariance: Double = 0.0,
    val meanBrightness: Double = 0.0,
    val glareFraction: Double = 0.0,
    val contrast: Double = 0.0,
    val effectiveWidthPx: Int = 0,
    val effectiveHeightPx: Int = 0
)

data class QualityResult(
    val passed: Boolean,
    val reason: RejectReason? = null,
    val friendlyMessage: String? = null,
    val metrics: QualityMetrics = QualityMetrics()
)

object ImageQualityGate {

    /**
     * Level-1 Light Check on the original image:
     * Validates minimum dimensions and ensures the image is not near-black or near-white overall.
     * Does NOT reject for background blur, background darkness, or background glare.
     */
    fun analyzeLevel1(bitmap: Bitmap): QualityResult {
        val width = bitmap.width
        val height = bitmap.height

        if (width < VerificationConfig.globalMinDimensionPx || height < VerificationConfig.globalMinDimensionPx) {
            return QualityResult(
                passed = false,
                reason = RejectReason.BLURRY,
                friendlyMessage = "Image resolution is too low. Please retake the photo.",
                metrics = QualityMetrics(effectiveWidthPx = width, effectiveHeightPx = height)
            )
        }

        // Fast downsample for global exposure check
        val scaled = scaleDownIfNeeded(bitmap, 160)
        val sWidth = scaled.width
        val sHeight = scaled.height
        val total = sWidth * sHeight
        val pixels = IntArray(total)
        scaled.getPixels(pixels, 0, sWidth, 0, 0, sWidth, sHeight)
        if (scaled !== bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }

        var sumLum = 0.0
        for (i in 0 until total) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            sumLum += lum
        }
        val meanBrightness = sumLum / total

        if (meanBrightness < VerificationConfig.globalBrightnessMin) {
            return QualityResult(
                passed = false,
                reason = RejectReason.TOO_DARK,
                friendlyMessage = "Photo is completely dark. Please ensure sufficient light or turn on flash.",
                metrics = QualityMetrics(meanBrightness = meanBrightness, effectiveWidthPx = width, effectiveHeightPx = height)
            )
        }

        if (meanBrightness > VerificationConfig.globalBrightnessMax) {
            return QualityResult(
                passed = false,
                reason = RejectReason.TOO_BRIGHT,
                friendlyMessage = "Photo is completely washed out. Please avoid shining harsh lights directly at the camera.",
                metrics = QualityMetrics(meanBrightness = meanBrightness, effectiveWidthPx = width, effectiveHeightPx = height)
            )
        }

        return QualityResult(
            passed = true,
            metrics = QualityMetrics(meanBrightness = meanBrightness, effectiveWidthPx = width, effectiveHeightPx = height)
        )
    }

    /**
     * Resolves the effective strip bounding box for quality inspection,
     * using the model's stripBox or the enclosing bounds of the detected cells.
     */
    fun resolveStripBox(stripBox: RectF?, cells: List<Cell>): RectF? {
        if (stripBox != null) return stripBox
        if (cells.isEmpty()) return null
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
        return if (r > l && b > t) RectF(l, t, r, b) else null
    }

    /**
     * Level-2 Blister Crop Quality Check:
     * Crops the strip box (+ configurable padding, clamped to image bounds) and runs
     * blur (Laplacian variance), brightness, contrast, glare, and effective-resolution checks on that crop.
     * A sharp background must never rescue a blurry strip; a good strip must not be rejected for a bad background.
     */
    fun analyzeBlisterCrop(bitmap: Bitmap, stripBox: RectF?): QualityResult {
        if (stripBox == null) {
            return analyzeCrop(bitmap, 0, 0, bitmap.width, bitmap.height)
        }

        val padding = VerificationConfig.stripCropQualityPadding
        val padW = (stripBox.right - stripBox.left) * padding
        val padH = (stripBox.bottom - stripBox.top) * padding

        val normLeft = max(0f, stripBox.left - padW)
        val normTop = max(0f, stripBox.top - padH)
        val normRight = min(1f, stripBox.right + padW)
        val normBottom = min(1f, stripBox.bottom + padH)

        val pxLeft = (normLeft * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val pxTop = (normTop * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val pxRight = (normRight * bitmap.width).toInt().coerceIn(pxLeft + 1, bitmap.width)
        val pxBottom = (normBottom * bitmap.height).toInt().coerceIn(pxTop + 1, bitmap.height)

        val cropWidth = pxRight - pxLeft
        val cropHeight = pxBottom - pxTop

        return analyzeCrop(bitmap, pxLeft, pxTop, cropWidth, cropHeight)
    }

    private fun analyzeCrop(source: Bitmap, left: Int, top: Int, width: Int, height: Int): QualityResult {
        // Effective resolution check on blister crop
        if (width < VerificationConfig.minBlisterResolutionPx || height < VerificationConfig.minBlisterResolutionPx) {
            return QualityResult(
                passed = false,
                reason = RejectReason.BLURRY,
                friendlyMessage = "Blister pack is too small or too far away. Please hold the phone closer to the strip.",
                metrics = QualityMetrics(effectiveWidthPx = width, effectiveHeightPx = height)
            )
        }

        var cropBitmap: Bitmap? = null
        try {
            cropBitmap = Bitmap.createBitmap(source, left, top, width, height)
            val scaled = scaleDownIfNeeded(cropBitmap, AppConfig.QUALITY_ANALYSIS_MAX_SIDE_PX)
            val cWidth = scaled.width
            val cHeight = scaled.height
            val totalPixels = cWidth * cHeight
            val pixels = IntArray(totalPixels)
            scaled.getPixels(pixels, 0, cWidth, 0, 0, cWidth, cHeight)
            if (scaled !== cropBitmap && !scaled.isRecycled) {
                scaled.recycle()
            }

            val luminance = FloatArray(totalPixels)
            var sumLum = 0.0
            var sumLumSq = 0.0
            var nearWhiteCount = 0

            for (i in 0 until totalPixels) {
                val color = pixels[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                luminance[i] = lum
                sumLum += lum
                sumLumSq += lum * lum
                if (lum >= 250.0f) {
                    nearWhiteCount++
                }
            }

            val meanBrightness = sumLum / totalPixels
            val varianceBrightness = max(0.0, (sumLumSq / totalPixels) - (meanBrightness * meanBrightness))
            val contrast = sqrt(varianceBrightness)
            val glareFraction = nearWhiteCount.toDouble() / totalPixels

            // Brightness checks on blister
            if (meanBrightness < VerificationConfig.blisterBrightnessMin) {
                return QualityResult(
                    passed = false,
                    reason = RejectReason.TOO_DARK,
                    friendlyMessage = "Blister pack is too dark. Please take photo in better lighting or turn on flash.",
                    metrics = QualityMetrics(meanBrightness = meanBrightness, glareFraction = glareFraction, contrast = contrast, effectiveWidthPx = width, effectiveHeightPx = height)
                )
            }

            if (meanBrightness > VerificationConfig.blisterBrightnessMax) {
                return QualityResult(
                    passed = false,
                    reason = RejectReason.TOO_BRIGHT,
                    friendlyMessage = "Blister pack is too bright. Please avoid harsh reflections or direct lighting.",
                    metrics = QualityMetrics(meanBrightness = meanBrightness, glareFraction = glareFraction, contrast = contrast, effectiveWidthPx = width, effectiveHeightPx = height)
                )
            }

            // Contrast check on blister
            if (contrast < VerificationConfig.blisterContrastMin) {
                return QualityResult(
                    passed = false,
                    reason = RejectReason.BLURRY,
                    friendlyMessage = "Low contrast on blister pack. Please improve lighting.",
                    metrics = QualityMetrics(meanBrightness = meanBrightness, glareFraction = glareFraction, contrast = contrast, effectiveWidthPx = width, effectiveHeightPx = height)
                )
            }

            // Glare check on blister
            if (glareFraction > VerificationConfig.blisterGlareFractionMax) {
                return QualityResult(
                    passed = false,
                    reason = RejectReason.GLARE,
                    friendlyMessage = "Glare detected on the blister cavities. Please tilt the strip slightly to avoid reflection.",
                    metrics = QualityMetrics(meanBrightness = meanBrightness, glareFraction = glareFraction, contrast = contrast, effectiveWidthPx = width, effectiveHeightPx = height)
                )
            }

            // Blur: Laplacian variance on blister crop
            var lapSum = 0.0
            var lapSqSum = 0.0
            val lapCount = (cWidth - 2) * (cHeight - 2)

            if (lapCount > 0) {
                for (y in 1 until cHeight - 1) {
                    val rowOffset = y * cWidth
                    val prevRowOffset = (y - 1) * cWidth
                    val nextRowOffset = (y + 1) * cWidth

                    for (x in 1 until cWidth - 1) {
                        val center = luminance[rowOffset + x]
                        val t = luminance[prevRowOffset + x]
                        val b = luminance[nextRowOffset + x]
                        val l = luminance[rowOffset + (x - 1)]
                        val r = luminance[rowOffset + (x + 1)]

                        val lapVal = (t + b + l + r - 4f * center).toDouble()
                        lapSum += lapVal
                        lapSqSum += lapVal * lapVal
                    }
                }
            }

            val lapMean = if (lapCount > 0) lapSum / lapCount else 0.0
            val blurVariance = if (lapCount > 0) (lapSqSum / lapCount) - (lapMean * lapMean) else 0.0

            if (blurVariance < VerificationConfig.blisterBlurVarianceMin) {
                return QualityResult(
                    passed = false,
                    reason = RejectReason.BLURRY,
                    friendlyMessage = "Photo is blurry. Please hold phone steady and lay blister strip flat.",
                    metrics = QualityMetrics(blurVariance = blurVariance, meanBrightness = meanBrightness, glareFraction = glareFraction, contrast = contrast, effectiveWidthPx = width, effectiveHeightPx = height)
                )
            }

            return QualityResult(
                passed = true,
                metrics = QualityMetrics(blurVariance = blurVariance, meanBrightness = meanBrightness, glareFraction = glareFraction, contrast = contrast, effectiveWidthPx = width, effectiveHeightPx = height)
            )
        } finally {
            cropBitmap?.let {
                if (it !== source && !it.isRecycled) {
                    it.recycle()
                }
            }
        }
    }

    /**
     * Evaluates quality: Level-1 on the overall image followed by Level-2 on the blister crop.
     */
    fun analyze(bitmap: Bitmap, stripBox: RectF? = null): QualityResult {
        val level1 = analyzeLevel1(bitmap)
        if (!level1.passed) return level1
        return analyzeBlisterCrop(bitmap, stripBox)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxSide: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSide && height <= maxSide) {
            return bitmap
        }

        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (width >= height) {
            targetWidth = maxSide
            targetHeight = (maxSide / ratio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = maxSide
            targetWidth = (maxSide * ratio).toInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
