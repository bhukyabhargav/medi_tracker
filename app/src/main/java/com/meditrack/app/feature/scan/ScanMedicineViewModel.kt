package com.meditrack.app.feature.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.feature.verification.quality.ImageQualityGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import javax.inject.Inject

data class ScanMedicineUiState(
    val isScanning: Boolean = true,
    val isProcessing: Boolean = false,
    val rawText: String? = null,
    val errorMessage: String? = null,
    // Editable review fields
    val name: String = "",
    val dosageText: String = "",
    val pillsPerDoseText: String = "1",
    val initialStockText: String = "", // Never defaulted to 10
    val frequencyType: FrequencyType? = null, // Never defaulted silently
    val timesOfDay: List<LocalTime> = emptyList(),
    val intervalHoursText: String = "6",
    val daysOfWeek: List<Int> = emptyList(),
    val durationDaysText: String = "",
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val instructions: String = "",
    val isAsNeeded: Boolean = false,
    val requiresManualReview: Boolean = false,
    val reviewReasons: List<String> = emptyList(),
    val isReadyToProceed: Boolean = false
)

@HiltViewModel
class ScanMedicineViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ScanMedicineUiState())
    val uiState: StateFlow<ScanMedicineUiState> = _uiState.asStateFlow()

    private val recognizer by lazy {
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (t: Throwable) {
            android.util.Log.e("ScanMedicineVM", "Failed to initialize ML Kit TextRecognizer: ${t.message}", t)
            null
        }
    }

    fun onCaptureError(message: String) {
        _uiState.update {
            it.copy(
                isProcessing = false,
                errorMessage = message
            )
        }
    }

    fun processBitmap(bitmap: Bitmap) {
        // C5: Double-processing guard
        if (_uiState.value.isProcessing) return

        _uiState.update { it.copy(isProcessing = true, errorMessage = null) }

        viewModelScope.launch(Dispatchers.Default) {
            var preparedBitmap: Bitmap? = null
            try {
                // C3: Downsample large images so longest side is at most 2048 px
                val maxDim = maxOf(bitmap.width, bitmap.height)
                preparedBitmap = if (maxDim > 2048) {
                    val scale = 2048f / maxDim
                    val targetW = (bitmap.width * scale).toInt()
                    val targetH = (bitmap.height * scale).toInt()
                    Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                } else {
                    bitmap
                }

                // F1: Image Quality Gate check before passing to OCR
                val quality = ImageQualityGate.analyze(preparedBitmap)
                if (!quality.passed) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            isScanning = true,
                            errorMessage = quality.friendlyMessage ?: "Image quality check failed. Please retake in good lighting."
                        )
                    }
                    return@launch
                }

                val rec = recognizer
                if (rec == null) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            errorMessage = "OCR engine is currently unavailable on this device. You can enter prescription details manually."
                        )
                    }
                    return@launch
                }

                val image = InputImage.fromBitmap(preparedBitmap, 0)
                rec.process(image)
                    .addOnSuccessListener { visionText ->
                        try {
                            onTextExtracted(visionText.text)
                        } catch (t: Throwable) {
                            android.util.Log.e("ScanMedicineVM", "Failed to parse OCR text: ${t.message}", t)
                            _uiState.update {
                                it.copy(
                                    isProcessing = false,
                                    errorMessage = "Error parsing prescription text. You can enter details manually."
                                )
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                errorMessage = "Failed to recognize text: ${e.localizedMessage ?: "Unknown error"}. You can enter details manually."
                            )
                        }
                    }
            } catch (t: Throwable) {
                android.util.Log.e("ScanMedicineVM", "processBitmap failed: ${t.message}", t)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Error analyzing image: ${t.localizedMessage ?: "OCR processing failed"}. You can enter details manually or pick from gallery."
                    )
                }
            }
        }
    }

    fun processUri(context: Context, uri: Uri) {
        // C5: Double-processing guard
        if (_uiState.value.isProcessing) return

        _uiState.update { it.copy(isProcessing = true, errorMessage = null) }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // C2: Read EXIF orientation and C3: downsample with inSampleSize <= 2048px
                val bitmap = com.meditrack.app.core.util.BitmapLoader.decodeSampled(context, uri, maxSidePx = 1920)
                if (bitmap == null) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            errorMessage = "Could not decode selected image file. Please choose another."
                        )
                    }
                    return@launch
                }

                // F1: Image Quality Gate check
                val quality = ImageQualityGate.analyze(bitmap)
                if (!quality.passed) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            isScanning = true,
                            errorMessage = quality.friendlyMessage ?: "Selected image is blurry, dark, or reflective. Please choose a clearer image."
                        )
                    }
                    return@launch
                }

                val rec = recognizer
                if (rec == null) {
                    _uiState.update { it.copy(isProcessing = false, errorMessage = "OCR engine unavailable.") }
                    return@launch
                }

                val image = InputImage.fromBitmap(bitmap, 0)
                rec.process(image)
                    .addOnSuccessListener { visionText ->
                        onTextExtracted(visionText.text)
                    }
                    .addOnFailureListener { e ->
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                errorMessage = "Failed to recognize text from selected image: ${e.localizedMessage ?: "Unknown error"}"
                            )
                        }
                    }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Error loading image: ${e.localizedMessage ?: "File error"}"
                    )
                }
            }
        }
    }

    private suspend fun decodeSampledAndRotatedBitmap(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // First decode bounds
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return@withContext null

            val maxDimension = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            var sampleSize = 1
            while (maxDimension / sampleSize > 2048) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            // Read EXIF orientation
            val exifOrientation = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (exifOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return@withContext decodedBitmap
            }

            val rotated = Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true)
            if (rotated != decodedBitmap) {
                decodedBitmap.recycle()
            }
            rotated
        } catch (_: Exception) {
            null
        }
    }

    fun onTextExtracted(text: String) {
        val parsed = try {
            PrescriptionParser.parse(text)
        } catch (t: Throwable) {
            android.util.Log.w("ScanMedicineVM", "PrescriptionParser failed: ${t.message}", t)
            ParsedPrescription(rawText = text, requiresManualReview = true, reviewReasons = listOf("Parsing review required"))
        }
        _uiState.update {
            val stockStr = parsed.initialStock?.toString() ?: ""
            // C7: Stop auto-filling 08:00 when parser found no times
            val updated = it.copy(
                isScanning = false,
                isProcessing = false,
                rawText = text,
                name = parsed.medicineName ?: "",
                dosageText = parsed.dosageText ?: "",
                pillsPerDoseText = parsed.pillsPerDose.toString(),
                initialStockText = stockStr,
                frequencyType = parsed.frequencyType,
                timesOfDay = parsed.timesOfDay,
                intervalHoursText = (parsed.intervalHours ?: 6).toString(),
                daysOfWeek = parsed.daysOfWeek ?: emptyList(),
                durationDaysText = parsed.durationDays?.toString() ?: "",
                foodRelation = parsed.foodRelation,
                instructions = parsed.instructions,
                isAsNeeded = parsed.isAsNeeded,
                requiresManualReview = parsed.requiresManualReview,
                reviewReasons = parsed.reviewReasons
            )
            updated.copy(isReadyToProceed = validateReadiness(updated))
        }
    }

    fun onNameChange(name: String) = _uiState.update {
        val s = it.copy(name = name)
        s.copy(isReadyToProceed = validateReadiness(s))
    }

    fun onDosageChange(dosage: String) = _uiState.update {
        val s = it.copy(dosageText = dosage)
        s.copy(isReadyToProceed = validateReadiness(s))
    }

    fun onPillsChange(pills: String) = _uiState.update {
        val s = it.copy(pillsPerDoseText = pills)
        s.copy(isReadyToProceed = validateReadiness(s))
    }

    fun onStockChange(stock: String) = _uiState.update {
        val s = it.copy(initialStockText = stock)
        s.copy(isReadyToProceed = validateReadiness(s))
    }

    fun onFrequencyChange(freq: FrequencyType) = _uiState.update {
        val s = it.copy(frequencyType = freq)
        s.copy(isReadyToProceed = validateReadiness(s))
    }

    fun onIntervalHoursChange(hours: String) = _uiState.update {
        val s = it.copy(intervalHoursText = hours)
        s.copy(isReadyToProceed = validateReadiness(s))
    }

    fun onToggleDay(day: Int) = _uiState.update { state ->
        val updatedDays = if (state.daysOfWeek.contains(day)) {
            state.daysOfWeek - day
        } else {
            (state.daysOfWeek + day).sorted()
        }
        val s = state.copy(daysOfWeek = updatedDays)
        s.copy(isReadyToProceed = validateReadiness(s))
    }

    fun onDurationDaysChange(days: String) = _uiState.update {
        val s = it.copy(durationDaysText = days.filter { ch -> ch.isDigit() })
        s.copy(isReadyToProceed = validateReadiness(s))
    }

    fun onFoodRelationChange(food: FoodRelation) = _uiState.update { it.copy(foodRelation = food) }
    fun onInstructionsChange(instr: String) = _uiState.update { it.copy(instructions = instr) }

    fun addTimeOfDay(time: LocalTime) {
        _uiState.update {
            val updatedTimes = (it.timesOfDay + time).distinct().sorted()
            val s = it.copy(timesOfDay = updatedTimes)
            s.copy(isReadyToProceed = validateReadiness(s))
        }
    }

    fun removeTimeOfDay(time: LocalTime) {
        _uiState.update {
            // C7: Allow empty list, never silently re-add 08:00
            val updatedTimes = it.timesOfDay - time
            val s = it.copy(timesOfDay = updatedTimes)
            s.copy(isReadyToProceed = validateReadiness(s))
        }
    }

    private fun validateReadiness(state: ScanMedicineUiState): Boolean {
        if (state.name.isBlank()) return false
        if (state.dosageText.isBlank()) return false
        val stock = state.initialStockText.toIntOrNull()
        if (stock == null || stock <= 0) return false
        if (state.frequencyType == null) return false
        if (state.frequencyType == FrequencyType.DAILY && state.timesOfDay.isEmpty()) return false
        if (state.frequencyType == FrequencyType.HOURLY) {
            val interval = state.intervalHoursText.toIntOrNull() ?: 0
            if (interval < 1 || interval > 24) return false
        }
        // A1: At least one day selected for SPECIFIC_DAYS
        if (state.frequencyType == FrequencyType.SPECIFIC_DAYS && state.daysOfWeek.isEmpty()) return false
        val pills = state.pillsPerDoseText.toIntOrNull()
        if (pills == null || pills <= 0) return false
        return true
    }

    fun rescan() {
        _uiState.update {
            ScanMedicineUiState(isScanning = true)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            recognizer?.close()
        } catch (_: Exception) {}
    }
}
