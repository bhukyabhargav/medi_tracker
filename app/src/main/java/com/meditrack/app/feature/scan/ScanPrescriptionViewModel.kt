package com.meditrack.app.feature.scan

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.feature.verification.quality.ImageQualityGate
import com.meditrack.app.feature.verification.quality.QualityResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

data class ScanPrescriptionUiState(
    val isProcessing: Boolean = false,
    val showReviewDialog: Boolean = false,
    val qualityFailure: QualityResult? = null,
    val errorMessage: String? = null,
    // Editable extracted fields
    val medicineName: String = "",
    val dosageText: String = "",
    val frequencyType: FrequencyType = FrequencyType.DAILY,
    val timesOfDay: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    val pillsPerDoseText: String = "1",
    val intervalHours: Int? = null,
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val instructions: String = "",
    val rawOcrText: String = ""
) {
    val pillsPerDose: Int get() = pillsPerDoseText.toIntOrNull()?.coerceAtLeast(1) ?: 1
}

@HiltViewModel
class ScanPrescriptionViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ScanPrescriptionUiState())
    val uiState: StateFlow<ScanPrescriptionUiState> = _uiState.asStateFlow()

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun onCapturedBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
            try {
                // Quality gate check (blur, brightness, glare)
                val qualityResult = ImageQualityGate.analyze(bitmap)
                if (!qualityResult.passed) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            qualityFailure = qualityResult
                        )
                    }
                    return@launch
                }

                processOcr(bitmap)
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Image analysis failed: ${t.message ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun onGalleryImageSelected(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
            // Gallery prescription images go directly to OCR
            processOcr(bitmap)
        }
    }

    fun processOcr(bitmap: Bitmap) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    processOcrText(visionText.text)
                }
                .addOnFailureListener { error ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            errorMessage = "Failed to recognize text: ${error.localizedMessage ?: "Unknown error"}"
                        )
                    }
                }
        } catch (t: Throwable) {
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    errorMessage = "Error initializing text recognition: ${t.message ?: "Out of memory"}"
                )
            }
        }
    }

    fun processOcrText(text: String) {
        val parsed = PrescriptionParser.parse(text)
        _uiState.update {
            it.copy(
                isProcessing = false,
                showReviewDialog = true,
                medicineName = parsed.medicineName ?: "",
                dosageText = parsed.dosageText ?: "",
                frequencyType = parsed.frequencyType,
                timesOfDay = parsed.timesOfDay,
                pillsPerDoseText = parsed.pillsPerDose.toString(),
                intervalHours = parsed.intervalHours,
                foodRelation = parsed.foodRelation,
                instructions = parsed.instructions,
                rawOcrText = text
            )
        }
    }

    fun onNameChange(name: String) = _uiState.update { it.copy(medicineName = name) }
    fun onDosageChange(dosage: String) = _uiState.update { it.copy(dosageText = dosage) }
    fun onFrequencyTypeChange(freq: FrequencyType) = _uiState.update { it.copy(frequencyType = freq) }
    fun onPillsPerDoseTextChange(pills: String) = _uiState.update { it.copy(pillsPerDoseText = pills) }
    fun onIntervalHoursChange(hours: Int?) = _uiState.update { it.copy(intervalHours = hours) }
    fun onFoodRelationChange(food: FoodRelation) = _uiState.update { it.copy(foodRelation = food) }
    fun onInstructionsChange(inst: String) = _uiState.update { it.copy(instructions = inst) }

    fun dismissReviewDialog() = _uiState.update { it.copy(showReviewDialog = false) }
    fun dismissQualityFailure() = _uiState.update { it.copy(qualityFailure = null) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    override fun onCleared() {
        super.onCleared()
        try {
            textRecognizer.close()
        } catch (_: Throwable) {
            // Safe cleanup
        }
    }
}
