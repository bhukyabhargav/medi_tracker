package com.meditrack.app.feature.verification.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.meditrack.app.core.config.VerificationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Offline blister pack & cavity detector powered by the trained RF-DETR model
 * exported from Roboflow weights (weights (1).pt) and quantized for on-device inference.
 *
 * Model specification:
 * - Input: [1, 3, 512, 512] RGB Float32 with ImageNet normalization
 * - Outputs:
 *   - "dets": [1, 300, 4] normalized coordinates (cx, cy, w, h)
 *   - "labels": [1, 300, 5] class logits:
 *       - 0: background / none
 *       - 1: dataset category / background
 *       - 2: blister_pack
 *       - 3: empty_cavity
 *       - 4: full_cavity
 */
@Singleton
class RfdetrStripDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : StripDetector, Closeable {

    companion object {
        const val MODEL_FILE_NAME = "rfdetr_blister.onnx"
        const val INPUT_SIZE = 512
        const val NUM_DETECTIONS = 300

        const val CLASS_BACKGROUND = 0
        const val CLASS_STRIP = 1
        const val CLASS_CELL_EMPTY = 2
        const val CLASS_CELL_FULL = 3

        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val mutex = Mutex()
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var initAttempted = false

    private data class StripCandidate(val box: RectF, val confidence: Float)

    /**
     * Initializes the ONNX Runtime session lazily on IO dispatcher.
     */
    private suspend fun getOrInitSession(): OrtSession? = withContext(Dispatchers.IO) {
        if (initAttempted) {
            return@withContext ortSession
        }
        initAttempted = true
        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val modelFile = File(context.cacheDir, MODEL_FILE_NAME)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                context.assets.open(MODEL_FILE_NAME).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(modelFile.absolutePath, sessionOptions)
            ortSession = session
            session
        } catch (_: Throwable) {
            ortSession = null
            null
        }
    }

