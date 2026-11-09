package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.meditrack.app.core.config.VerificationConfig
import com.meditrack.app.core.util.LetterboxTransform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
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
) : StripDetector, Closeable {

    companion object {
        const val DEFAULT_INPUT_SIZE = 320
        const val NUM_DETECTIONS = 10
        const val NUM_COORDINATES = 4
        const val MODEL_FILE_NAME = "strip_detector.tflite"

        // Unified Class IDs from class_map.yaml
        const val CLASS_STRIP = 0
        const val CLASS_CELL_FULL = 1
        const val CLASS_CELL_EMPTY = 2
        const val CLASS_CELL_PARTIAL = 3

        /**
         * Validates model input and output tensor shapes against the supported YOLOv8 / SSD contract.
         * Returns null if valid, or a descriptive error message if invalid.
         */
        fun validateModelContract(interpreter: Interpreter): String? {
            val inputCount = interpreter.inputTensorCount
            if (inputCount < 1) {
                return "Model has no input tensors."
            }
            val inTensor = interpreter.getInputTensor(0)
            val inShape = inTensor.shape()
            if (inShape.size != 4) {
                return "Input tensor must have rank 4 [batch, height, width, channels], found rank ${inShape.size}: ${inShape.contentToString()}"
            }
            val inChannels = inShape.getOrNull(3) ?: -1
            if (inChannels != 3 && inChannels != 1 && inChannels > 0) {
                return "Input tensor channels must be 1 or 3, found $inChannels"
            }

            val outputCount = interpreter.outputTensorCount
            if (outputCount == 1) {
                // YOLOv8 detector: shape [1, channels, anchors] (e.g. [1, 8, 2100])
                val outTensor = interpreter.getOutputTensor(0)
                val outShape = outTensor.shape()
                if (outShape.size != 3) {
                    return "YOLO output tensor must have rank 3 [1, channels, anchors], found rank ${outShape.size}: ${outShape.contentToString()}"
                }
                val channels = outShape[1]
                val anchors = outShape[2]
                if (channels < 5) {
                    return "YOLO output channels must be at least 5 (coords + >=1 class), found $channels"
                }
                if (anchors <= 0) {
                    return "YOLO output anchors must be > 0, found $anchors"
                }
            } else if (outputCount == 4) {
                // SSD detector: locations [1, N, 4], classes [1, N], scores [1, N], count [1]
                val locShape = interpreter.getOutputTensor(0).shape()
                val classShape = interpreter.getOutputTensor(1).shape()
                val scoreShape = interpreter.getOutputTensor(2).shape()
                if (locShape.size < 2 || classShape.size < 2 || scoreShape.size < 2) {
                    return "SSD output tensor shapes invalid: loc=${locShape.contentToString()}, classes=${classShape.contentToString()}, scores=${scoreShape.contentToString()}"
                }
            } else {
                return "Unsupported output tensor count: $outputCount. Expected 1 (YOLO) or 4 (SSD)."
            }
            return null
        }
    }

    private data class StripCandidate(val box: RectF, val confidence: Float)

    private val mutex = Mutex()
    private var interpreter: Interpreter? = null
    private var initAttempted = false
    private var modelContractError: String? = null

    internal constructor(
        context: Context,
        testInterpreter: Interpreter?
    ) : this(context) {
        this.interpreter = testInterpreter
        this.initAttempted = testInterpreter != null
    }

    /**
     * Lazily initializes and validates the TFLite interpreter off the main thread.
     */
    private suspend fun getOrInitInterpreter(): Interpreter? = withContext(Dispatchers.IO) {
        if (initAttempted) {
            if (modelContractError != null) {
                throw IllegalStateException("Verification unavailable: $modelContractError")
            }
            return@withContext interpreter
        }

        initAttempted = true
        try {
            val assetManager = context.assets
            val newInterpreter = try {
                val fileDescriptor = assetManager.openFd(MODEL_FILE_NAME)
                val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
                val fileChannel = inputStream.channel
                val startOffset = fileDescriptor.startOffset
                val declaredLength = fileDescriptor.declaredLength
                val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                Interpreter(modelBuffer)
            } catch (_: Throwable) {
                // Fallback if asset is compressed or openFd unsupported
                val bytes = assetManager.open(MODEL_FILE_NAME).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buffer.put(bytes)
                buffer.rewind()
                Interpreter(buffer)
            }

            // Assert model contract (C3)
            val contractError = validateModelContract(newInterpreter)
            if (contractError != null) {
                newInterpreter.close()
                modelContractError = contractError
                throw IllegalStateException("Verification unavailable: $contractError")
            }

            interpreter = newInterpreter
            newInterpreter
        } catch (e: IllegalStateException) {
            modelContractError = e.message
            throw e
        } catch (_: Throwable) {
            interpreter = null
            null
        }
    }

    /**
     * Executes blister strip and cell detection on [bitmap].
     * Serialized with [Mutex] to prevent SIGSEGV from concurrent interpreter access (C6).
     */
    override suspend fun detect(bitmap: Bitmap): Detection = mutex.withLock {
        withContext(Dispatchers.Default) {
            val activeInterpreter = getOrInitInterpreter()
                ?: throw IllegalStateException(
                    "Model assets/strip_detector.tflite not installed. Real model arrives in Stage 13."
                )

            val inTensor = activeInterpreter.getInputTensor(0)
            val inShape = inTensor.shape()
            val inHeight = inShape.getOrNull(1)?.takeIf { it > 0 } ?: DEFAULT_INPUT_SIZE
            val inWidth = inShape.getOrNull(2)?.takeIf { it > 0 } ?: DEFAULT_INPUT_SIZE

            // Aspect-ratio preserving letterbox preprocessing (C2)
            val transform = LetterboxTransform(bitmap.width, bitmap.height, inWidth, inHeight)
            val inputBuffer = preprocessBitmap(bitmap, activeInterpreter, transform)

            val rawCells = mutableListOf<Cell>()
            val rawStrips = mutableListOf<StripCandidate>()

            val margin = VerificationConfig.edgeMarginFraction
            val confidenceThreshold = VerificationConfig.confidenceThreshold
            val outputCount = activeInterpreter.outputTensorCount

            if (outputCount == 1) {
                // YOLO detector: shape [1, channels, anchors] (e.g. [1, 8, 2100])
                val outTensor = activeInterpreter.getOutputTensor(0)
                val shape = outTensor.shape()
                val channels = shape.getOrNull(1) ?: 8
                val anchors = shape.getOrNull(2) ?: 2100

                val outputArray = Array(1) { Array(channels) { FloatArray(anchors) } }
                activeInterpreter.run(inputBuffer, outputArray)

                for (j in 0 until anchors) {
                    var bestClass = -1
                    var maxScore = 0f
                    for (c in 4 until channels) {
                        val score = outputArray[0][c][j]
                        if (score > maxScore) {
                            maxScore = score
                            bestClass = c - 4
                        }
                    }

                    if (maxScore >= confidenceThreshold) {
                        val xc = outputArray[0][0][j]
                        val yc = outputArray[0][1][j]
                        val w = outputArray[0][2][j]
                        val h = outputArray[0][3][j]

                        // Map letterbox model coordinates back to original normalized image space (C2)
                        val box = transform.toOriginalNormalized(xc, yc, w, h)

                        when (bestClass) {
                            // C1: Collect all candidate strip detections to pick highest confidence later
                            CLASS_STRIP -> rawStrips.add(StripCandidate(box, maxScore))
                            CLASS_CELL_FULL -> rawCells.add(Cell(box = box, state = CellState.FULL, confidence = maxScore))
                            CLASS_CELL_EMPTY -> rawCells.add(Cell(box = box, state = CellState.EMPTY, confidence = maxScore))
                            CLASS_CELL_PARTIAL -> rawCells.add(Cell(box = box, state = CellState.PARTIAL, confidence = maxScore))
                        }
                    }
                }
            } else {
                // SSD detector: locations [1, N, 4], classes [1, N], scores [1, N], count [1]
                val outputLocations = Array(1) { Array(NUM_DETECTIONS) { FloatArray(NUM_COORDINATES) } }
                val outputClasses = Array(1) { FloatArray(NUM_DETECTIONS) }
                val outputScores = Array(1) { FloatArray(NUM_DETECTIONS) }
                val numDetections = FloatArray(1)

                val outputMap = HashMap<Int, Any>()
                outputMap[0] = outputLocations
                outputMap[1] = outputClasses
                outputMap[2] = outputScores
                outputMap[3] = numDetections

                activeInterpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

                val count = numDetections[0].toInt().coerceIn(0, NUM_DETECTIONS)
                for (i in 0 until count) {
                    val score = outputScores[0][i]
                    if (score < confidenceThreshold) continue

                    val rawClass = outputClasses[0][i].toInt()
                    val rawYmin = outputLocations[0][i][0]
                    val rawXmin = outputLocations[0][i][1]
                    val rawYmax = outputLocations[0][i][2]
                    val rawXmax = outputLocations[0][i][3]

                    val box = transform.toOriginalNormalizedBox(rawYmin, rawXmin, rawYmax, rawXmax)

                    when (rawClass) {
                        // C1: Collect all candidate strip detections
                        CLASS_STRIP -> rawStrips.add(StripCandidate(box, score))
                        CLASS_CELL_FULL -> rawCells.add(Cell(box = box, state = CellState.FULL, confidence = score))
                        CLASS_CELL_EMPTY -> rawCells.add(Cell(box = box, state = CellState.EMPTY, confidence = score))
                        CLASS_CELL_PARTIAL -> rawCells.add(Cell(box = box, state = CellState.PARTIAL, confidence = score))
                        else -> {
                            val mappedState = if (rawClass % 2 == 0) CellState.FULL else CellState.EMPTY
                            rawCells.add(Cell(box = box, state = mappedState, confidence = score))
                        }
                    }
                }
            }

            // C1: Pick the strip detection with the highest confidence
            val bestStrip = rawStrips.maxByOrNull { it.confidence }
            val detectedStripBox = bestStrip?.box
            val detectedStripConfidence = bestStrip?.confidence

            // C4: Apply Class-Agnostic Non-Maximum Suppression (NMS) across all cell states
            val filteredCells = applyNms(rawCells, VerificationConfig.iouThreshold)

            // Spatially cluster cells to filter out stray background false-positives (e.g. tablecloth patterns, table edges)
            val clusteredCells = filterOutlierCells(filteredCells)

            // Check if detectedStripBox actually encloses the majority of detected cells.
            // If the model predicted a strip on an artifact (e.g. table edge or background border)
            // that does not enclose the cells, discard it in favor of cell cluster bounding.
            val stripEnclosesCells = if (detectedStripBox != null && clusteredCells.isNotEmpty()) {
                val cellsInside = clusteredCells.count { cell ->
                    val cx = (cell.box.left + cell.box.right) / 2f
                    val cy = (cell.box.top + cell.box.bottom) / 2f
                    cx >= detectedStripBox.left && cx <= detectedStripBox.right &&
                            cy >= detectedStripBox.top && cy <= detectedStripBox.bottom
                }
                cellsInside.toFloat() / clusteredCells.size >= 0.5f
            } else {
                detectedStripBox != null
            }

            // Determine if strip is fully in frame based on normalized original image coordinates
            val effectiveStripBox = if (detectedStripBox != null && stripEnclosesCells) {
                detectedStripBox
            } else {
                deriveStripBox(clusteredCells)
            }

            val isFullyInFrame = if (effectiveStripBox != null) {
                effectiveStripBox.left >= margin &&
                        effectiveStripBox.top >= margin &&
                        effectiveStripBox.right <= (1.0f - margin) &&
                        effectiveStripBox.bottom <= (1.0f - margin)
            } else {
                false
            }

            Detection(
                cells = clusteredCells,
                stripFullyInFrame = isFullyInFrame,
                stripConfidence = detectedStripConfidence
            )
        }
    }

    /**
     * Latency benchmark: executes repeated inference runs and returns average latency in ms.
     */
    suspend fun benchmarkLatency(bitmap: Bitmap, iterations: Int = 5): Long = withContext(Dispatchers.Default) {
        val activeInterpreter = try {
            getOrInitInterpreter()
        } catch (_: Throwable) {
            null
        }
        if (activeInterpreter == null) return@withContext 0L
        val times = mutableListOf<Long>()
        repeat(iterations) {
            val start = System.currentTimeMillis()
            detect(bitmap)
            val elapsed = System.currentTimeMillis() - start
            times.add(elapsed)
        }
        times.average().toLong()
    }

    private fun preprocessBitmap(
        bitmap: Bitmap,
        activeInterpreter: Interpreter,
        transform: LetterboxTransform
    ): ByteBuffer {
        val inTensor = activeInterpreter.getInputTensor(0)
        val inShape = inTensor.shape()
        val inHeight = inShape.getOrNull(1)?.takeIf { it > 0 } ?: DEFAULT_INPUT_SIZE
        val inWidth = inShape.getOrNull(2)?.takeIf { it > 0 } ?: DEFAULT_INPUT_SIZE
        val isFloat = inTensor.dataType() == DataType.FLOAT32

        val letterboxed = transform.applyLetterbox(bitmap)
        try {
            val bytesPerChannel = if (isFloat) 4 else 1
            val byteBuffer = ByteBuffer.allocateDirect(1 * inHeight * inWidth * 3 * bytesPerChannel)
            byteBuffer.order(ByteOrder.nativeOrder())

            val pixels = IntArray(inWidth * inHeight)
            letterboxed.getPixels(pixels, 0, inWidth, 0, 0, inWidth, inHeight)

            for (pixel in pixels) {
                val r = (pixel shr 16 and 0xFF)
                val g = (pixel shr 8 and 0xFF)
                val b = (pixel and 0xFF)

                if (isFloat) {
                    byteBuffer.putFloat(r / 255.0f)
                    byteBuffer.putFloat(g / 255.0f)
                    byteBuffer.putFloat(b / 255.0f)
                } else {
                    byteBuffer.put(r.toByte())
                    byteBuffer.put(g.toByte())
                    byteBuffer.put(b.toByte())
                }
            }

            byteBuffer.rewind()
            return byteBuffer
        } finally {
            if (!letterboxed.isRecycled) {
                letterboxed.recycle()
            }
        }
    }

    internal fun filterOutlierCells(cells: List<Cell>): List<Cell> {
        if (cells.size <= 2) return cells

        val centers = cells.map { cell ->
            val cx = (cell.box.left + cell.box.right) / 2f
            val cy = (cell.box.top + cell.box.bottom) / 2f
            Pair(cx, cy)
        }

        val sortedX = centers.map { it.first }.sorted()
        val sortedY = centers.map { it.second }.sorted()
        val medX = sortedX[sortedX.size / 2]
        val medY = sortedY[sortedY.size / 2]

        val distances = centers.map { (cx, cy) ->
            val dx = cx - medX
            val dy = cy - medY
            kotlin.math.sqrt(dx * dx + dy * dy)
        }

        val sortedDistances = distances.sorted()
        val medDistance = sortedDistances[sortedDistances.size / 2]

        // Reject isolated detections far from the main cell cluster.
        // Threshold allows genuine oblong blister packs (up to 0.40 normalized radius)
        // while dropping background false-positives at the edges of the photo.
        val threshold = max(0.40f, medDistance * 3.0f)

        val clustered = cells.filterIndexed { index, _ -> distances[index] <= threshold }
        return if (clustered.isNotEmpty()) clustered else cells
    }

    internal fun deriveStripBox(cells: List<Cell>): RectF? {
        if (cells.isEmpty()) return null
        val cluster = filterOutlierCells(cells)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (cell in cluster) {
            minX = min(minX, cell.box.left)
            minY = min(minY, cell.box.top)
            maxX = max(maxX, cell.box.right)
            maxY = max(maxY, cell.box.bottom)
        }

        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * C4: Class-agnostic Non-Maximum Suppression across all cell states.
     * Physical pill cavities cannot be both FULL and EMPTY. If the model outputs
     * overlapping boxes for the same blister cavity, the highest confidence one survives.
     */
    internal fun applyNms(cells: List<Cell>, iouThreshold: Float): List<Cell> {
        val sorted = cells.sortedByDescending { it.confidence }
        val result = mutableListOf<Cell>()

        for (cell in sorted) {
            var shouldKeep = true
            for (kept in result) {
                if (computeIoU(cell.box, kept.box) > iouThreshold) {
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

    internal fun computeIoU(a: RectF, b: RectF): Float {
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

    /**
     * C6: Cleanly closes the TFLite interpreter and frees native resources.
     */
    override fun close() {
        runBlocking {
            mutex.withLock {
                try {
                    interpreter?.close()
                } catch (_: Throwable) {}
                interpreter = null
                initAttempted = false
                modelContractError = null
            }
        }
    }
}
