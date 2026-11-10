package com.meditrack.app.feature.verification.pipeline

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoboflowWorkflowStripDetectorTest {

    private val fallbackDetector: TfliteStripDetector = mockk(relaxed = true)
    private lateinit var detector: RoboflowWorkflowStripDetector

    @Before
    fun setUp() {
        detector = RoboflowWorkflowStripDetector(fallbackDetector)
    }

    @Test
    fun parseWorkflowResponse_missingPackStatus_failsClosedAsRejected() {
        // Missing pack_status field must fail closed (not default to accepted)
        val json = """
            {
                "missing_count": 2,
                "empty_cavity_predictions": {
                    "predictions": []
                }
            }
        """.trimIndent()

        val detection = detector.parseWorkflowResponse(json)

        assertEquals("rejected", detection.roboflowPackStatus)
        assertFalse("Missing pack_status must fail closed", detection.stripFullyInFrame)
        assertEquals(0.0f, detection.stripConfidence)
        assertEquals(DetectorSource.CLOUD, detection.source)
    }

    @Test
    fun parseWorkflowResponse_acceptedPackStatus_withMissingCount() {
        val json = """
            {
                "pack_status": "accepted",
                "missing_count": 3,
                "pack_predictions": {
                    "image": { "width": 1000, "height": 1000 },
                    "predictions": [
                        { "x": 500, "y": 500, "width": 400, "height": 400 }
                    ]
                },
                "empty_cavity_predictions": {
                    "image": { "width": 1000, "height": 1000 },
                    "predictions": [
                        { "x": 400, "y": 400, "width": 50, "height": 50, "confidence": 0.95 },
                        { "x": 450, "y": 400, "width": 50, "height": 50, "confidence": 0.92 },
                        { "x": 500, "y": 400, "width": 50, "height": 50, "confidence": 0.89 }
                    ]
                }
            }
        """.trimIndent()

        val detection = detector.parseWorkflowResponse(json)

        assertEquals("accepted", detection.roboflowPackStatus)
        assertTrue(detection.stripFullyInFrame)
        assertEquals(1.0f, detection.stripConfidence)
        assertEquals(3, detection.roboflowMissingCount)
        assertEquals(3, detection.cells.size)
        assertEquals(DetectorSource.CLOUD, detection.source)
    }

    @Test
    fun parseWorkflowResponse_missingCountAbsent_fallsBackToCountedCellsWhenAccepted() {
        val json = """
            {
                "pack_status": "accepted",
                "pack_predictions": {
                    "image": { "width": 1000, "height": 1000 },
                    "predictions": [
                        { "x": 500, "y": 500, "width": 400, "height": 400 }
                    ]
                },
                "empty_cavity_predictions": {
                    "image": { "width": 1000, "height": 1000 },
                    "predictions": [
                        { "x": 400, "y": 400, "width": 50, "height": 50, "confidence": 0.95 }
                    ]
                }
            }
        """.trimIndent()

        val detection = detector.parseWorkflowResponse(json)

        assertEquals("accepted", detection.roboflowPackStatus)
        assertEquals(1, detection.roboflowMissingCount)
        assertEquals(1, detection.cells.size)
    }
}
