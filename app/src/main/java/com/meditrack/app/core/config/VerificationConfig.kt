package com.meditrack.app.core.config

/**
 * Stage 13 / 14: Verification & ML Model Configuration
 *
 * Configurable parameters and remote-config-ready hooks for on-device
 * blister strip detection, inference latency targets, and model auditing.
 */
object VerificationConfig {
    var modelVersion: String = "v1.0.0-tflite"
    var isTfliteEnabled: Boolean = true
    var confidenceThreshold: Float = 0.5f
    var iouThreshold: Float = 0.45f
    var edgeMarginFraction: Float = 0.05f // 5% border margin for strip bounds
    var maxInferenceLatencyMs: Long = 400L // Target <= 400 ms on mid-range devices
}
