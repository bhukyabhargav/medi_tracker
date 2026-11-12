package com.meditrack.app.feature.verification.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfdetrPostProcessorTest {

    private val numClasses = 5

    /** One query: a box and the logits of the empty (index 3) and full (index 4) classes. */
    private data class Query(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val emptyLogit: Float, val fullLogit: Float
    )

    private fun decode(queries: List<Query>, threshold: Float = 0.45f): List<RfdetrPostProcessor.RawBox> {
        val dets = FloatArray(queries.size * 4)
        val logits = FloatArray(queries.size * numClasses) { -10f }
        queries.forEachIndexed { i, q ->
            dets[i * 4] = q.cx; dets[i * 4 + 1] = q.cy; dets[i * 4 + 2] = q.w; dets[i * 4 + 3] = q.h
            logits[i * numClasses + 3] = q.emptyLogit
            logits[i * numClasses + 4] = q.fullLogit
        }
        return RfdetrPostProcessor.decode(dets, logits, queries.size, numClasses, threshold, nmsIou = 0.5f)
    }

    @Test
    fun countsEmptyAndFullCavities_andDropsLowScores() {
        val boxes = decode(
            listOf(
                Query(0.2f, 0.2f, 0.1f, 0.1f, emptyLogit = 2f, fullLogit = -5f),   // empty, score 0.88
                Query(0.4f, 0.2f, 0.1f, 0.1f, emptyLogit = -5f, fullLogit = 2f),   // full
                Query(0.6f, 0.2f, 0.1f, 0.1f, emptyLogit = -5f, fullLogit = 1.5f), // full
                Query(0.8f, 0.8f, 0.1f, 0.1f, emptyLogit = -2f, fullLogit = -3f)   // best score 0.12: background
            )
        )
        assertEquals(1, boxes.count { it.isEmptyCavity })
        assertEquals(2, boxes.count { !it.isEmptyCavity })
    }

    @Test
    fun samePocketDetectedAsEmptyAndFull_isCountedOnce_withTheHigherScore() {
        // The model sometimes reports one pocket twice: weakly "empty" and more strongly "full".
        val boxes = decode(
            listOf(
                Query(0.3f, 0.3f, 0.2f, 0.2f, emptyLogit = 0.0f, fullLogit = -5f),    // empty at 0.50
                Query(0.31f, 0.3f, 0.2f, 0.2f, emptyLogit = -5f, fullLogit = 0.45f)   // full at 0.61, same pocket
            )
        )
        assertEquals(1, boxes.size)
        assertEquals(RfdetrPostProcessor.CLASS_FULL_CAVITY, boxes[0].classIndex)
    }

    @Test
    fun boxesAreConvertedToCornersAndClampedToTheImage() {
        val box = decode(listOf(Query(0.05f, 0.5f, 0.2f, 0.2f, emptyLogit = 3f, fullLogit = -5f))).single()
        assertEquals(0f, box.left, 1e-6f)
        assertEquals(0.15f, box.right, 1e-6f)
        assertEquals(0.4f, box.top, 1e-6f)
        assertEquals(0.6f, box.bottom, 1e-6f)
    }

    @Test
    fun modelWithoutCavityClasses_givesNoBoxes() {
        val result = RfdetrPostProcessor.decode(FloatArray(4), FloatArray(3), 1, 3, 0.45f, 0.5f)
        assertTrue(result.isEmpty())
    }
}
