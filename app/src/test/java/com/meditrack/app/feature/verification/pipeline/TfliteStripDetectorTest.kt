package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.core.config.VerificationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tensorflow.lite.Interpreter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TfliteStripDetectorTest {

    @Test
    fun detect_withoutModelAsset_throwsIllegalStateException() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                detector.detect(bitmap)
            }
        }

        assertTrue(
            "Exception message must mention missing model file: ${exception.message}",
            exception.message?.contains("not installed") == true
        )
    }

    @Test
    fun verificationConfig_containsAuditableModelVersionAndLatencyTargets() {
        assertNotNull(VerificationConfig.modelVersion)
        assertTrue(VerificationConfig.modelVersion.startsWith("v1"))
        assertEquals(400L, VerificationConfig.maxInferenceLatencyMs)
        assertEquals(0.05f, VerificationConfig.edgeMarginFraction, 0.001f)
    }

    @Test
    fun benchmarkLatency_returnsZeroWhenModelNotLoaded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        val latency = detector.benchmarkLatency(bitmap, iterations = 2)
        assertEquals(0L, latency)
    }

    @Test
    fun validateModelContract_validYoloShape_passesContract() {
        val mockInterpreter = mockk<Interpreter>()
        every { mockInterpreter.inputTensorCount } returns 1
        every { mockInterpreter.getInputTensor(0).shape() } returns intArrayOf(1, 320, 320, 3)
        every { mockInterpreter.outputTensorCount } returns 1
        every { mockInterpreter.getOutputTensor(0).shape() } returns intArrayOf(1, 8, 2100)

        val error = TfliteStripDetector.validateModelContract(mockInterpreter)
        assertNull("Contract validation must pass for YOLOv8 [1, 8, 2100]", error)
    }

    @Test
    fun validateModelContract_invalidInputRank_failsContract() {
        val mockInterpreter = mockk<Interpreter>()
        every { mockInterpreter.inputTensorCount } returns 1
        every { mockInterpreter.getInputTensor(0).shape() } returns intArrayOf(320, 320, 3) // rank 3 instead of 4
        every { mockInterpreter.outputTensorCount } returns 1
        every { mockInterpreter.getOutputTensor(0).shape() } returns intArrayOf(1, 8, 2100)

        val error = TfliteStripDetector.validateModelContract(mockInterpreter)
        assertNotNull(error)
        assertTrue("Error must mention input rank", error!!.contains("rank 4"))
    }

    @Test
    fun validateModelContract_insufficientChannels_failsContract() {
        val mockInterpreter = mockk<Interpreter>()
        every { mockInterpreter.inputTensorCount } returns 1
        every { mockInterpreter.getInputTensor(0).shape() } returns intArrayOf(1, 320, 320, 3)
        every { mockInterpreter.outputTensorCount } returns 1
        every { mockInterpreter.getOutputTensor(0).shape() } returns intArrayOf(1, 3, 2100) // only 3 channels (< 5)

        val error = TfliteStripDetector.validateModelContract(mockInterpreter)
        assertNotNull(error)
        assertTrue("Error must mention channels requirement", error!!.contains("channels must be at least 5"))
    }

    @Test
    fun validateModelContract_zeroAnchors_failsContract() {
        val mockInterpreter = mockk<Interpreter>()
        every { mockInterpreter.inputTensorCount } returns 1
        every { mockInterpreter.getInputTensor(0).shape() } returns intArrayOf(1, 320, 320, 3)
        every { mockInterpreter.outputTensorCount } returns 1
        every { mockInterpreter.getOutputTensor(0).shape() } returns intArrayOf(1, 8, 0)

        val error = TfliteStripDetector.validateModelContract(mockInterpreter)
        assertNotNull(error)
        assertTrue("Error must mention anchors requirement", error!!.contains("anchors must be > 0"))
    }

    @Test
    fun applyNms_classAgnostic_suppressesOverlappingCellsAcrossDifferentClasses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        // Same physical cavity predicted as both FULL (0.90) and EMPTY (0.85)
        val cellFull = Cell(box = RectF(0.2f, 0.2f, 0.4f, 0.4f), state = CellState.FULL, confidence = 0.90f)
        val cellEmpty = Cell(box = RectF(0.2f, 0.2f, 0.4f, 0.4f), state = CellState.EMPTY, confidence = 0.85f)
        // Distinct non-overlapping cavity
        val cellOther = Cell(box = RectF(0.6f, 0.6f, 0.8f, 0.8f), state = CellState.FULL, confidence = 0.92f)

        val filtered = detector.applyNms(listOf(cellFull, cellEmpty, cellOther), iouThreshold = 0.45f)

        assertEquals("Only 2 unique cavities must survive class-agnostic NMS", 2, filtered.size)
        assertTrue("Highest confidence FULL cell must be retained", filtered.any { it.state == CellState.FULL && it.confidence == 0.90f })
        assertTrue("Non-overlapping FULL cell must be retained", filtered.any { it.state == CellState.FULL && it.confidence == 0.92f })
        assertFalse("Overlapping lower-confidence EMPTY cell must be suppressed", filtered.any { it.state == CellState.EMPTY })
    }

    @Test
    fun detect_picksHighestConfidenceStripCandidateAcrossAnchors() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockInterpreter = mockk<Interpreter>(relaxed = true)

        every { mockInterpreter.inputTensorCount } returns 1
        every { mockInterpreter.getInputTensor(0).shape() } returns intArrayOf(1, 320, 320, 3)
        every { mockInterpreter.getInputTensor(0).dataType() } returns org.tensorflow.lite.DataType.FLOAT32
        every { mockInterpreter.outputTensorCount } returns 1
        every { mockInterpreter.getOutputTensor(0).shape() } returns intArrayOf(1, 8, 2)

        every { mockInterpreter.run(any(), any()) } answers {
            val outArray = secondArg<Array<Array<FloatArray>>>()
            // Anchor 0: Strip with high confidence 0.95
            outArray[0][0][0] = 160f // xc
            outArray[0][1][0] = 160f // yc
            outArray[0][2][0] = 100f // w
            outArray[0][3][0] = 50f  // h
            outArray[0][4][0] = 0.95f // CLASS_STRIP (highest confidence)
            outArray[0][5][0] = 0.01f
            outArray[0][6][0] = 0.01f
            outArray[0][7][0] = 0.01f

            // Anchor 1: Strip with lower confidence 0.55 (comes AFTER anchor 0)
            outArray[0][0][1] = 160f // xc
            outArray[0][1][1] = 160f // yc
            outArray[0][2][1] = 80f  // w
            outArray[0][3][1] = 40f  // h
            outArray[0][4][1] = 0.55f // CLASS_STRIP (lower confidence!)
            outArray[0][5][1] = 0.01f
            outArray[0][6][1] = 0.01f
            outArray[0][7][1] = 0.01f
        }

        val detector = TfliteStripDetector(context, mockInterpreter)
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val detection = detector.detect(bitmap)

        assertEquals("Must select anchor 0 with highest confidence (0.95), not last anchor (0.55)", 0.95f, detection.stripConfidence)
    }

    @Test
    fun close_canBeCalledSafelyAndCleansUpResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        // Multiple calls to close should succeed idempotently
        detector.close()
        detector.close()
    }

    @Test
    fun filterOutlierCells_removesDistantFalsePositiveDetections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        // Tightly packed 3x2 blister pack cavities in the center (0.45 .. 0.55)
        val clusterCells = listOf(
            Cell(box = RectF(0.40f, 0.40f, 0.46f, 0.46f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.48f, 0.40f, 0.54f, 0.46f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.56f, 0.40f, 0.62f, 0.46f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.40f, 0.50f, 0.46f, 0.56f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.48f, 0.50f, 0.54f, 0.56f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.56f, 0.50f, 0.62f, 0.56f), state = CellState.FULL, confidence = 0.9f)
        )
        // False positive detection on tablecloth floral circle near the top-left border
        val outlierOnCloth = Cell(box = RectF(0.01f, 0.01f, 0.04f, 0.04f), state = CellState.FULL, confidence = 0.85f)

        val filtered = detector.filterOutlierCells(clusterCells + outlierOnCloth)

        assertEquals("Outlier at image border must be dropped", 6, filtered.size)
        assertFalse("Outlier on tablecloth pattern must not be present", filtered.contains(outlierOnCloth))
        assertTrue("Cluster cells in center must be retained", filtered.containsAll(clusterCells))
    }

    @Test
    fun deriveStripBox_ignoresOutliersAndComputesTightBoundingBox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        val clusterCells = listOf(
            Cell(box = RectF(0.30f, 0.40f, 0.40f, 0.50f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.45f, 0.40f, 0.55f, 0.50f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.60f, 0.40f, 0.70f, 0.50f), state = CellState.FULL, confidence = 0.9f)
        )
        val outlierCell = Cell(box = RectF(0.01f, 0.01f, 0.03f, 0.03f), state = CellState.FULL, confidence = 0.8f)

        val derivedBox = detector.deriveStripBox(clusterCells + outlierCell)

        assertNotNull(derivedBox)
        assertEquals("Strip left should be bounded by cluster, not outlier", 0.30f, derivedBox!!.left, 0.001f)
        assertEquals("Strip right should be bounded by cluster", 0.70f, derivedBox.right, 0.001f)
        assertTrue("Derived strip box must be well within frame margin (> 0.05)", derivedBox.left >= 0.05f)
    }

    @Test
    fun detect_whenStripCandidateDoesNotEncloseCells_fallsBackToClusterDerivation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockInterpreter = mockk<Interpreter>(relaxed = true)

        every { mockInterpreter.inputTensorCount } returns 1
        every { mockInterpreter.getInputTensor(0).shape() } returns intArrayOf(1, 320, 320, 3)
        every { mockInterpreter.getInputTensor(0).dataType() } returns org.tensorflow.lite.DataType.FLOAT32
        every { mockInterpreter.outputTensorCount } returns 1
        every { mockInterpreter.getOutputTensor(0).shape() } returns intArrayOf(1, 8, 4)

        every { mockInterpreter.run(any(), any()) } answers {
            val outArray = secondArg<Array<Array<FloatArray>>>()
            // Anchor 0: CLASS_STRIP false detection on table edge at the border (x=5, y=5, w=8, h=8)
            outArray[0][0][0] = 5f
            outArray[0][1][0] = 5f
            outArray[0][2][0] = 8f
            outArray[0][3][0] = 8f
            outArray[0][4][0] = 0.92f // CLASS_STRIP

            // Anchors 1, 2, 3: Centered blister cells around center (160, 160)
            for (j in 1..3) {
                outArray[0][0][j] = 120f + (j * 30f)
                outArray[0][1][j] = 160f
                outArray[0][2][j] = 20f
                outArray[0][3][j] = 20f
                outArray[0][4][j] = 0.01f
                outArray[0][5][j] = 0.90f // CLASS_CELL_FULL
            }
        }

        val detector = TfliteStripDetector(context, mockInterpreter)
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val detection = detector.detect(bitmap)

        assertEquals("Cells must be detected", 3, detection.cells.size)
        assertTrue(
            "Strip must be considered fully in frame because table-edge strip was discarded for cell cluster",
            detection.stripFullyInFrame
        )
    }

    @Test
    fun computeStripFullyInFrame_wellCenteredStrip_returnsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        val stripBox = RectF(0.20f, 0.30f, 0.80f, 0.60f)
        val cells = listOf(
            Cell(box = RectF(0.25f, 0.35f, 0.35f, 0.45f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.40f, 0.35f, 0.50f, 0.45f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.55f, 0.35f, 0.65f, 0.45f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.70f, 0.35f, 0.78f, 0.45f), state = CellState.FULL, confidence = 0.9f)
        )

        assertTrue(detector.computeStripFullyInFrame(stripBox, cells))
    }

    @Test
    fun computeStripFullyInFrame_smallStrip20Percent_returnsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        // Small strip covering ~20% of image width
        val stripBox = RectF(0.40f, 0.40f, 0.60f, 0.55f)
        val cells = listOf(
            Cell(box = RectF(0.42f, 0.42f, 0.48f, 0.52f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.52f, 0.42f, 0.58f, 0.52f), state = CellState.FULL, confidence = 0.9f)
        )

        assertTrue("Small strip (20% of photo) well inside bounds must pass", detector.computeStripFullyInFrame(stripBox, cells))
    }

    @Test
    fun computeStripFullyInFrame_offCenterStrip_returnsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        // Off-centre strip in bottom-right quadrant, not touching border
        val stripBox = RectF(0.55f, 0.60f, 0.92f, 0.85f)
        val cells = listOf(
            Cell(box = RectF(0.58f, 0.65f, 0.68f, 0.80f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.72f, 0.65f, 0.82f, 0.80f), state = CellState.FULL, confidence = 0.9f)
        )

        assertTrue("Off-centre strip not touching the border must pass", detector.computeStripFullyInFrame(stripBox, cells))
    }

    @Test
    fun computeStripFullyInFrame_rotatedStrip_returnsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        // Diagonally arranged cells within frame
        val cells = listOf(
            Cell(box = RectF(0.20f, 0.20f, 0.28f, 0.28f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.35f, 0.35f, 0.43f, 0.43f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.50f, 0.50f, 0.58f, 0.58f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.65f, 0.65f, 0.73f, 0.73f), state = CellState.FULL, confidence = 0.9f)
        )
        val stripBox = RectF(0.18f, 0.18f, 0.75f, 0.75f)

        assertTrue("Rotated strip inside bounds must pass", detector.computeStripFullyInFrame(stripBox, cells))
    }

    @Test
    fun computeStripFullyInFrame_stripTouchingBorderLeft_returnsFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        // Strip touches left border (left <= tolerance)
        val stripBox = RectF(0.005f, 0.30f, 0.60f, 0.60f)
        val cells = listOf(
            Cell(box = RectF(0.05f, 0.35f, 0.15f, 0.45f), state = CellState.FULL, confidence = 0.9f),
            Cell(box = RectF(0.20f, 0.35f, 0.30f, 0.45f), state = CellState.FULL, confidence = 0.9f)
        )

        assertFalse("Strip touching left border must fail in-frame check", detector.computeStripFullyInFrame(stripBox, cells))
    }

    @Test
    fun computeStripFullyInFrame_cellCutOffAtBorderTop_returnsFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        // Strip box might be inside, but a cell touches/crosses top border (top <= cellCutoffTolerance)
        val stripBox = RectF(0.20f, 0.05f, 0.80f, 0.60f)
        val cutOffCell = Cell(box = RectF(0.25f, 0.002f, 0.35f, 0.10f), state = CellState.FULL, confidence = 0.9f)
        val insideCell = Cell(box = RectF(0.40f, 0.20f, 0.50f, 0.30f), state = CellState.FULL, confidence = 0.9f)

        assertFalse("Cell cut off at top border must fail in-frame check", detector.computeStripFullyInFrame(stripBox, listOf(cutOffCell, insideCell)))
    }

    @Test
    fun computeStripFullyInFrame_emptyCellsAndNullBox_returnsFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)

        assertFalse(
            "Null strip box and empty cells must not be fully in frame",
            detector.computeStripFullyInFrame(null, emptyList())
        )
    }
}
