package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PillBlisterClassifierDetectorTest {

    @Test
    fun classifyPillColor_mostlySaturatedPixels_returnsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PillBlisterClassifierDetector(context)

        // Create vivid colored pixels (e.g., bright orange / red)
        val pixels = IntArray(100) { Color.rgb(255, 50, 0) }
        assertTrue(detector.classifyPillColor(pixels))
    }

    @Test
    fun classifyPillColor_mostlyAchromaticPixels_returnsFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PillBlisterClassifierDetector(context)

        // Achromatic white / gray foil pixels (low saturation)
        val pixels = IntArray(100) { Color.rgb(200, 200, 200) }
        assertFalse(detector.classifyPillColor(pixels))
    }

    @Test
    fun correctWhiteBalance_achromaticFoilDetected_adjustsGains() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PillBlisterClassifierDetector(context)

        // Simulate 20x20 image with realistic foil and slight warm lighting (R=195, G=175, B=165)
        // Saturation = (30 * 255) / 195 = 39 (<= 60, foil detected)
        val pixels = IntArray(400) { Color.rgb(195, 175, 165) }
        val balanced = detector.correctWhiteBalance(pixels, 20, 20)

        assertNotNull(balanced)
        assertEquals(400, balanced.size)
        // Red channel should be scaled down towards target 180
        val sampleR = Color.red(balanced[0])
        assertTrue("Expected R to be adjusted towards target 180, got $sampleR", sampleR in 170..185)
    }

    @Test
    fun detect_syntheticBlisterGrid_detectsPillsAndEmptyCells() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PillBlisterClassifierDetector(context)

        // Create synthetic 300x150 bitmap directly in pixel memory
        // Background: foil gray (140, 140, 140)
        // Pills: white circles (255, 255, 255)
        val w = 300
        val h = 150
        val pixels = IntArray(w * h) { Color.rgb(140, 140, 140) }

        val radius = 16
        val pillCenters = listOf(
            Pair(60, 45), Pair(150, 45), Pair(240, 45),
            Pair(60, 105), Pair(150, 105)
            // Note: position (240, 105) is deliberately empty (foil)
        )

        for ((cx, cy) in pillCenters) {
            for (y in (cy - radius)..(cy + radius)) {
                for (x in (cx - radius)..(cx + radius)) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= radius * radius && x in 0 until w && y in 0 until h) {
                        pixels[y * w + x] = Color.rgb(255, 255, 255)
                    }
                }
            }
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        val detection = detector.detect(bitmap)
        assertNotNull(detection)
        assertTrue("Detection cells should not be empty", detection.cells.isNotEmpty())

        val fullCount = detection.cells.count { it.state == CellState.FULL }
        assertTrue("Expected full pills to be detected, got $fullCount", fullCount >= 3)
    }

    @Test
    fun deriveStripBox_enclosesAllCells() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PillBlisterClassifierDetector(context)

        val cells = listOf(
            Cell(box = RectF(0.20f, 0.30f, 0.30f, 0.40f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.70f, 0.30f, 0.80f, 0.40f), state = CellState.EMPTY, confidence = 0.9f)
        )
        val stripBox = detector.deriveStripBox(cells)
        assertNotNull(stripBox)
        assertTrue(stripBox!!.left <= 0.20f)
        assertTrue(stripBox.right >= 0.80f)
    }

    @Test
    fun computeStripFullyInFrame_wellContainedStrip_returnsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PillBlisterClassifierDetector(context)

        val stripBox = RectF(0.15f, 0.20f, 0.85f, 0.80f)
        val cells = listOf(
            Cell(box = RectF(0.20f, 0.30f, 0.35f, 0.45f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.65f, 0.30f, 0.80f, 0.45f), state = CellState.EMPTY, confidence = 0.85f)
        )
        assertTrue(detector.computeStripFullyInFrame(stripBox, cells))
    }

    @Test
    fun computeStripFullyInFrame_touchingLeftBorder_returnsFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PillBlisterClassifierDetector(context)

        val stripBox = RectF(0.005f, 0.20f, 0.85f, 0.80f)
        val cells = listOf(
            Cell(box = RectF(0.10f, 0.30f, 0.25f, 0.45f), state = CellState.FULL, confidence = 0.9f)
        )
        assertFalse(detector.computeStripFullyInFrame(stripBox, cells))
    }

    @Test
    fun close_canBeCalledSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PillBlisterClassifierDetector(context)
        detector.close()
        detector.close()
    }
}
