package com.meditrack.app.core.config

/**
 * Stage 13 / 14: Verification & ML Model Configuration
 *
 * Configurable parameters and remote-config-ready hooks for on-device
 * blister strip detection, inference latency targets, confidence guards, and model auditing.
 */
object VerificationConfig {
    var modelVersion: String = "v1.0.0-tflite"
    var isTfliteEnabled: Boolean = true
    var confidenceThreshold: Float = 0.45f
    var minCellConfidence: Float = 0.45f
    var iouThreshold: Float = 0.45f
    var edgeMarginFraction: Float = 0.05f // 5% border margin for strip bounds
    var maxInferenceLatencyMs: Long = 400L // Target <= 400 ms on mid-range devices
    var maxCellCountTolerance: Int = 1 // Max allowable discrepancy in total cavities detected

    // Two-stage detection: when the strip fills less than this fraction of the photo, the cells are only a few
    // pixels wide at the model's 320x320 input and are missed or classified with low confidence. The detector
    // then re-runs on a crop around the strip.
    var stripZoomAreaThreshold: Float = 0.85f
    // Margins (fraction of the strip box on each side) for the crops that are pooled with the whole-photo pass.
    var stripCropMargins: List<Float> = listOf(0.08f, 0.02f, 0.20f)
    var stripCropMarginFraction: Float = 0.08f // extra context kept around the strip box when cropping

    // Confidence guard: a real foil strip nearly always has one or two cells at 0.45-0.60, so a single weaker
    // cell must not reject the whole photo. Reject only when MANY cells are unreliable.
    var reliableCellConfidence: Float = 0.60f
    var maxUnreliableCellFraction: Float = 0.30f

    // Blister-presence evidence (shared by enrollment and dose verification).
    var minBlisterCells: Int = 1 // with a confident strip box, a nearly finished strip may show only 1 cavity
    var minCellsWithoutStrip: Int = 4 // cell-only evidence (no strip box) needs at least this many cells
    var minConsistentCellFraction: Float = 0.8f // share of cells with a similar size
}
