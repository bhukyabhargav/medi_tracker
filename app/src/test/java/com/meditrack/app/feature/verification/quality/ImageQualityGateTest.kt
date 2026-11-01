package com.meditrack.app.feature.verification.quality

import android.graphics.Bitmap
import android.graphics.Color
import com.meditrack.app.domain.model.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageQualityGateTest {

    @Test
    fun sharp_high_contrast_pattern_passes_quality_check() {
        // 200x200 high-frequency checkerboard pattern
        val width = 200
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Alternating black (30) and bright gray (200) every 4 pixels
                val isEvenBlock = ((x / 4) + (y / 4)) % 2 == 0
                val c = if (isEvenBlock) Color.rgb(30, 30, 30) else Color.rgb(200, 200, 200)
                bitmap.setPixel(x, y, c)
            }
        }

        val result = ImageQualityGate.analyze(bitmap)

        assertTrue("Expected sharp checkerboard to pass quality gate, but failed: ${result.reason}", result.passed)
        assertNull(result.reason)
        assertTrue("Blur variance must exceed threshold: ${result.metrics.blurVariance}", result.metrics.blurVariance >= 100.0)
    }

    @Test
    fun solid_smooth_image_fails_blurry() {
        // Uniform gray image has zero Laplacian variance (perfectly blurry / flat)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                bitmap.setPixel(x, y, Color.rgb(128, 128, 128))
            }
        }

        val result = ImageQualityGate.analyze(bitmap)

        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.reason)
        assertTrue(result.metrics.blurVariance < 100.0)
    }

    @Test
    fun pitch_black_image_fails_too_dark() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                bitmap.setPixel(x, y, Color.rgb(10, 10, 10))
            }
        }

        val result = ImageQualityGate.analyze(bitmap)

        assertFalse(result.passed)
        assertEquals(RejectReason.TOO_DARK, result.reason)
        assertTrue(result.metrics.meanBrightness < 40.0)
    }

    @Test
    fun washed_out_white_image_fails_too_bright() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                bitmap.setPixel(x, y, Color.rgb(240, 240, 240))
            }
        }

        val result = ImageQualityGate.analyze(bitmap)

        assertFalse(result.passed)
        assertEquals(RejectReason.TOO_BRIGHT, result.reason)
        assertTrue(result.metrics.meanBrightness > 220.0)
    }

    @Test
    fun image_with_large_glare_patch_fails_glare() {
        val width = 100
        val height = 100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 80% normal exposure (gray 120), 20% near-white glare (255)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isGlare = x < 20 && y < 100 // 20% of image area
                val color = if (isGlare) Color.rgb(255, 255, 255) else Color.rgb(120, 120, 120)
                bitmap.setPixel(x, y, color)
            }
        }

        val result = ImageQualityGate.analyze(bitmap)

        assertFalse(result.passed)
        assertEquals(RejectReason.GLARE, result.reason)
        assertTrue(result.metrics.glareFraction > 0.08)
    }
}
