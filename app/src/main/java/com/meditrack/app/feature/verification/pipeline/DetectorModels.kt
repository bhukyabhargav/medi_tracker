package com.meditrack.app.feature.verification.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import com.meditrack.app.domain.model.RejectReason

enum class CellState {
    FULL,
    EMPTY,
    PARTIAL
}

enum class DetectorSource {
    CLOUD,
    LOCAL
}

data class Cell(
    val box: RectF = RectF(),
    val state: CellState,
    val confidence: Float
)

data class Detection(
    val cells: List<Cell>,
    val stripFullyInFrame: Boolean,
    val stripConfidence: Float? = null,
    /** Strip box in normalized coordinates of the ORIGINAL image (null when no strip could be located). */
    val stripBox: RectF? = null,
    val isRoboflowWorkflow: Boolean = false,
    val roboflowMissingCount: Int? = null,
    val roboflowPackStatus: String? = null,
    val source: DetectorSource = DetectorSource.LOCAL
)

interface StripDetector {
    suspend fun detect(bitmap: Bitmap): Detection
}

data class VerificationResult(
    val passed: Boolean,
    val rejectReason: RejectReason? = null,
    val failureReason: VerificationFailureReason? = null,
    val emptyCellsAfter: Int? = null,
    val friendlyMessage: String? = null,
    val retryHint: String? = null,
    val detectorSource: DetectorSource? = null
)
