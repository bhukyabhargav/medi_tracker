package com.meditrack.app.feature.verification.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.meditrack.app.core.config.VerificationConfig
import com.meditrack.app.core.util.LetterboxTransform
import com.meditrack.app.core.util.StripCropPlanner
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
    private var pillBlisterDetector: PillBlisterClassifierDetector? = null
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
     * Multi-pass detection, serialized with [Mutex] to prevent SIGSEGV from concurrent interpreter access.
     *
     * Pass 1 runs on the whole photo and locates the strip (works even when the strip is small in the frame).
     * Unless the strip already fills most of the photo, the model is then re-run on several crops around the
     * strip (different margins). Cells are only a few pixels wide at the model's 320x320 input in pass 1, so
     * they are missed or get low confidence; each crop sees them at a different scale. All cells are mapped
     * back to the original photo, pooled, and de-duplicated with class-agnostic NMS (highest confidence wins),
     * so one weak view of a cell is outvoted by a better one.
     *
     * The in-frame check and strip boundaries are evaluated geometrically against image borders.
     */
    override suspend fun detect(bitmap: Bitmap): Detection = mutex.withLock {
        // If a test interpreter was injected, run the TFLite flow directly
        if (interpreter != null) {
            return@withLock detectTfliteMultiPass(bitmap)
        }

        // Try offline PillBlisterClassifier detector first (ported from aziz-hizem/pill-blister-classifier)
        try {
            if (pillBlisterDetector == null) {
                pillBlisterDetector = PillBlisterClassifierDetector(context)
            }
            val cvResult = pillBlisterDetector!!.detect(bitmap)
            if (cvResult.cells.isNotEmpty()) {
                return@withLock cvResult
            }
        } catch (_: Throwable) {
            // Graceful fallback to TFLite (e.g. host Robolectric unit tests)
        }

        detectTfliteMultiPass(bitmap)
    }

    private suspend fun detectTfliteMultiPass(bitmap: Bitmap): Detection {
        val first = detectUnlocked(bitmap)
        val box = if (first.cells.size >= VerificationConfig.minBlisterCells) {
            deriveStripBox(first.cells, VerificationConfig.stripCropMarginFraction) ?: first.stripBox
        } else {
            first.stripBox
        } ?: return first

        val area = StripCropPlanner.areaFraction(box.left, box.top, box.right, box.bottom)
        if (area >= VerificationConfig.stripZoomAreaThreshold) return first

        val pooled = first.cells.toMutableList()
        for (margin in VerificationConfig.stripCropMargins) {
            val crop = StripCropPlanner.plan(
                box.left, box.top, box.right, box.bottom,
                bitmap.width, bitmap.height, margin
            ) ?: continue

            var cropBitmap: Bitmap? = null
            try {
                cropBitmap = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
                val second = detectUnlocked(cropBitmap)
                for (cell in second.cells) {
                    val m = StripCropPlanner.mapToOriginal(
                        cell.box.left, cell.box.top, cell.box.right, cell.box.bottom,
                        crop, bitmap.width, bitmap.height
                    )
                    pooled.add(cell.copy(box = RectF(m[0], m[1], m[2], m[3])))
                }
            } catch (_: OutOfMemoryError) {
                // Keep whatever has been pooled so far.
            } finally {
                cropBitmap?.let { if (it !== bitmap && !it.isRecycled) it.recycle() }
            }
        }

        val merged = filterOutlierCells(applyNms(pooled, VerificationConfig.iouThreshold))
        // Never return fewer cells than the plain whole-photo pass.
        if (merged.size < first.cells.size) return first

        val finalStripBox = if (merged.isNotEmpty()) {
            deriveStripBox(merged) ?: first.stripBox
        } else {
            first.stripBox
        }

        val finalInFrame = computeStripFullyInFrame(
            stripBox = finalStripBox,
            cells = merged,
            tolerance = VerificationConfig.borderToleranceFraction,
            cellCutoffTolerance = VerificationConfig.cellCutoffToleranceFraction
        )

        return first.copy(
            cells = merged,
            stripBox = finalStripBox,
            stripFullyInFrame = finalInFrame
        )
    }

    /** Single inference pass on [bitmap] (the caller must hold [mutex]). */
    private suspend fun detectUnlocked(bitmap: Bitmap): Detection =
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

            // Pick the strip detection with the highest confidence
            val bestStrip = rawStrips.maxByOrNull { it.confidence }
            val detectedStripBox = bestStrip?.box
            val detectedStripConfidence = bestStrip?.confidence

            // Class-Agnostic Non-Maximum Suppression (NMS) across all cell states
            val filteredCells = applyNms(rawCells, VerificationConfig.iouThreshold)

            // Spatially cluster cells to filter out stray background false-positives
            val clusteredCells = filterOutlierCells(filteredCells)

            val derivedBox = deriveStripBox(clusteredCells)

            // Check if detectedStripBox encloses cells without being an oversized hallucination
            val stripEnclosesCells = if (detectedStripBox != null && derivedBox != null) {
                val derivedArea = (derivedBox.right - derivedBox.left) * (derivedBox.bottom - derivedBox.top)
                val detectedArea = (detectedStripBox.right - detectedStripBox.left) * (detectedStripBox.bottom - detectedStripBox.top)

                val cellsInside = clusteredCells.count { cell ->
                    val cx = (cell.box.left + cell.box.right) / 2f
                    val cy = (cell.box.top + cell.box.bottom) / 2f
                    cx >= detectedStripBox.left && cx <= detectedStripBox.right &&
                            cy >= detectedStripBox.top && cy <= detectedStripBox.bottom
                }
                val majorityEnclosed = cellsInside.toFloat() / clusteredCells.size >= 0.6f
                val reasonableArea = derivedArea <= 0f || detectedArea <= derivedArea * 2.2f
                val borderTouchingHallucination = (detectedStripBox.left <= VerificationConfig.borderToleranceFraction ||
                        detectedStripBox.top <= VerificationConfig.borderToleranceFraction ||
                        detectedStripBox.right >= (1.0f - VerificationConfig.borderToleranceFraction) ||
                        detectedStripBox.bottom >= (1.0f - VerificationConfig.borderToleranceFraction)) &&
                        (derivedBox.left > VerificationConfig.borderToleranceFraction &&
                        derivedBox.top > VerificationConfig.borderToleranceFraction &&
                        derivedBox.right < (1.0f - VerificationConfig.borderToleranceFraction) &&
                        derivedBox.bottom < (1.0f - VerificationConfig.borderToleranceFraction))
                majorityEnclosed && reasonableArea && !borderTouchingHallucination
            } else {
                detectedStripBox != null && clusteredCells.isEmpty()
            }

            val effectiveStripBox = if (detectedStripBox != null && stripEnclosesCells) {
                detectedStripBox
            } else {
                derivedBox
            }

            // Real stripFullyInFrame computed strictly from geometry (Task 2)
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

    /**
     * Determines whether the strip and all its cavities are fully within the image frame.
     * Evaluates geometric boundaries against configurable edge tolerances.
     *
     * A strip is NOT fully in frame if:
     * 1. No strip box or cell geometry exists.
     * 2. Any individual cell is cut off by the image boundary.
     * 3. The strip box (or cell cluster bounding box) touches or crosses the image border within tolerance.
     */
    internal fun computeStripFullyInFrame(
        stripBox: RectF?,
        cells: List<Cell>,
        tolerance: Float = VerificationConfig.borderToleranceFraction,
        cellCutoffTolerance: Float = VerificationConfig.cellCutoffToleranceFraction
    ): Boolean {
        if (stripBox == null && cells.isEmpty()) return false

        // 1. Cut-off cells at the border count against it
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

        // 2. The bounding box of the validated cells must not touch/cross the borders within tolerance
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

        // 3. The strip box must not touch/cross the original image borders within tolerance
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

        val widths = cells.map { it.box.right - it.box.left }
        val heights = cells.map { it.box.bottom - it.box.top }
        val mw = max(0.01f, widths.sorted()[widths.size / 2])
        val mh = max(0.01f, heights.sorted()[heights.size / 2])

        // Filter out extreme noise boxes before clustering if we have enough cells
        val validSize = cells.filter {
            val wRatio = (it.box.right - it.box.left) / mw
            val hRatio = (it.box.bottom - it.box.top) / mh
            wRatio in VerificationConfig.cellSizeToleranceLow..VerificationConfig.cellSizeToleranceHigh &&
                    hRatio in VerificationConfig.cellSizeToleranceLow..VerificationConfig.cellSizeToleranceHigh
        }
        val target = if (validSize.size >= 3) validSize else cells

        val centers = target.map { cell ->
            val cx = (cell.box.left + cell.box.right) / 2f
            val cy = (cell.box.top + cell.box.bottom) / 2f
            Pair(cx, cy)
        }

        val n = target.size
        val xs = centers.map { it.first }
        val ys = centers.map { it.second }
        val mx = xs.average().toFloat()
        val my = ys.average().toFloat()
        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val angle = 0.5f * kotlin.math.atan2(2f * sxy, sxx - syy)
        val ca = kotlin.math.cos(angle)
        val sa = kotlin.math.sin(angle)

        val maxDim = max(mw, mh)
        val minDim = min(mw, mh)

        val adj = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) {
            for (j in (i + 1) until n) {
                val dx = centers[i].first - centers[j].first
                val dy = centers[i].second - centers[j].second

                // Photo-axis normalized distance
                val dStd = kotlin.math.sqrt((dx / mw) * (dx / mw) + (dy / mh) * (dy / mh))

                // Principal-axis normalized distance
                val drx = dx * ca + dy * sa
                val dry = -dx * sa + dy * ca
                val dRot = kotlin.math.sqrt((drx / maxDim) * (drx / maxDim) + (dry / minDim) * (dry / minDim))

                if (min(dStd, dRot) <= VerificationConfig.maxCellClusterDistance) {
                    adj[i].add(j)
                    adj[j].add(i)
                }
            }
        }

        val visited = BooleanArray(n)
        val components = mutableListOf<List<Cell>>()
        for (i in 0 until n) {
            if (!visited[i]) {
                val comp = mutableListOf<Cell>()
                val queue = ArrayDeque<Int>()
                visited[i] = true
                queue.add(i)
                while (queue.isNotEmpty()) {
                    val curr = queue.removeFirst()
                    comp.add(target[curr])
                    for (nxt in adj[curr]) {
                        if (!visited[nxt]) {
                            visited[nxt] = true
                            queue.add(nxt)
                        }
                    }
                }
                components.add(comp)
            }
        }

        // Return largest component with at least minBlisterCells or highest total confidence
        components.sortByDescending { comp ->
            val hasMin = if (comp.size >= VerificationConfig.minBlisterCells) 1000f else 0f
            hasMin + comp.size * 50f + comp.sumOf { it.confidence.toDouble() }.toFloat()
        }
        return components.firstOrNull() ?: cells
    }

    internal fun deriveStripBox(cells: List<Cell>, marginFraction: Float = 0.0f): RectF? {
        if (cells.isEmpty()) return null
        val cluster = filterOutlierCells(cells)
        val target = if (cluster.isNotEmpty()) cluster else cells
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (cell in target) {
            minX = min(minX, cell.box.left)
            minY = min(minY, cell.box.top)
            maxX = max(maxX, cell.box.right)
            maxY = max(maxY, cell.box.bottom)
        }

        if (minX > maxX || minY > maxY) return null
        val padX = (maxX - minX) * marginFraction
        val padY = (maxY - minY) * marginFraction
        return RectF(
            max(0f, minX - padX),
            max(0f, minY - padY),
            min(1f, maxX + padX),
            min(1f, maxY + padY)
        )
    }

    /**
     * Class-agnostic Non-Maximum Suppression across all cell states.
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

    override fun close() {
        runBlocking {
            mutex.withLock {
                try {
                    interpreter?.close()
                } catch (_: Throwable) {}
                interpreter = null
                try {
                    pillBlisterDetector?.close()
                } catch (_: Throwable) {}
                pillBlisterDetector = null
                initAttempted = false
                modelContractError = null
            }
        }
    }
}
