package com.meditrack.app.feature.verification.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.meditrack.app.core.config.VerificationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Offline blister cavity detector: runs the RF-DETR Nano model exported from ml/weights.pt
 * (assets/rfdetr_blister.onnx) on the phone with ONNX Runtime. No internet is needed.
 *
 * The model finds every cavity and labels it empty or full. It has no "blister pack" class, so the strip box is
 * the box around all cavities, and "is this a blister at all" is decided by [BlisterPresenceValidator] from the
 * layout of the cavities, exactly as for the other on-device detector.
 *
 * Preprocessing must match the export: the whole photo is resized straight to 384x384 with bilinear filtering
 * (no letterbox), scaled to 0..1 and normalized with the ImageNet mean and standard deviation.
 */
@Singleton
class RfdetrStripDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : StripDetector {

    companion object {
        private const val TAG = "RfdetrStripDetector"
        const val MODEL_ASSET = "rfdetr_blister.onnx"
        const val INPUT_SIZE = 384
        const val NMS_IOU = 0.5f

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    private fun getOrCreateSession(): OrtSession {
        session?.let { return it }
        val environment = OrtEnvironment.getEnvironment()
        // Read straight from the APK every time the process starts, so an app update always runs its own model
        // (a copy cached on disk would keep an old model alive after an update).
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(min(4, max(1, Runtime.getRuntime().availableProcessors())))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val created = environment.createSession(modelBytes, options)
        env = environment
        session = created
        return created
    }

    override suspend fun detect(bitmap: Bitmap): Detection = mutex.withLock {
        withContext(Dispatchers.Default) {
            val ortSession = try {
                getOrCreateSession()
            } catch (t: Throwable) {
                Log.e(TAG, "Offline blister model could not be loaded: ${t.message}", t)
                throw IllegalStateException("The offline blister check could not start on this phone.", t)
            }
            val environment = env ?: throw IllegalStateException("The offline blister check is not ready.")

            val input = preprocess(bitmap)
            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

            val boxes = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
                ortSession.run(mapOf(ortSession.inputNames.first() to tensor)).use { result ->
                    val dets = flatten(result.get("dets").get().value)
                    val labels = flatten(result.get("labels").get().value)
                    val numQueries = dets.size / 4
                    val numClasses = if (numQueries > 0) labels.size / numQueries else 0
                    RfdetrPostProcessor.decode(
                        dets = dets,
                        logits = labels,
                        numQueries = numQueries,
                        numClasses = numClasses,
                        scoreThreshold = VerificationConfig.confidenceThreshold,
                        nmsIou = NMS_IOU
                    )
                }
            }

            toDetection(boxes)
        }
    }

    internal fun toDetection(boxes: List<RfdetrPostProcessor.RawBox>): Detection {
        if (boxes.isEmpty()) {
            return Detection(cells = emptyList(), stripFullyInFrame = false, stripConfidence = 0f)
        }
        val cells = boxes.map {
            Cell(
                box = RectF(it.left, it.top, it.right, it.bottom),
                state = if (it.isEmptyCavity) CellState.EMPTY else CellState.FULL,
                confidence = it.score
            )
        }
        val left = boxes.minOf { it.left }
        val top = boxes.minOf { it.top }
        val right = boxes.maxOf { it.right }
        val bottom = boxes.maxOf { it.bottom }

        // A cavity touching the photo edge means part of the strip is probably outside the photo.
        val tol = VerificationConfig.cellCutoffToleranceFraction
        val fullyInFrame = left > tol && top > tol && right < 1f - tol && bottom < 1f - tol

        return Detection(
            cells = cells,
            stripFullyInFrame = fullyInFrame,
            stripConfidence = boxes.map { it.score }.average().toFloat(),
            stripBox = RectF(left, top, right, bottom)
        )
    }

    private fun preprocess(source: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== source) scaled.recycle()

        val area = INPUT_SIZE * INPUT_SIZE
        val out = FloatArray(3 * area)
        for (i in 0 until area) {
            val p = pixels[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[area + i] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * area + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    /** ONNX Runtime returns nested float arrays ([1][queries][n]); flatten them in order. */
    private fun flatten(value: Any?): FloatArray {
        val out = ArrayList<Float>()
        fun walk(v: Any?) {
            when (v) {
                is FloatArray -> v.forEach { out.add(it) }
                is Array<*> -> v.forEach { walk(it) }
            }
        }
        walk(value)
        return out.toFloatArray()
    }
}
