package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.meditrack.app.core.config.VerificationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class TfliteStripDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : StripDetector {

    companion object {
        const val INPUT_SIZE = 300
        const val NUM_DETECTIONS = 10
        const val NUM_COORDINATES = 4
        private const val MODEL_FILE_NAME = "strip_detector.tflite"

        // Unified Class IDs from class_map.yaml
        const val CLASS_STRIP = 0
        const val CLASS_CELL_FULL = 1
        const val CLASS_CELL_EMPTY = 2
        const val CLASS_CELL_PARTIAL = 3
    }

    private var interpreter: Interpreter? = null

    init {
        try {
            val assetManager = context.assets
            val fileDescriptor = assetManager.openFd(MODEL_FILE_NAME)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            interpreter = Interpreter(modelBuffer)
        } catch (_: Throwable) {
            interpreter = null
        }
    }

    override suspend fun detect(bitmap: Bitmap): Detection = withContext(Dispatchers.Default) {
        val activeInterpreter = interpreter
            ?: throw IllegalStateException(
                "Model assets/strip_detector.tflite not installed. Real model arrives in Stage 13."
            )

        // 1. Preprocess Bitmap -> ByteBuffer
        val inputBuffer = preprocessBitmap(bitmap)

        // 2. Prepare Output Tensors
        // Locations: [1, NUM_DETECTIONS, 4]
        val outputLocations = Array(1) { Array(NUM_DETECTIONS) { FloatArray(NUM_COORDINATES) } }
        // Classes: [1, NUM_DETECTIONS]
        val outputClasses = Array(1) { FloatArray(NUM_DETECTIONS) }
        // Scores: [1, NUM_DETECTIONS]
        val outputScores = Array(1) { FloatArray(NUM_DETECTIONS) }
        // Number of detections: [1]
        val numDetections = FloatArray(1)

        val outputMap = HashMap<Int, Any>()
        outputMap[0] = outputLocations
        outputMap[1] = outputClasses
        outputMap[2] = outputScores
        outputMap[3] = numDetections

        // 3. Execute Inference
        activeInterpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        // 4. Post-Process Detections
        val count = numDetections[0].toInt().coerceIn(0, NUM_DETECTIONS)
        val rawCells = mutableListOf<Cell>()
        var detectedStripBox: RectF? = null

        val margin = VerificationConfig.edgeMarginFraction
        val confidenceThreshold = VerificationConfig.confidenceThreshold

        for (i in 0 until count) {
            val score = outputScores[0][i]
            if (score < confidenceThreshold) continue

            val rawClass = outputClasses[0][i].toInt()
            val ymin = outputLocations[0][i][0].coerceIn(0f, 1f)
            val xmin = outputLocations[0][i][1].coerceIn(0f, 1f)
            val ymax = outputLocations[0][i][2].coerceIn(0f, 1f)
            val xmax = outputLocations[0][i][3].coerceIn(0f, 1f)

            val box = RectF(xmin, ymin, xmax, ymax)

            when (rawClass) {
                CLASS_STRIP -> {
                    detectedStripBox = box
                }
                CLASS_CELL_FULL -> {
                    rawCells.add(Cell(box = box, state = CellState.FULL, confidence = score))
                }
                CLASS_CELL_EMPTY -> {
                    rawCells.add(Cell(box = box, state = CellState.EMPTY, confidence = score))
                }
                CLASS_CELL_PARTIAL -> {
                    rawCells.add(Cell(box = box, state = CellState.PARTIAL, confidence = score))
                }
                else -> {
                    // Fallback mapping for generic multi-class models
                    val mappedState = if (rawClass % 2 == 0) CellState.FULL else CellState.EMPTY
                    rawCells.add(Cell(box = box, state = mappedState, confidence = score))
                }
            }
        }

        // Apply Non-Maximum Suppression (NMS)
        val filteredCells = applyNms(rawCells, VerificationConfig.iouThreshold)

        // Determine if strip is fully in frame
        val effectiveStripBox = detectedStripBox ?: deriveStripBox(filteredCells)
        val isFullyInFrame = if (effectiveStripBox != null) {
            effectiveStripBox.left >= margin &&
                    effectiveStripBox.top >= margin &&
                    effectiveStripBox.right <= (1.0f - margin) &&
                    effectiveStripBox.bottom <= (1.0f - margin)
        } else {
            true
        }

        Detection(
            cells = filteredCells,
            stripFullyInFrame = isFullyInFrame
        )
    }

    /**
     * Latency benchmark: executes repeated inference runs and returns average latency in ms.
     */
    suspend fun benchmarkLatency(bitmap: Bitmap, iterations: Int = 5): Long = withContext(Dispatchers.Default) {
        if (interpreter == null) return@withContext 0L
        val times = mutableListOf<Long>()
        repeat(iterations) {
            val start = System.currentTimeMillis()
            detect(bitmap)
            val elapsed = System.currentTimeMillis() - start
            times.add(elapsed)
        }
        times.average().toLong()
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val byteBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF).toByte()
            val g = (pixel shr 8 and 0xFF).toByte()
            val b = (pixel and 0xFF).toByte()
            byteBuffer.put(r)
            byteBuffer.put(g)
            byteBuffer.put(b)
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun deriveStripBox(cells: List<Cell>): RectF? {
        if (cells.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (cell in cells) {
            minX = min(minX, cell.box.left)
            minY = min(minY, cell.box.top)
            maxX = max(maxX, cell.box.right)
            maxY = max(maxY, cell.box.bottom)
        }

        return RectF(minX, minY, maxX, maxY)
    }

    private fun applyNms(cells: List<Cell>, iouThreshold: Float): List<Cell> {
        val sorted = cells.sortedByDescending { it.confidence }
        val result = mutableListOf<Cell>()

        for (cell in sorted) {
            var shouldKeep = true
            for (kept in result) {
                if (cell.state == kept.state && computeIoU(cell.box, kept.box) > iouThreshold) {
                    shouldKeep = false
                    break
                }
            }
            if (shouldKeep) {
                result.add(cell)
            }
        }

        return result
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val xA = max(a.left, b.left)
        val yA = max(a.top, b.top)
        val xB = min(a.right, b.right)
        val yB = min(a.bottom, b.bottom)

        val intersectionArea = max(0f, xB - xA) * max(0f, yB - yA)
        val boxAArea = (a.right - a.left) * (a.bottom - a.top)
        val boxBArea = (b.right - b.left) * (b.bottom - b.top)

        val unionArea = boxAArea + boxBArea - intersectionArea
        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }
}
