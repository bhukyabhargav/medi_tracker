package com.meditrack.app.feature.verification.pipeline

import com.meditrack.app.domain.model.RejectReason

/**
 * Task 4: Structured verification failure reasons.
 *
 * Each failure reason includes:
 * - A distinct machine-readable code
 * - A patient-facing clear description of the issue
 * - An actionable retry hint telling the user how to fix the problem on their next attempt
 * - A mapping to the domain [RejectReason] ensuring consistency with attempt strike counting
 *   (quality gate rejections do not consume strikes).
 */
enum class VerificationFailureReason(
    val code: String,
    val userMessage: String,
    val retryHint: String,
    val isQualityGate: Boolean,
    val mappedRejectReason: RejectReason
) {
    BLISTER_NOT_FOUND(
        code = "BLISTER_NOT_FOUND",
        userMessage = "No blister pack was detected in the photo.",
        retryHint = "Place the blister pack flat on a plain surface with adequate lighting and retake.",
        isQualityGate = false,
        mappedRejectReason = RejectReason.STRIP_NOT_FULLY_VISIBLE
    ),
    BLISTER_PARTIALLY_VISIBLE(
        code = "BLISTER_PARTIALLY_VISIBLE",
        userMessage = "The blister pack is cut off by the edge of the photo.",
        retryHint = "Move the phone back slightly so all edges of the blister pack are fully inside the frame.",
        isQualityGate = false,
        mappedRejectReason = RejectReason.STRIP_NOT_FULLY_VISIBLE
    ),
    BLISTER_TOO_SMALL(
        code = "BLISTER_TOO_SMALL",
        userMessage = "The blister pack is too small or far away in the frame.",
        retryHint = "Hold the camera closer so the blister strip fills a larger portion of the image.",
        isQualityGate = true,
        mappedRejectReason = RejectReason.STRIP_NOT_FULLY_VISIBLE
    ),
    IMAGE_BLURRY(
        code = "IMAGE_BLURRY",
        userMessage = "The blister pack is blurry or out of focus.",
        retryHint = "Hold your phone steady and tap the screen to focus on the medicine strip.",
        isQualityGate = true,
        mappedRejectReason = RejectReason.BLURRY
    ),
    EXCESSIVE_GLARE(
        code = "EXCESSIVE_GLARE",
        userMessage = "Glare or light reflections are washing out the blister cavities.",
        retryHint = "Tilt the blister pack or phone slightly to angle reflections away from overhead lights.",
        isQualityGate = true,
        mappedRejectReason = RejectReason.GLARE
    ),
    POOR_EXPOSURE(
        code = "POOR_EXPOSURE",
        userMessage = "The lighting is too dark or washed out to clearly see the cavities.",
        retryHint = "Move to a well-lit area with even lighting and avoid harsh shadows or direct backlight.",
        isQualityGate = true,
        mappedRejectReason = RejectReason.TOO_DARK
    ),
    INSUFFICIENT_RESOLUTION(
        code = "INSUFFICIENT_RESOLUTION",
        userMessage = "The blister pack resolution is too low for reliable cavity counting.",
        retryHint = "Move the camera closer to the blister pack and ensure your camera lens is clean.",
        isQualityGate = true,
        mappedRejectReason = RejectReason.BLURRY
    ),
    AMBIGUOUS_CELL_DETECTION(
        code = "AMBIGUOUS_CELL_DETECTION",
        userMessage = "Blister cavities could not be reliably distinguished.",
        retryHint = "Ensure all foil pockets are clean, unobstructed, and clearly facing the camera.",
        isQualityGate = false,
        mappedRejectReason = RejectReason.WRONG_PILL_COUNT
    );

    companion object {
        fun fromQualityReason(reason: RejectReason?): VerificationFailureReason? = when (reason) {
            RejectReason.BLURRY -> IMAGE_BLURRY
            RejectReason.GLARE -> EXCESSIVE_GLARE
            RejectReason.TOO_DARK, RejectReason.TOO_BRIGHT -> POOR_EXPOSURE
            else -> null
        }
    }
}
