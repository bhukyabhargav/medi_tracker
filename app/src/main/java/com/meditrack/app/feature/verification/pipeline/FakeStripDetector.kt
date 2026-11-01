package com.meditrack.app.feature.verification.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import javax.inject.Inject
import javax.inject.Singleton

enum class ForceDetectorBehavior {
    DEFAULT,
    FORCE_PASS,
    FORCE_PILLS_NOT_REMOVED,
    FORCE_WRONG_COUNT,
    FORCE_STRIP_NOT_FULLY_VISIBLE,
    FORCE_LOW_CONFIDENCE
}

@Singleton
class FakeStripDetector @Inject constructor() : StripDetector {

    var behavior: ForceDetectorBehavior = ForceDetectorBehavior.DEFAULT
    var mockEmptyCellsCount: Int = 1
    var mockPreviousEmptyCellsCount: Int = 0
    var mockTotalCellsCount: Int = 10
    var mockStripFullyInFrame: Boolean = true

    override suspend fun detect(bitmap: Bitmap): Detection {
        val stripInFrame = when (behavior) {
            ForceDetectorBehavior.FORCE_STRIP_NOT_FULLY_VISIBLE -> false
            else -> mockStripFullyInFrame
        }

        val confidence = when (behavior) {
            ForceDetectorBehavior.FORCE_LOW_CONFIDENCE -> 0.35f // < DETECTOR_MIN_CONFIDENCE (0.5f)
            else -> 0.96f
        }

        val emptyCount = when (behavior) {
            ForceDetectorBehavior.FORCE_PILLS_NOT_REMOVED -> mockPreviousEmptyCellsCount
            ForceDetectorBehavior.FORCE_WRONG_COUNT -> mockEmptyCellsCount + 2
            ForceDetectorBehavior.FORCE_PASS -> mockEmptyCellsCount
            else -> mockEmptyCellsCount
        }

        val cells = mutableListOf<Cell>()
        val total = maxOf(mockTotalCellsCount, emptyCount)

        for (i in 0 until total) {
            val state = if (i < emptyCount) CellState.EMPTY else CellState.FULL
            val cellBox = RectF(
                (i * 40f),
                10f,
                (i * 40f + 35f),
                60f
            )
            cells.add(Cell(box = cellBox, state = state, confidence = confidence))
        }

        return Detection(
            cells = cells,
            stripFullyInFrame = stripInFrame
        )
    }

    companion object {
        // Global debug toggle accessible from DebugToolsScreen
        var debugForcedBehavior: ForceDetectorBehavior = ForceDetectorBehavior.DEFAULT
    }
}
