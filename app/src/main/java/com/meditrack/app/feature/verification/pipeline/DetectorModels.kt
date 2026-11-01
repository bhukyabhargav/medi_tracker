package com.meditrack.app.feature.verification.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import com.meditrack.app.domain.model.RejectReason

enum class CellState {
    FULL,
    EMPTY,
    PARTIAL
}

data class Cell(
    val box: RectF = RectF(),
    val state: CellState,
    val confidence: Float
)

data class Detection(
    val cells: List<Cell>,
    val stripFullyInFrame: Boolean
)

interface StripDetector {
    suspend fun detect(bitmap: Bitmap): Detection
}

data class VerificationResult(
    val passed: Boolean,
    val rejectReason: RejectReason? = null,
    val emptyCellsAfter: Int? = null,
    val friendlyMessage: String? = null
)
