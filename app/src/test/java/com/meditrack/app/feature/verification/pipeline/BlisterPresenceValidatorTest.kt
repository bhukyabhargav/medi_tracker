package com.meditrack.app.feature.verification.pipeline

import android.graphics.RectF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BlisterPresenceValidatorTest {

    private fun cell(conf: Float = 0.9f, box: RectF = RectF()) =
        Cell(box = box, state = CellState.FULL, confidence = conf)

    @Test fun nullOrEmpty_isNotABlister() {
        assertFalse(BlisterPresenceValidator.evaluate(null).present)
        assertFalse(BlisterPresenceValidator.evaluate(Detection(emptyList(), false)).present)
    }

    @Test fun fewStrayCells_withoutStrip_isNotABlister() {
        val d = Detection(listOf(cell(0.5f), cell(0.5f)), true, stripConfidence = null)
        assertFalse(BlisterPresenceValidator.evaluate(d).present)
    }

    @Test fun lowConfidenceStrip_isNotABlister() {
        val d = Detection(listOf(cell(), cell()), true, stripConfidence = 0.2f)
        assertFalse(BlisterPresenceValidator.evaluate(d).present)
    }

    @Test fun confidentStrip_withCells_isABlister() {
        val d = Detection(listOf(cell(), cell()), true, stripConfidence = 0.9f)
        assertTrue(BlisterPresenceValidator.evaluate(d).present)
    }

    @Test fun manyConfidentCells_withoutStrip_isABlister() {
        val d = Detection(List(6) { cell(0.85f) }, true, stripConfidence = null)
        assertTrue(BlisterPresenceValidator.evaluate(d).present)
    }

    @Test fun wildlyDifferentCellSizes_isNotABlister() {
        val cells = listOf(
            cell(box = RectF(0.0f, 0.0f, 0.05f, 0.05f)),
            cell(box = RectF(0.1f, 0.0f, 0.15f, 0.05f)),
            cell(box = RectF(0.2f, 0.0f, 0.25f, 0.05f)),
            cell(box = RectF(0.0f, 0.2f, 0.9f, 0.9f))
        )
        val d = Detection(cells, true, stripConfidence = 0.9f)
        assertFalse(BlisterPresenceValidator.evaluate(d).present)
    }
}