    override suspend fun detect(bitmap: Bitmap): Detection = mutex.withLock {
        withContext(Dispatchers.Default) {
            val session = getOrInitSession()
                ?: throw IllegalStateException(
                    "Model assets/$MODEL_FILE_NAME could not be loaded into ONNX Runtime."
                )
            val env = ortEnv ?: throw IllegalStateException("OrtEnvironment is null")

            val floatArray = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
            preprocessBitmap(bitmap, floatArray)
            val floatBuffer = FloatBuffer.wrap(floatArray)

            val inputTensor = OnnxTensor.createTensor(
                env,
                floatBuffer,
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            )

            val rawCells = mutableListOf<Cell>()
            val rawStrips = mutableListOf<StripCandidate>()
            val confThreshold = VerificationConfig.confidenceThreshold

            val results = session.run(mapOf("input" to inputTensor))
            try {
                @Suppress("UNCHECKED_CAST")
                val out0 = (results.get(0).value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val out1 = (results.get(1).value as Array<Array<FloatArray>>)[0]

                val outputNames = session.outputNames.toList()
                val (detsArr, labelsArr) = if (outputNames.getOrNull(0) == "dets") {
                    Pair(out0, out1)
                } else if (outputNames.getOrNull(1) == "dets") {
                    Pair(out1, out0)
                } else {
                    Pair(out0, out1)
                }

                for (i in 0 until min(detsArr.size, NUM_DETECTIONS)) {
                    val logits = labelsArr[i]
                    val stripScore: Float
                    val emptyScore: Float
                    val fullScore: Float

                    if (logits.size >= 5) {
                        // 5-class schema: [bg0, bg1/cat0, blister_pack, empty_cavity, full_cavity]
                        val s1 = logits.getOrElse(1) { -10f }
                        val s2 = logits.getOrElse(2) { -10f }
                        stripScore = sigmoid(max(s1, s2))
                        emptyScore = sigmoid(logits.getOrElse(3) { -10f })
                        fullScore = sigmoid(logits.getOrElse(4) { -10f })
                    } else {
                        // 4-class schema: [bg, blister_pack, empty_cavity, full_cavity]
                        stripScore = sigmoid(logits.getOrElse(CLASS_STRIP) { -10f })
                        emptyScore = sigmoid(logits.getOrElse(CLASS_CELL_EMPTY) { -10f })
                        fullScore = sigmoid(logits.getOrElse(CLASS_CELL_FULL) { -10f })
                    }

                    val cx = detsArr[i][0]
                    val cy = detsArr[i][1]
                    val w = detsArr[i][2]
                    val h = detsArr[i][3]

                    val left = (cx - w / 2f).coerceIn(0f, 1f)
                    val top = (cy - h / 2f).coerceIn(0f, 1f)
                    val right = (cx + w / 2f).coerceIn(0f, 1f)
                    val bottom = (cy + h / 2f).coerceIn(0f, 1f)
                    val box = RectF(left, top, right, bottom)

                    if (stripScore >= confThreshold) {
                        rawStrips.add(StripCandidate(box, stripScore))
                    }

                    val maxCavityScore = max(emptyScore, fullScore)
                    if (maxCavityScore >= confThreshold) {
                        val state = if (emptyScore > fullScore) CellState.EMPTY else CellState.FULL
                        rawCells.add(Cell(box = box, state = state, confidence = maxCavityScore))
                    }
                }
            } finally {
                results.close()
                inputTensor.close()
            }

            val bestStrip = rawStrips.maxByOrNull { it.confidence }
            val detectedStripBox = bestStrip?.box
            val detectedStripConfidence = bestStrip?.confidence

            val filteredCells = applyNms(rawCells, VerificationConfig.iouThreshold)
            val clusteredCells = filterOutlierCells(filteredCells)

            val derivedBox = deriveStripBox(clusteredCells)
            val effectiveStripBox = if (detectedStripBox != null && derivedBox != null) {
                val cellsInside = clusteredCells.count { cell ->
                    detectedStripBox.contains(
                        (cell.box.left + cell.box.right) / 2f,
                        (cell.box.top + cell.box.bottom) / 2f
                    )
                }
                if (clusteredCells.isEmpty() || cellsInside.toFloat() / clusteredCells.size >= 0.5f) {
                    detectedStripBox
                } else {
                    derivedBox
                }
            } else {
                detectedStripBox ?: derivedBox
            }

            val isFullyInFrame = computeStripFullyInFrame(
                stripBox = effectiveStripBox,
                cells = clusteredCells,
                tolerance = VerificationConfig.borderToleranceFraction,
                cellCutoffTolerance = VerificationConfig.cellCutoffToleranceFraction
            )

            Detection(
                cells = clusteredCells,
                stripFullyInFrame = isFullyInFrame,
                stripConfidence = detectedStripConfidence,
                stripBox = effectiveStripBox
            )
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap, outFloats: FloatArray) {
        val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val rOffset = 0
        val gOffset = INPUT_SIZE * INPUT_SIZE
        val bOffset = 2 * INPUT_SIZE * INPUT_SIZE

        for (i in 0 until (INPUT_SIZE * INPUT_SIZE)) {
            val pixel = pixels[i]
            val rNorm = ((pixel shr 16) and 0xFF) / 255.0f
            val gNorm = ((pixel shr 8) and 0xFF) / 255.0f
            val bNorm = (pixel and 0xFF) / 255.0f

            // ImageNet normalization
            outFloats[rOffset + i] = (rNorm - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
            outFloats[gOffset + i] = (gNorm - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
            outFloats[bOffset + i] = (bNorm - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
        }
        if (scaled !== bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }
    }

    private fun sigmoid(x: Float): Float = 1.0f / (1.0f + exp(-x))

    internal fun applyNms(cells: List<Cell>, iouThreshold: Float): List<Cell> {
        val sorted = cells.sortedByDescending { it.confidence }
        val selected = mutableListOf<Cell>()

        for (candidate in sorted) {
            var shouldKeep = true
            for (kept in selected) {
                if (computeIoU(candidate.box, kept.box) >= iouThreshold) {
                    shouldKeep = false
                    break
                }
            }
            if (shouldKeep) {
                selected.add(candidate)
            }
        }
        return selected
    }

    internal fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val union = areaA + areaB - interArea
        return if (union > 0f) interArea / union else 0f
    }

    internal fun filterOutlierCells(cells: List<Cell>): List<Cell> {
        if (cells.size <= 2) return cells
        val centers = cells.map { cell ->
            Pair((cell.box.left + cell.box.right) / 2f, (cell.box.top + cell.box.bottom) / 2f)
        }
        val medianX = centers.map { it.first }.sorted()[centers.size / 2]
        val medianY = centers.map { it.second }.sorted()[centers.size / 2]

        val medianWidth = cells.map { it.box.width() }.sorted()[cells.size / 2]
        val medianHeight = cells.map { it.box.height() }.sorted()[cells.size / 2]
        val maxDist = max(medianWidth, medianHeight) * 5.0f

        return cells.filter { cell ->
            val cx = (cell.box.left + cell.box.right) / 2f
            val cy = (cell.box.top + cell.box.bottom) / 2f
            val dx = cx - medianX
            val dy = cy - medianY
            kotlin.math.sqrt(dx * dx + dy * dy) <= maxDist
        }
    }

    internal fun deriveStripBox(cells: List<Cell>, marginFraction: Float = 0.08f): RectF? {
        if (cells.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (c in cells) {
            minX = min(minX, c.box.left)
            minY = min(minY, c.box.top)
            maxX = max(maxX, c.box.right)
            maxY = max(maxY, c.box.bottom)
        }

        val w = maxX - minX
        val h = maxY - minY
        return RectF(
            (minX - w * marginFraction).coerceIn(0f, 1f),
            (minY - h * marginFraction).coerceIn(0f, 1f),
            (maxX + w * marginFraction).coerceIn(0f, 1f),
            (maxY + h * marginFraction).coerceIn(0f, 1f)
        )
    }

    internal fun computeStripFullyInFrame(
        stripBox: RectF?,
        cells: List<Cell>,
        tolerance: Float = VerificationConfig.borderToleranceFraction,
        cellCutoffTolerance: Float = VerificationConfig.cellCutoffToleranceFraction
    ): Boolean {
        if (stripBox == null && cells.isEmpty()) return false

        for (cell in cells) {
            val b = cell.box
            if (b.left <= cellCutoffTolerance ||
                b.top <= cellCutoffTolerance ||
                b.right >= (1.0f - cellCutoffTolerance) ||
                b.bottom >= (1.0f - cellCutoffTolerance)
            ) {
                return false
            }
        }

        if (cells.isNotEmpty()) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            for (c in cells) {
                minX = min(minX, c.box.left)
                minY = min(minY, c.box.top)
                maxX = max(maxX, c.box.right)
                maxY = max(maxY, c.box.bottom)
            }
            if (minX <= tolerance || minY <= tolerance || maxX >= (1.0f - tolerance) || maxY >= (1.0f - tolerance)) {
                return false
            }
        }

        if (stripBox != null) {
            if (stripBox.left <= tolerance ||
                stripBox.top <= tolerance ||
                stripBox.right >= (1.0f - tolerance) ||
                stripBox.bottom >= (1.0f - tolerance)
            ) {
                return false
            }
        }

        return true
    }

    override fun close() {
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
    }
}
