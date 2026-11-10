package com.meditrack.app.core.config

/**
 * Stage 13 / 14: Verification & ML Model Configuration
 *
 * Configurable parameters and remote-config-ready hooks for on-device
 * blister strip detection, inference latency targets, confidence guards, and model auditing.
 */
object VerificationConfig {
    var modelVersion: String = "v1.3.0-roboflow-android-blister-presence-empty-count"
    var isTfliteEnabled: Boolean = true
    var isRoboflowWorkflowEnabled: Boolean = true
    var roboflowWorkspaceSlug: String = "varshiths-workspace-y38an"
    var roboflowWorkflowId: String = "android-blister-presence-and-empty-count-1794338462016"
    var roboflowWorkflowName: String = "Android Blister Presence and Empty Count"
    var roboflowWorkflowEndpoint: String = "https://serverless.roboflow.com/varshiths-workspace-y38an/workflows/android-blister-presence-and-empty-count-1794338462016"
    var roboflowApiKey: String = System.getenv("ROBOFLOW_API_KEY") ?: "jZmdnU1kp8FytFibdLat"
    var confidenceThreshold: Float = 0.45f
    var minCellConfidence: Float = 0.45f
    var iouThreshold: Float = 0.45f
    var edgeMarginFraction: Float = 0.05f // 5% border margin for strip bounds (legacy target)
    var borderToleranceFraction: Float = 0.015f // 1.5% margin within which strip/cell bounds touch the border
    var cellCutoffToleranceFraction: Float = 0.012f // 1.2% margin within which a cell is cut off by the frame edge
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
    // The bundled model was trained without "no blister" photos, so it reports a strip/cells with 0.5-0.9 confidence
    // on ordinary photos (people, animals, coins, plain walls). Confidence alone therefore cannot prove a blister;
    // the layout of the detected cells has to look like a blister (a regular grid of equal-sized cavities
    // that fill a good part of the strip box).
    var minBlisterCells: Int = 4 // a physical blister strip has at least this many cavities
    var maxCellClusterDistance: Float = 2.35f // max normalized distance between adjacent cavities in blister cluster
    var minConsistentCellFraction: Float = 0.75f // share of cells whose width AND height are close to the median
    var cellSizeToleranceLow: Float = 0.60f
    var cellSizeToleranceHigh: Float = 1.65f
    var minMeanCellConfidence: Float = 0.55f
    var minAlignedCellFraction: Float = 0.8f // share of cells lying on a regular row or column
    var minStripCoverage: Float = 0.10f // total cell area / strip box area (real strips: ~0.3-0.45; rotated/single-row: ~0.12)

    // Level-1 Global image quality checks (light check: minimum resolution, not near-black/near-white)
    var globalMinDimensionPx: Int = 100 // minimum width and height of original photo
    var globalBrightnessMin: Double = 15.0 // near-black overall (lens blocked/covered)
    var globalBrightnessMax: Double = 245.0 // near-white overall (direct harsh light/saturation)

    // Level-2 Blister crop quality checks (evaluated on the tightly cropped blister strip)
    var stripCropQualityPadding: Float = 0.0f // padding around strip box for crop quality check (isolated to blister, avoids leaking sharp/blurry background)
    var minBlisterResolutionPx: Int = 24 // crop width and height must each be at least this (supports unit test fixtures and small real strips)
    var blisterBlurVarianceMin: Double = 100.0 // Laplacian variance threshold on blister crop
    var blisterBrightnessMin: Double = 40.0 // min mean brightness of blister crop
    var blisterBrightnessMax: Double = 220.0 // max mean brightness of blister crop
    var blisterContrastMin: Double = 15.0 // min RMS contrast (standard deviation of luminance)
    var blisterGlareFractionMax: Double = 0.08 // max fraction of saturated near-white pixels (>= 250)
}
