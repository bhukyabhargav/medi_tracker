package com.meditrack.app.feature.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.core.util.BitmapLoader
import com.meditrack.app.feature.verification.pipeline.Cell
import com.meditrack.app.feature.verification.pipeline.CellState
import com.meditrack.app.feature.verification.pipeline.TfliteStripDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

data class SelfTestCellDetail(
    val state: CellState,
    val confidence: Float,
    val box: RectF
)

data class SelfTestImageResult(
    val fileName: String,
    val stripDetected: Boolean,
    val stripConfidence: Float?,
    val totalCells: Int,
    val fullCells: Int,
    val emptyCells: Int,
    val partialCells: Int,
    val averageCellConfidence: Float,
    val latencyMs: Long,
    val isSynthetic: Boolean = false,
    val error: String? = null,
    val cells: List<SelfTestCellDetail> = emptyList()
)

data class ModelSelfTestUiState(
    val isRunning: Boolean = false,
    val hasRun: Boolean = false,
    val assetImagesFound: List<String> = emptyList(),
    val results: List<SelfTestImageResult> = emptyList(),
    val summaryText: String = "",
    val errorMessage: String? = null
)

@HiltViewModel
class ModelSelfTestViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detector: TfliteStripDetector
) : ViewModel() {

    internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val _uiState = MutableStateFlow(ModelSelfTestUiState())
    val uiState: StateFlow<ModelSelfTestUiState> = _uiState.asStateFlow()

    init {
        scanAssets()
    }

    fun scanAssets() {
        try {
            val files = context.assets.list("selftest")
                ?.filter { it.endsWith(".jpg", ignoreCase = true) || it.endsWith(".jpeg", ignoreCase = true) || it.endsWith(".png", ignoreCase = true) }
                ?: emptyList()
            _uiState.update { it.copy(assetImagesFound = files) }
        } catch (e: Exception) {
            _uiState.update { it.copy(assetImagesFound = emptyList()) }
        }
    }

    fun runSelfTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, errorMessage = null) }

            withContext(defaultDispatcher) {
                try {
                    scanAssets()
                    val files = _uiState.value.assetImagesFound

                    if (files.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                isRunning = false,
                                hasRun = true,
                                results = emptyList(),
                                summaryText = "No test images found in assets/selftest/.\n\n" +
                                        "Real-world blister accuracy is unproven in the public dataset. " +
                                        "Place gold-standard blister photos (.jpg/.png) in app/src/main/assets/selftest/ " +
                                        "to test strip and cavity detection on device hardware."
                            )
                        }
                        return@withContext
                    }

                    val results = mutableListOf<SelfTestImageResult>()

                    for (file in files) {
                        try {
                            val bytes = context.assets.open("selftest/$file").use { it.readBytes() }
                            val bitmap = BitmapLoader.decodeSampled(bytes, maxSidePx = 1920)

                            if (bitmap == null) {
                                results.add(
                                    SelfTestImageResult(
                                        fileName = file,
                                        stripDetected = false,
                                        stripConfidence = null,
                                        totalCells = 0,
                                        fullCells = 0,
                                        emptyCells = 0,
                                        partialCells = 0,
                                        averageCellConfidence = 0f,
                                        latencyMs = 0,
                                        error = "Failed to decode image bitmap"
                                    )
                                )
                                continue
                            }

                            val startTime = SystemClock.elapsedRealtime()
                            val detection = detector.detect(bitmap)
                            val elapsed = SystemClock.elapsedRealtime() - startTime

                            bitmap.recycle()

                            val fullCount = detection.cells.count { it.state == CellState.FULL }
                            val emptyCount = detection.cells.count { it.state == CellState.EMPTY }
                            val partialCount = detection.cells.count { it.state == CellState.PARTIAL }
                            val avgConf = if (detection.cells.isNotEmpty()) {
                                detection.cells.map { it.confidence }.average().toFloat()
                            } else 0f

                            val cellDetails = detection.cells.map {
                                SelfTestCellDetail(it.state, it.confidence, it.box)
                            }

                            results.add(
                                SelfTestImageResult(
                                    fileName = file,
                                    stripDetected = detection.stripFullyInFrame || (detection.stripConfidence ?: 0f) > 0.4f,
                                    stripConfidence = detection.stripConfidence,
                                    totalCells = detection.cells.size,
                                    fullCells = fullCount,
                                    emptyCells = emptyCount,
                                    partialCells = partialCount,
                                    averageCellConfidence = avgConf,
                                    latencyMs = elapsed,
                                    cells = cellDetails
                                )
                            )
                        } catch (t: Throwable) {
                            results.add(
                                SelfTestImageResult(
                                    fileName = file,
                                    stripDetected = false,
                                    stripConfidence = null,
                                    totalCells = 0,
                                    fullCells = 0,
                                    emptyCells = 0,
                                    partialCells = 0,
                                    averageCellConfidence = 0f,
                                    latencyMs = 0,
                                    error = t.message ?: "Inference error"
                                )
                            )
                        }
                    }

                    val totalImages = results.size
                    val totalCells = results.sumOf { it.totalCells }
                    val avgLatency = if (results.isNotEmpty()) results.map { it.latencyMs }.average().toLong() else 0L

                    val summary = "Evaluated $totalImages asset image(s).\n" +
                            "Total cavities detected: $totalCells\n" +
                            "Average inference latency: ${avgLatency}ms"

                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            hasRun = true,
                            results = results,
                            summaryText = summary
                        )
                    }
                } catch (t: Throwable) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            errorMessage = "Self-test execution failed: ${t.message}"
                        )
                    }
                }
            }
        }
    }

    fun runSyntheticSelfTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, errorMessage = null) }

            withContext(defaultDispatcher) {
                try {
                    // Create a synthetic 320x320 bitmap simulating a blister strip layout
                    val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.LTGRAY)

                    val paint = Paint().apply {
                        color = Color.WHITE
                        isAntiAlias = true
                    }
                    val borderPaint = Paint().apply {
                        color = Color.DKGRAY
                        style = Paint.Style.STROKE
                        strokeWidth = 3f
                    }

                    // Draw strip rectangle
                    canvas.drawRoundRect(30f, 60f, 290f, 260f, 16f, 16f, paint)
                    canvas.drawRoundRect(30f, 60f, 290f, 260f, 16f, 16f, borderPaint)

                    // Draw 4 circular cavities
                    val cavityPaint = Paint().apply {
                        color = Color.BLUE
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(80f, 110f, 25f, cavityPaint)
                    canvas.drawCircle(240f, 110f, 25f, cavityPaint)
                    canvas.drawCircle(80f, 210f, 25f, cavityPaint)
                    canvas.drawCircle(240f, 210f, 25f, cavityPaint)

                    val startTime = SystemClock.elapsedRealtime()
                    val detection = detector.detect(bitmap)
                    val elapsed = SystemClock.elapsedRealtime() - startTime

                    bitmap.recycle()

                    val fullCount = detection.cells.count { it.state == CellState.FULL }
                    val emptyCount = detection.cells.count { it.state == CellState.EMPTY }
                    val partialCount = detection.cells.count { it.state == CellState.PARTIAL }
                    val avgConf = if (detection.cells.isNotEmpty()) {
                        detection.cells.map { it.confidence }.average().toFloat()
                    } else 0f

                    val syntheticResult = SelfTestImageResult(
                        fileName = "synthetic_blister_strip_320x320",
                        stripDetected = detection.stripFullyInFrame || (detection.stripConfidence ?: 0f) > 0.4f,
                        stripConfidence = detection.stripConfidence,
                        totalCells = detection.cells.size,
                        fullCells = fullCount,
                        emptyCells = emptyCount,
                        partialCells = partialCount,
                        averageCellConfidence = avgConf,
                        latencyMs = elapsed,
                        isSynthetic = true,
                        cells = detection.cells.map { SelfTestCellDetail(it.state, it.confidence, it.box) }
                    )

                    val summary = "Synthetic model pipeline test completed in ${elapsed}ms.\n" +
                            "Model Interpreter is verified responsive and functional."

                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            hasRun = true,
                            results = listOf(syntheticResult),
                            summaryText = summary
                        )
                    }
                } catch (t: Throwable) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            errorMessage = "Synthetic test failed: ${t.message}"
                        )
                    }
                }
            }
        }
    }
}
