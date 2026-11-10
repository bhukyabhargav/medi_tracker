package com.meditrack.app.feature.verification.quality

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.meditrack.app.core.config.VerificationConfig
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

    @Test
    fun level1_passes_blurry_background_and_rejects_only_extreme_dark_or_bright() {
        // A uniform gray background that is blurry overall but has valid dimensions and moderate brightness
        val grayBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                grayBitmap.setPixel(x, y, Color.rgb(120, 120, 120))
            }
        }
        val level1 = ImageQualityGate.analyzeLevel1(grayBitmap)
        assertTrue("Level-1 must NOT reject for blur in background", level1.passed)

        // Extreme dark image fails Level-1
        val blackBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                blackBitmap.setPixel(x, y, Color.rgb(5, 5, 5))
            }
        }
        val blackResult = ImageQualityGate.analyzeLevel1(blackBitmap)
        assertFalse(blackResult.passed)
        assertEquals(RejectReason.TOO_DARK, blackResult.reason)

        // Extreme washed out image fails Level-1
        val whiteBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                whiteBitmap.setPixel(x, y, Color.rgb(250, 250, 250))
            }
        }
        val whiteResult = ImageQualityGate.analyzeLevel1(whiteBitmap)
        assertFalse(whiteResult.passed)
        assertEquals(RejectReason.TOO_BRIGHT, whiteResult.reason)
    }

    @Test
    fun sharp_background_does_not_rescue_blurry_strip() {
        val width = 300
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Background is high-frequency sharp checkerboard
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isEven = ((x / 4) + (y / 4)) % 2 == 0
                bitmap.setPixel(x, y, if (isEven) Color.rgb(30, 30, 30) else Color.rgb(200, 200, 200))
            }
        }

        // Strip area [75, 75, 225, 225] (center 50%) is solid gray (perfectly blurry, variance = 0)
        for (y in 75 until 225) {
            for (x in 75 until 225) {
                bitmap.setPixel(x, y, Color.rgb(128, 128, 128))
            }
        }

        val stripBox = RectF(0.25f, 0.25f, 0.75f, 0.75f)

        // Level-1 passes because overall image has normal exposure and size
        val level1 = ImageQualityGate.analyzeLevel1(bitmap)
        assertTrue(level1.passed)

        // Level-2 crop check on the strip fails with BLURRY
        val level2 = ImageQualityGate.analyzeBlisterCrop(bitmap, stripBox)
        assertFalse("Sharp background must never rescue a blurry strip", level2.passed)
        assertEquals(RejectReason.BLURRY, level2.reason)
    }

    @Test
    fun dark_background_does_not_reject_sharp_strip() {
        val width = 300
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Background is dark (table / cloth: RGB 20, 20, 20)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, Color.rgb(20, 20, 20))
            }
        }

        // Strip area [75, 75, 225, 225] is sharp and well-lit
        for (y in 75 until 225) {
            for (x in 75 until 225) {
                val isEven = ((x / 4) + (y / 4)) % 2 == 0
                bitmap.setPixel(x, y, if (isEven) Color.rgb(40, 40, 40) else Color.rgb(200, 200, 200))
            }
        }

        val stripBox = RectF(0.25f, 0.25f, 0.75f, 0.75f)

        // Level-1 passes
        val level1 = ImageQualityGate.analyzeLevel1(bitmap)
        assertTrue("Dark background must not fail Level-1 if not pitch black overall", level1.passed)

        // Level-2 passes because the blister strip itself is well-lit and sharp
        val level2 = ImageQualityGate.analyzeBlisterCrop(bitmap, stripBox)
        assertTrue("Good strip must not be rejected for a dark background", level2.passed)
    }

    @Test
    fun blister_crop_below_effective_resolution_fails() {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        // Tiny strip box: 9x9 pixels (below minBlisterResolutionPx = 24)
        val tinyBox = RectF(0.10f, 0.10f, 0.13f, 0.13f)

        val result = ImageQualityGate.analyzeBlisterCrop(bitmap, tinyBox)
        assertFalse("Blister crop below min resolution must fail", result.passed)
        assertEquals(RejectReason.BLURRY, result.reason)
    }
}
