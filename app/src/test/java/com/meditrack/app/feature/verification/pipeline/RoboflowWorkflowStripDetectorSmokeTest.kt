package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.core.config.VerificationConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RoboflowWorkflowStripDetectorSmokeTest {

    private lateinit var context: Context
    private lateinit var fallbackDetector: TfliteStripDetector
    private lateinit var detector: RoboflowWorkflowStripDetector

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fallbackDetector = mockk(relaxed = true)
        detector = RoboflowWorkflowStripDetector(context, fallbackDetector)
        VerificationConfig.isRoboflowWorkflowEnabled = true
    }

    @Test
    fun parseWorkflowResponse_validAndroidBlisterWorkflow_assertsExpectedKeysAndOutputs() {
        // Representative response format from "Android Blister Presence and Empty Count" workflow
        val sampleResponse = """
        {
          "outputs": [
            {
              "pack_status": "accepted",
              "missing_count": 2,
              "pack_predictions": {
                "image": { "width": 640, "height": 480 },
                "predictions": [
                  {
                    "x": 320.0,
                    "y": 240.0,
                    "width": 300.0,
                    "height": 200.0,
                    "confidence": 0.95,
                    "class": "blister_pack"
                  }
                ]
              },
              "empty_cavity_predictions": {
                "image": { "width": 640, "height": 480 },
                "predictions": [
                  {
                    "x": 260.0,
                    "y": 200.0,
                    "width": 40.0,
                    "height": 40.0,
                    "confidence": 0.88,
                    "class": "empty_cavity"
                  },
                  {
                    "x": 380.0,
                    "y": 200.0,
                    "width": 40.0,
                    "height": 40.0,
                    "confidence": 0.91,
                    "class": "empty_cavity"
                  }
                ]
              },
              "visualization": "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA="
            }
          ]
        }
        """.trimIndent()

        val detection = detector.parseWorkflowResponse(sampleResponse)

        // Assert expected outputs
        assertTrue("Detection must be flagged as Roboflow workflow", detection.isRoboflowWorkflow)
        assertEquals("accepted", detection.roboflowPackStatus)
        assertEquals(2, detection.roboflowMissingCount)
        assertEquals(2, detection.cells.size)
        assertEquals(CellState.EMPTY, detection.cells[0].state)
        assertEquals(CellState.EMPTY, detection.cells[1].state)
        assertNotNull("Strip bounding box must be extracted", detection.stripBox)
        assertTrue("Strip fully in frame check should pass when centered", detection.stripFullyInFrame)

        // Assert heavy visualization blob was written to disk instead of kept in memory
        val cacheFiles = context.cacheDir.listFiles { _, name -> name.startsWith("roboflow_") }
        assertTrue("Image output should be cached to disk", cacheFiles != null && cacheFiles.isNotEmpty())
    }

    @Test
    fun parseWorkflowResponse_rejectedPack_setsStripConfidenceZero() {
        val sampleResponse = """
        {
          "outputs": [
            {
              "pack_status": "rejected",
              "missing_count": 0,
              "empty_cavity_predictions": {
                "image": { "width": 640, "height": 480 },
                "predictions": []
              }
            }
          ]
        }
        """.trimIndent()

        val detection = detector.parseWorkflowResponse(sampleResponse)

        assertFalse("Pack rejected must report stripFullyInFrame false", detection.stripFullyInFrame)
        assertEquals(0.0f, detection.stripConfidence ?: -1f, 0.01f)
        assertEquals("rejected", detection.roboflowPackStatus)
        assertEquals(0, detection.roboflowMissingCount)
        assertTrue(detection.cells.isEmpty())
    }

    @Test
    fun detect_whenOfflineOrApiFails_fallsBackToTfliteDetector() = runBlocking {
        // Point to invalid port to trigger fallback
        VerificationConfig.roboflowWorkflowEndpoint = "http://localhost:59999/invalid/endpoint"

        val fallbackResult = Detection(
            cells = emptyList(),
            stripFullyInFrame = true,
            stripConfidence = 0.85f,
            isRoboflowWorkflow = false
        )
        coEvery { fallbackDetector.detect(any()) } returns fallbackResult

        val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = detector.detect(testBitmap)

        assertEquals("Must gracefully fall back to local detector", fallbackResult, result)
        assertFalse(result.isRoboflowWorkflow)
    }
}
