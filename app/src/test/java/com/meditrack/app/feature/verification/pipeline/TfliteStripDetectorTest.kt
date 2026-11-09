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
    fun concurrentDetect_serializedSafelyByMutex() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteStripDetector(context)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        // Launch concurrent calls to detect; all should execute without crashing or corrupting state
        val deferred = (1..5).map {
            async(Dispatchers.Default) {
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        detector.detect(bitmap)
                    }
                }
            }
        }
        deferred.awaitAll()
    }
}
