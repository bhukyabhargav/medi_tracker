package com.meditrack.app.feature.verification.quality

import android.graphics.Bitmap
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.RejectReason

data class QualityMetrics(
    val blurVariance: Double,
    val meanBrightness: Double,
    val glareFraction: Double
)

data class QualityResult(
    val passed: Boolean,
    val reason: RejectReason? = null,
    val friendlyMessage: String? = null,
    val metrics: QualityMetrics
)

object ImageQualityGate {

    /**
     * Evaluates a captured image for blur, exposure (brightness), and glare.
     * Pure Kotlin implementation operating on Android Bitmap.
     * Rejections at the quality gate do NOT count toward verification attempts.
     */
    fun analyze(bitmap: Bitmap): QualityResult {
        val scaled = scaleDownIfNeeded(bitmap, AppConfig.QUALITY_ANALYSIS_MAX_SIDE_PX)
        val width = scaled.width
        val height = scaled.height
        val totalPixels = width * height

        val pixels = IntArray(totalPixels)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled != bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }

        // 1. Convert to grayscale luminance (0..255)
        val luminance = FloatArray(totalPixels)
        var sumLuminance = 0.0
        var nearWhiteCount = 0

        for (i in 0 until totalPixels) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            // ITU-R BT.601 standard luminance
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            luminance[i] = lum
            sumLuminance += lum

            if (lum >= 250.0f) {
                nearWhiteCount++
            }
        }

        val meanBrightness = sumLuminance / totalPixels
        val glareFraction = nearWhiteCount.toDouble() / totalPixels

        // 2. Check Brightness: Too dark
        if (meanBrightness < AppConfig.BRIGHTNESS_MEAN_MIN) {
            return QualityResult(
                passed = false,
                reason = RejectReason.TOO_DARK,
                friendlyMessage = "Photo is too dark. Please take photo in better lighting or turn on flash.",
                metrics = QualityMetrics(0.0, meanBrightness, glareFraction)
            )
        }

        // 3. Check Brightness: Too bright
        if (meanBrightness > AppConfig.BRIGHTNESS_MEAN_MAX) {
            return QualityResult(
                passed = false,
                reason = RejectReason.TOO_BRIGHT,
                friendlyMessage = "Photo is too bright. Please avoid direct harsh lighting.",
                metrics = QualityMetrics(0.0, meanBrightness, glareFraction)
            )
        }

        // 4. Check Glare: Saturated reflective hotspots
        if (glareFraction > AppConfig.GLARE_PIXEL_FRACTION_MAX) {
            return QualityResult(
                passed = false,
                reason = RejectReason.GLARE,
                friendlyMessage = "Glare detected on the blister pack. Please tilt the strip slightly to avoid reflection.",
                metrics = QualityMetrics(0.0, meanBrightness, glareFraction)
            )
        }

        // 5. Check Blur: Variance of 3x3 Laplacian
        // Kernel:
        //  0  1  0
        //  1 -4  1
        //  0  1  0
        var lapSum = 0.0
        var lapSqSum = 0.0
        val lapCount = (width - 2) * (height - 2)

        if (lapCount > 0) {
            for (y in 1 until height - 1) {
                val rowOffset = y * width
                val prevRowOffset = (y - 1) * width
                val nextRowOffset = (y + 1) * width

                for (x in 1 until width - 1) {
                    val center = luminance[rowOffset + x]
                    val top = luminance[prevRowOffset + x]
                    val bottom = luminance[nextRowOffset + x]
                    val left = luminance[rowOffset + (x - 1)]
                    val right = luminance[rowOffset + (x + 1)]

                    val lapVal = (top + bottom + left + right - 4f * center).toDouble()
                    lapSum += lapVal
                    lapSqSum += lapVal * lapVal
                }
            }
        }

        val lapMean = if (lapCount > 0) lapSum / lapCount else 0.0
        val blurVariance = if (lapCount > 0) (lapSqSum / lapCount) - (lapMean * lapMean) else 0.0

        if (blurVariance < AppConfig.BLUR_LAPLACIAN_VARIANCE_MIN) {
            return QualityResult(
                passed = false,
                reason = RejectReason.BLURRY,
                friendlyMessage = "Photo is blurry. Please hold phone steady and lay blister strip flat.",
                metrics = QualityMetrics(blurVariance, meanBrightness, glareFraction)
            )
        }

        // All checks passed
        return QualityResult(
            passed = true,
            reason = null,
            friendlyMessage = null,
            metrics = QualityMetrics(blurVariance, meanBrightness, glareFraction)
        )
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
