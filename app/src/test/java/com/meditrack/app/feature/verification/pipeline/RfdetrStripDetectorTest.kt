package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
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
class RfdetrStripDetectorTest {

    @Test
    fun constants_and_classMapping_matchRoboflowTrainedContract() {
        assertEquals("rfdetr_blister.onnx", RfdetrStripDetector.MODEL_FILE_NAME)
        assertEquals(512, RfdetrStripDetector.INPUT_SIZE)
        assertEquals(300, RfdetrStripDetector.NUM_DETECTIONS)
        assertEquals(0, RfdetrStripDetector.CLASS_BACKGROUND)
        assertEquals(1, RfdetrStripDetector.CLASS_STRIP)
        assertEquals(2, RfdetrStripDetector.CLASS_CELL_EMPTY)
        assertEquals(3, RfdetrStripDetector.CLASS_CELL_FULL)
    }

    @Test
    fun computeIoU_identicalBoxes_returnsOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = RfdetrStripDetector(context)
        val box = RectF(0.2f, 0.2f, 0.4f, 0.4f)
        val iou = detector.computeIoU(box, box)
        assertEquals(1.0f, iou, 0.001f)
    }

    @Test
    fun computeIoU_disjointBoxes_returnsZero() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = RfdetrStripDetector(context)
        val a = RectF(0.1f, 0.1f, 0.2f, 0.2f)
        val b = RectF(0.5f, 0.5f, 0.6f, 0.6f)
        val iou = detector.computeIoU(a, b)
        assertEquals(0.0f, iou, 0.001f)
    }

    @Test
    fun applyNms_suppressesLowerConfidenceOverlappingCavity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = RfdetrStripDetector(context)

        val cellFull = Cell(box = RectF(0.2f, 0.2f, 0.4f, 0.4f), state = CellState.FULL, confidence = 0.95f)
        val cellEmpty = Cell(box = RectF(0.2f, 0.2f, 0.4f, 0.4f), state = CellState.EMPTY, confidence = 0.80f)
        val distinctCell = Cell(box = RectF(0.6f, 0.6f, 0.8f, 0.8f), state = CellState.EMPTY, confidence = 0.92f)

        val filtered = detector.applyNms(listOf(cellFull, cellEmpty, distinctCell), iouThreshold = 0.45f)
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.state == CellState.FULL && it.confidence == 0.95f })
        assertTrue(filtered.any { it.state == CellState.EMPTY && it.confidence == 0.92f })
    }

    @Test
    fun deriveStripBox_computesTightEnclosure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = RfdetrStripDetector(context)

        val cells = listOf(
            Cell(box = RectF(0.20f, 0.30f, 0.30f, 0.40f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.70f, 0.30f, 0.80f, 0.40f), state = CellState.FULL, confidence = 0.9f)
        )
        val stripBox = detector.deriveStripBox(cells)
        assertNotNull(stripBox)
        assertTrue(stripBox!!.left <= 0.20f)
        assertTrue(stripBox.right >= 0.80f)
    }

    @Test
    fun computeStripFullyInFrame_wellContainedStrip_returnsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = RfdetrStripDetector(context)

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
        val detector = RfdetrStripDetector(context)

        val stripBox = RectF(0.005f, 0.20f, 0.85f, 0.80f)
        val cells = listOf(
            Cell(box = RectF(0.10f, 0.30f, 0.25f, 0.45f), state = CellState.FULL, confidence = 0.9f)
        )
        assertFalse(detector.computeStripFullyInFrame(stripBox, cells))
    }

    @Test
    fun close_canBeCalledSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = RfdetrStripDetector(context)
        detector.close()
        detector.close()
    }
}
