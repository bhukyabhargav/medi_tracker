package com.meditrack.app.feature.medicine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.MedicineValidator
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

enum class AddEditStep {
    FORM,
    ENROLLMENT_PHOTO,
    SUCCESS
}

data class AddEditMedicineUiState(
    val name: String = "",
    val dosageText: String = "",
    val pillsPerDoseText: String = "1",
    val initialStockText: String = "10",
    val frequencyType: FrequencyType = FrequencyType.DAILY,
    val timesOfDay: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    val intervalHoursText: String = "6",
    val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val startDate: Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    val endDate: Long? = null,
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val instructions: String = "",
    val reminderEnabled: Boolean = true,
    val baselinePhotoPath: String? = null,
    val isEditing: Boolean = false,
    val editingMedicineId: String? = null,
    val currentStep: AddEditStep = AddEditStep.FORM,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val savedDoses: List<DoseEvent> = emptyList()
) {
    val pillsPerDose: Int get() = pillsPerDoseText.toIntOrNull() ?: 0
    val initialStock: Int get() = initialStockText.toIntOrNull() ?: 0
    val intervalHours: Int get() = intervalHoursText.toIntOrNull() ?: 6
}

@HiltViewModel
class AddEditMedicineViewModel @Inject constructor(
    private val medicineRepository: MedicineRepository,
    private val saveMedicineUseCase: SaveMedicineUseCase,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditMedicineUiState())
    val uiState: StateFlow<AddEditMedicineUiState> = _uiState.asStateFlow()

    fun loadMedicine(medicineId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val medicine = medicineRepository.getById(medicineId)
            if (medicine != null) {
                _uiState.update {
                    it.copy(
                        name = medicine.name,
                        dosageText = medicine.dosageText,
                        pillsPerDoseText = medicine.pillsPerDose.toString(),
                        initialStockText = medicine.initialStock.toString(),
                        frequencyType = medicine.frequencyType,
                        timesOfDay = medicine.timesOfDay.ifEmpty { listOf(LocalTime.of(8, 0)) },
                        intervalHoursText = (medicine.intervalHours ?: 6).toString(),
                        daysOfWeek = medicine.daysOfWeek ?: listOf(1, 2, 3, 4, 5, 6, 7),
                        startDate = medicine.startDate,
                        endDate = medicine.endDate,
                        foodRelation = medicine.foodRelation,
                        instructions = medicine.instructions,
                        reminderEnabled = medicine.reminderEnabled,
                        baselinePhotoPath = medicine.baselinePhotoPath,
                        isEditing = true,
                        editingMedicineId = medicine.id,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Medicine not found") }
            }
        }
    }

    fun onNameChange(name: String) = _uiState.update { it.copy(name = name, errorMessage = null) }
    fun onDosageChange(dosage: String) = _uiState.update { it.copy(dosageText = dosage, errorMessage = null) }
    fun onPillsPerDoseTextChange(pills: String) = _uiState.update { it.copy(pillsPerDoseText = pills, errorMessage = null) }
    fun onInitialStockTextChange(stock: String) = _uiState.update { it.copy(initialStockText = stock, errorMessage = null) }
    fun onFrequencyTypeChange(freq: FrequencyType) = _uiState.update { it.copy(frequencyType = freq, errorMessage = null) }
    fun onIntervalHoursTextChange(hours: String) = _uiState.update { it.copy(intervalHoursText = hours, errorMessage = null) }
    fun onStartDateChange(date: Long) = _uiState.update { it.copy(startDate = date, errorMessage = null) }
    fun onEndDateChange(date: Long?) = _uiState.update { it.copy(endDate = date, errorMessage = null) }
    fun onFoodRelationChange(food: FoodRelation) = _uiState.update { it.copy(foodRelation = food, errorMessage = null) }
    fun onInstructionsChange(instr: String) = _uiState.update { it.copy(instructions = instr, errorMessage = null) }
    fun onReminderToggle(enabled: Boolean) = _uiState.update { it.copy(reminderEnabled = enabled, errorMessage = null) }

    // Backward-compatibility aliases
    fun onPillsPerDoseChange(pills: String) = onPillsPerDoseTextChange(pills)
    fun onInitialStockChange(stock: String) = onInitialStockTextChange(stock)
    fun onIntervalHoursChange(hours: String) = onIntervalHoursTextChange(hours)
    fun onReminderEnabledChange(enabled: Boolean) = onReminderToggle(enabled)

    fun deleteMedicine(onDeleted: () -> Unit = {}) {
        val medicineId = _uiState.value.editingMedicineId ?: return
        viewModelScope.launch {
            saveMedicineUseCase.deleteMedicine(medicineId)
            onDeleted()
        }
    }

    fun addTimeOfDay(time: LocalTime) {
        _uiState.update {
            val updatedTimes = (it.timesOfDay + time).distinct().sorted()
            it.copy(timesOfDay = updatedTimes, errorMessage = null)
        }
    }

    fun removeTimeOfDay(time: LocalTime) {
        _uiState.update {
            val updatedTimes = it.timesOfDay - time
            it.copy(timesOfDay = updatedTimes, errorMessage = null)
        }
    }

    fun toggleDayOfWeek(day: Int) {
        _uiState.update { state ->
            val days = state.daysOfWeek.toMutableList()
            if (days.contains(day)) {
                if (days.size > 1) days.remove(day)
            } else {
                days.add(day)
                days.sort()
            }
            state.copy(daysOfWeek = days, errorMessage = null)
        }
    }

    fun validateForm(): Boolean {
        val state = _uiState.value
        val result = MedicineValidator.validate(
            name = state.name,
            pillsPerDose = state.pillsPerDose,
            startDate = state.startDate,
            endDate = state.endDate,
            reminderEnabled = state.reminderEnabled,
            timesOfDay = state.timesOfDay,
            initialStock = state.initialStock,
            frequencyType = state.frequencyType,
            intervalHours = state.intervalHours,
            daysOfWeek = state.daysOfWeek
        )
        return when (result) {
            is MedicineValidator.ValidationResult.Valid -> {
                _uiState.update { it.copy(errorMessage = null) }
                true
            }
            is MedicineValidator.ValidationResult.Invalid -> {
                _uiState.update { it.copy(errorMessage = result.errorMessage) }
                false
            }
        }
    }

    fun onContinueToPhoto() {
        if (validateForm()) {
            _uiState.update { it.copy(currentStep = AddEditStep.ENROLLMENT_PHOTO) }
        }
    }

    fun onBackToForm() {
        _uiState.update { it.copy(currentStep = AddEditStep.FORM) }
    }

    fun onPhotoCaptured(photoPath: String) {
        _uiState.update { it.copy(baselinePhotoPath = photoPath) }
    }

    fun onSkipPhoto() {
        _uiState.update { it.copy(baselinePhotoPath = null) }
        saveMedicine()
    }

    fun onConfirmPhotoAndSave() {
        saveMedicine()
    }

    fun saveDirectlyIfEditing() {
        if (validateForm()) {
            saveMedicine()
        }
    }

    private fun saveMedicine() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val now = System.currentTimeMillis()
                val currentUserId = userPreferences.currentUserId.firstOrNull() ?: ""
                val existing = state.editingMedicineId?.let { medicineRepository.getById(it) }

                val medicine = if (existing != null) {
                    existing.copy(
                        name = state.name.trim(),
                        dosageText = state.dosageText.trim(),
                        pillsPerDose = state.pillsPerDose,
                        frequencyType = state.frequencyType,
                        timesOfDay = state.timesOfDay,
                        intervalHours = if (state.frequencyType == FrequencyType.HOURLY) state.intervalHours else null,
                        daysOfWeek = if (state.frequencyType == FrequencyType.SPECIFIC_DAYS) state.daysOfWeek else null,
                        startDate = state.startDate,
                        endDate = state.endDate,
                        foodRelation = state.foodRelation,
                        instructions = state.instructions.trim(),
                        reminderEnabled = state.reminderEnabled,
                        initialStock = state.initialStock,
                        // PRESERVE historical state:
                        currentStock = existing.currentStock,
                        baselineEmptyCells = existing.baselineEmptyCells,
                        baselinePhotoPath = state.baselinePhotoPath ?: existing.baselinePhotoPath,
                        lowStockNotified = existing.lowStockNotified,
                        updatedAt = now
                    )
                } else {
                    Medicine(
                        id = "med_${UUID.randomUUID()}",
                        patientId = currentUserId,
                        name = state.name.trim(),
                        dosageText = state.dosageText.trim(),
                        pillsPerDose = state.pillsPerDose,
                        frequencyType = state.frequencyType,
                        timesOfDay = state.timesOfDay,
                        intervalHours = if (state.frequencyType == FrequencyType.HOURLY) state.intervalHours else null,
                        daysOfWeek = if (state.frequencyType == FrequencyType.SPECIFIC_DAYS) state.daysOfWeek else null,
                        startDate = state.startDate,
                        endDate = state.endDate,
                        foodRelation = state.foodRelation,
                        instructions = state.instructions.trim(),
                        reminderEnabled = state.reminderEnabled,
                        initialStock = state.initialStock,
                        currentStock = state.initialStock,
                        baselineEmptyCells = 0,
                        baselinePhotoPath = state.baselinePhotoPath,
                        createdAt = now,
                        updatedAt = now
                    )
                }

                val generatedDoses = if (state.isEditing) {
                    saveMedicineUseCase.updateMedicine(medicine)
                } else {
                    saveMedicineUseCase.saveNewMedicine(medicine)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        savedDoses = generatedDoses,
                        currentStep = AddEditStep.SUCCESS
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to save medicine: ${e.message}"
                    )
                }
            }
        }
    }
}
