package com.meditrack.app.feature.verification.pipeline

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the raw outputs of the offline RF-DETR blister model into cavity boxes.
 * Pure Kotlin (no Android types) so the exact counting rules can be unit tested.
 *
 * Model outputs (exported from ml/weights.pt by ml/export_rfdetr_onnx.py):
 * - dets:   numQueries x 4 boxes as (centerX, centerY, width, height), normalized 0..1 of the input image
 * - labels: numQueries x numClasses raw logits (sigmoid gives the score of each class)
 *
 * Class indices of this model, confirmed by running it on a labelled strip photo:
 * 3 = empty cavity, 4 = full cavity. Indices 0..2 never score for cavities and are ignored.
 */
object RfdetrPostProcessor {

    const val CLASS_EMPTY_CAVITY = 3
    const val CLASS_FULL_CAVITY = 4

    data class RawBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val classIndex: Int,
        val score: Float
    ) {
        val isEmptyCavity: Boolean get() = classIndex == CLASS_EMPTY_CAVITY
    }

    /**
     * @param dets flattened numQueries*4 box values
     * @param logits flattened numQueries*numClasses class logits
     * @param scoreThreshold a query is kept only when its best cavity class scores at least this
     * @param nmsIou two kept boxes overlapping more than this are the same pocket; the higher score wins,
     *               whatever the class. This is what stops one pocket being counted as both empty and full.
     */
    fun decode(
        dets: FloatArray,
        logits: FloatArray,
        numQueries: Int,
        numClasses: Int,
        scoreThreshold: Float,
        nmsIou: Float
    ): List<RawBox> {
        require(dets.size >= numQueries * 4) { "dets too short" }
        require(logits.size >= numQueries * numClasses) { "labels too short" }
        if (numClasses <= CLASS_FULL_CAVITY) return emptyList()

        val candidates = ArrayList<RawBox>()
        for (q in 0 until numQueries) {
            val emptyScore = sigmoid(logits[q * numClasses + CLASS_EMPTY_CAVITY])
            val fullScore = sigmoid(logits[q * numClasses + CLASS_FULL_CAVITY])
            val cls = if (emptyScore >= fullScore) CLASS_EMPTY_CAVITY else CLASS_FULL_CAVITY
            val score = max(emptyScore, fullScore)
            if (score < scoreThreshold) continue

            val cx = dets[q * 4]
            val cy = dets[q * 4 + 1]
            val w = dets[q * 4 + 2]
            val h = dets[q * 4 + 3]
            if (w <= 0f || h <= 0f) continue
            candidates.add(
                RawBox(
                    left = (cx - w / 2f).coerceIn(0f, 1f),
                    top = (cy - h / 2f).coerceIn(0f, 1f),
                    right = (cx + w / 2f).coerceIn(0f, 1f),
                    bottom = (cy + h / 2f).coerceIn(0f, 1f),
                    classIndex = cls,
                    score = score
                )
            )
        }

        val kept = ArrayList<RawBox>()
        for (box in candidates.sortedByDescending { it.score }) {
            if (kept.none { iou(it, box) > nmsIou }) kept.add(box)
        }
        return kept
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    internal fun iou(a: RawBox, b: RawBox): Float {
        val iw = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val ih = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = iw * ih
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union > 0f) inter / union else 0f
    }
}
