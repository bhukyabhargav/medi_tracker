package com.meditrack.app.core.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterboxTransformTest {

    private val tolerance = 1e-4f

    @Test
    fun wideAspectRatio_16by9_computesCorrectPaddingAndScale() {
        val transform = LetterboxTransform(srcWidth = 1920, srcHeight = 1080, targetWidth = 320, targetHeight = 320)

        // scale = min(320/1920, 320/1080) = 320/1920 = 1/6
        assertEquals(320f / 1920f, transform.scale, tolerance)
        assertEquals(320f, transform.scaledWidth, tolerance)
        assertEquals(180f, transform.scaledHeight, tolerance)
        assertEquals(0f, transform.padX, tolerance)
        assertEquals(70f, transform.padY, tolerance) // (320 - 180) / 2 = 70
    }

    @Test
    fun tallAspectRatio_9by16_computesCorrectPaddingAndScale() {
        val transform = LetterboxTransform(srcWidth = 1080, srcHeight = 1920, targetWidth = 320, targetHeight = 320)

        assertEquals(320f / 1920f, transform.scale, tolerance)
        assertEquals(180f, transform.scaledWidth, tolerance)
        assertEquals(320f, transform.scaledHeight, tolerance)
        assertEquals(70f, transform.padX, tolerance) // (320 - 180) / 2 = 70
        assertEquals(0f, transform.padY, tolerance)
    }

    @Test
    fun squareAspectRatio_computesZeroPadding() {
        val transform = LetterboxTransform(srcWidth = 1000, srcHeight = 1000, targetWidth = 320, targetHeight = 320)

        assertEquals(0.32f, transform.scale, tolerance)
        assertEquals(320f, transform.scaledWidth, tolerance)
        assertEquals(320f, transform.scaledHeight, tolerance)
        assertEquals(0f, transform.padX, tolerance)
        assertEquals(0f, transform.padY, tolerance)
    }

    @Test
    fun wideImage_forwardAndInverseRoundtrip_preservesCoordinates() {
        val transform = LetterboxTransform(srcWidth = 1920, srcHeight = 1080, targetWidth = 320, targetHeight = 320)

        // An object in the center of the original image
        val originalBox = RectF(0.25f, 0.20f, 0.75f, 0.80f)
        val (xc, yc, w, h) = transform.toLetterboxCenterSize(originalBox)

        // Inverse transform using pixel coordinates
        val reconstructed = transform.toOriginalNormalized(xc, yc, w, h)

        assertEquals(originalBox.left, reconstructed.left, tolerance)
        assertEquals(originalBox.top, reconstructed.top, tolerance)
        assertEquals(originalBox.right, reconstructed.right, tolerance)
        assertEquals(originalBox.bottom, reconstructed.bottom, tolerance)
    }

    @Test
    fun tallImage_forwardAndInverseRoundtrip_preservesCoordinates() {
        val transform = LetterboxTransform(srcWidth = 1080, srcHeight = 1920, targetWidth = 320, targetHeight = 320)

        val originalBox = RectF(0.15f, 0.30f, 0.85f, 0.70f)
        val (xc, yc, w, h) = transform.toLetterboxCenterSize(originalBox)

        val reconstructed = transform.toOriginalNormalized(xc, yc, w, h)

        assertEquals(originalBox.left, reconstructed.left, tolerance)
        assertEquals(originalBox.top, reconstructed.top, tolerance)
        assertEquals(originalBox.right, reconstructed.right, tolerance)
        assertEquals(originalBox.bottom, reconstructed.bottom, tolerance)
    }

    @Test
    fun squareImage_forwardAndInverseRoundtrip_preservesCoordinates() {
        val transform = LetterboxTransform(srcWidth = 800, srcHeight = 800, targetWidth = 320, targetHeight = 320)

        val originalBox = RectF(0.10f, 0.10f, 0.90f, 0.90f)
        val (xc, yc, w, h) = transform.toLetterboxCenterSize(originalBox)

        val reconstructed = transform.toOriginalNormalized(xc, yc, w, h)

        assertEquals(originalBox.left, reconstructed.left, tolerance)
        assertEquals(originalBox.top, reconstructed.top, tolerance)
        assertEquals(originalBox.right, reconstructed.right, tolerance)
        assertEquals(originalBox.bottom, reconstructed.bottom, tolerance)
    }

    @Test
    fun normalizedLetterboxInputs_produceExactOriginalCoordinates() {
        val transform = LetterboxTransform(srcWidth = 1920, srcHeight = 1080, targetWidth = 320, targetHeight = 320)

        // Suppose model output is normalized (0..1)
        val originalBox = RectF(0.20f, 0.20f, 0.60f, 0.80f)
        val (xcPx, ycPx, wPx, hPx) = transform.toLetterboxCenterSize(originalBox)

        val xcNorm = xcPx / 320f
        val ycNorm = ycPx / 320f
        val wNorm = wPx / 320f
        val hNorm = hPx / 320f

        val reconstructed = transform.toOriginalNormalized(xcNorm, ycNorm, wNorm, hNorm)

        assertEquals(originalBox.left, reconstructed.left, tolerance)
        assertEquals(originalBox.top, reconstructed.top, tolerance)
        assertEquals(originalBox.right, reconstructed.right, tolerance)
        assertEquals(originalBox.bottom, reconstructed.bottom, tolerance)
    }

    @Test
    fun ssdCornerCoordinates_toOriginalNormalizedBox_invertsCorrectly() {
        val transform = LetterboxTransform(srcWidth = 1920, srcHeight = 1080, targetWidth = 320, targetHeight = 320)

        val originalBox = RectF(0.1f, 0.2f, 0.9f, 0.8f)
        val (xc, yc, w, h) = transform.toLetterboxCenterSize(originalBox)
        val xminPx = xc - w / 2f
        val yminPx = yc - h / 2f
        val xmaxPx = xc + w / 2f
        val ymaxPx = yc + h / 2f

        val reconstructed = transform.toOriginalNormalizedBox(
            ymin = yminPx,
            xmin = xminPx,
            ymax = ymaxPx,
            xmax = xmaxPx
        )

        assertEquals(originalBox.left, reconstructed.left, tolerance)
        assertEquals(originalBox.top, reconstructed.top, tolerance)
        assertEquals(originalBox.right, reconstructed.right, tolerance)
        assertEquals(originalBox.bottom, reconstructed.bottom, tolerance)
    }

    @Test
    fun boundaryCoordinates_areSafelyCoercedToZeroAndOne() {
        val transform = LetterboxTransform(srcWidth = 1920, srcHeight = 1080, targetWidth = 320, targetHeight = 320)

        // Box reaching beyond image edges in letterbox padding area
        val reconstructed = transform.toOriginalNormalized(xc = 0f, yc = 0f, w = 100f, h = 100f)

        assertTrue(reconstructed.left >= 0f)
        assertTrue(reconstructed.top >= 0f)
        assertTrue(reconstructed.right <= 1f)
        assertTrue(reconstructed.bottom <= 1f)
    }

    @Test
    fun applyLetterbox_createsBitmapWithTargetDimensionsAndPaddedColor() {
        val src = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val transform = LetterboxTransform(srcWidth = 100, srcHeight = 50, targetWidth = 320, targetHeight = 320)

        val letterboxed = transform.applyLetterbox(src, padColor = 114)

        assertEquals(320, letterboxed.width)
        assertEquals(320, letterboxed.height)

        // Pixel (0, 0) should be in the top padding area -> color should be RGB(114, 114, 114)
        val pixelColor = letterboxed.getPixel(0, 0)
        assertEquals(114, Color.red(pixelColor))
        assertEquals(114, Color.green(pixelColor))
        assertEquals(114, Color.blue(pixelColor))

        letterboxed.recycle()
        src.recycle()
    }
}
