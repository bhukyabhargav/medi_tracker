package com.meditrack.app.feature.verification.pipeline

import android.graphics.RectF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlisterPresenceValidatorTest {

    private fun gridCells(rows: Int, cols: Int, conf: Float = 0.85f, skip: Set<Int> = emptySet()): List<Cell> {
        val out = mutableListOf<Cell>()
        var idx = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            if (idx++ in skip) continue
            val l = 0.10f + c * 0.15f
            val t = 0.25f + r * 0.20f
            out.add(Cell(box = RectF(l, t, l + 0.10f, t + 0.13f), state = CellState.FULL, confidence = conf))
        }
        return out
    }

    private fun det(cells: List<Cell>, strip: Float? = 0.9f, box: RectF? = null) =
        Detection(cells = cells, stripFullyInFrame = true, stripConfidence = strip, stripBox = box)

    @Test fun nullOrEmpty_isNotABlister() {
        assertFalse(BlisterPresenceValidator.evaluate(null).present)
        assertFalse(BlisterPresenceValidator.evaluate(det(emptyList())).present)
    }

    @Test fun regularTwoByFiveGrid_isABlister() {
        assertTrue(BlisterPresenceValidator.evaluate(det(gridCells(2, 5))).present)
    }

    @Test fun singleRowOfFour_isABlister() {
        assertTrue(BlisterPresenceValidator.evaluate(det(gridCells(1, 4))).present)
    }

    @Test fun gridWithMissingCells_isStillABlister() {
        assertTrue(BlisterPresenceValidator.evaluate(det(gridCells(2, 6, skip = setOf(2, 7)))).present)
    }

    @Test fun tooFewCells_isNotABlister() {
        assertFalse(BlisterPresenceValidator.evaluate(det(gridCells(1, 3))).present)
    }

    @Test fun lowConfidenceStrip_isNotABlister() {
        assertFalse(BlisterPresenceValidator.evaluate(det(gridCells(2, 5), strip = 0.2f)).present)
    }

    @Test fun lowAverageCellConfidence_isNotABlister() {
        assertFalse(BlisterPresenceValidator.evaluate(det(gridCells(2, 5, conf = 0.46f))).present)
    }

    @Test fun cellsWithZeroGeometry_areNotABlister() {
        val cells = List(8) { Cell(state = CellState.FULL, confidence = 0.9f) }
        assertFalse(BlisterPresenceValidator.evaluate(det(cells)).present)
    }

    @Test fun irregularScatter_isNotABlister() {
        val boxes = listOf(
            RectF(0.10f, 0.10f, 0.20f, 0.23f), RectF(0.53f, 0.31f, 0.63f, 0.44f), RectF(0.28f, 0.71f, 0.38f, 0.84f),
            RectF(0.81f, 0.52f, 0.91f, 0.65f), RectF(0.42f, 0.05f, 0.52f, 0.18f), RectF(0.70f, 0.85f, 0.80f, 0.98f)
        )
        val cells = boxes.map { Cell(box = it, state = CellState.FULL, confidence = 0.8f) }
        assertFalse(BlisterPresenceValidator.evaluate(det(cells)).present)
    }

    @Test fun wildlyDifferentCellSizes_isNotABlister() {
        val cells = gridCells(1, 4).toMutableList()
        cells[0] = cells[0].copy(box = RectF(0.0f, 0.0f, 0.9f, 0.9f))
        cells[1] = cells[1].copy(box = RectF(0.10f, 0.0f, 0.12f, 0.02f))
        assertFalse(BlisterPresenceValidator.evaluate(det(cells)).present)
    }

    @Test fun hugeStripBoxWithSmallCells_isNotABlister() {
        // The model's "strip" covers the whole photo while the cells cover only a sliver of it.
        val cells = gridCells(2, 5).map { c ->
            c.copy(box = RectF(c.box.left, c.box.top, c.box.left + 0.03f, c.box.top + 0.04f))
        }
        val d = det(cells, box = RectF(0f, 0f, 1f, 1f))
        assertFalse(BlisterPresenceValidator.evaluate(d).present)
    }

    @Test fun rotatedStrip_isStillABlister() {
        // Diagonal arrangement (45 degrees): rows/columns are still evenly spaced.
        val cells = (0 until 6).map { i ->
            val l = 0.10f + i * 0.12f
            val t = 0.10f + i * 0.12f
            Cell(box = RectF(l, t, l + 0.10f, t + 0.10f), state = CellState.FULL, confidence = 0.85f)
        }
        assertTrue(BlisterPresenceValidator.evaluate(det(cells)).present)
    }
}
