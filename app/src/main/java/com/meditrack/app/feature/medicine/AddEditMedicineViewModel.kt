package com.meditrack.app.feature.medicine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.AcceptProposalUseCase
import com.meditrack.app.domain.usecase.MedicineValidator
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
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
    val initialStockText: String = "",
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
    val baselineEmptyCells: Int = 0,
    val isEditing: Boolean = false,
    val editingMedicineId: String? = null,
    val currentStep: AddEditStep = AddEditStep.FORM,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val savedDoses: List<DoseEvent> = emptyList(),
    val acceptProposalId: String? = null,
    val prescribedByDoctorUid: String? = null,
    val prescribedByDoctorName: String? = null
) {
    val pillsPerDose: Int get() = pillsPerDoseText.toIntOrNull() ?: 0
    val initialStock: Int get() = initialStockText.toIntOrNull() ?: 0
    val intervalHours: Int get() = intervalHoursText.toIntOrNull() ?: 6
}

@HiltViewModel
class AddEditMedicineViewModel @Inject constructor(
    private val medicineRepository: MedicineRepository,
    private val saveMedicineUseCase: SaveMedicineUseCase,
    private val userPreferences: UserPreferences,
    val stripDetector: com.meditrack.app.feature.verification.pipeline.StripDetector? = null,
    private val acceptProposalUseCase: AcceptProposalUseCase? = null,
    private val doctorCloud: com.meditrack.app.feature.doctor.DoctorCloud? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditMedicineUiState())
    val uiState: StateFlow<AddEditMedicineUiState> = _uiState.asStateFlow()

    // C8: Track whether prefill was already consumed to prevent rotation/re-render overwriting user edits
    private var prefillConsumed: Boolean = false

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

    fun prefillFromScan(
        name: String?,
        dosage: String?,
        pillsPerDose: String?,
        initialStock: String?,
        frequency: String?,
        times: List<String>?,
        intervalHours: String?,
        daysOfWeek: String? = null,
        durationDays: String? = null,
        foodRelation: String?,
        instructions: String?,
        acceptProposalId: String? = null,
        prescribedByDoctorUid: String? = null,
        prescribedByDoctorName: String? = null
    ) {
        if (prefillConsumed) return
        prefillConsumed = true

        _uiState.update { state ->
            val freqType = try {
                if (!frequency.isNullOrBlank()) FrequencyType.valueOf(frequency) else state.frequencyType
            } catch (_: Exception) {
                state.frequencyType
            }
            val food = try {
                if (!foodRelation.isNullOrBlank()) FoodRelation.valueOf(foodRelation) else state.foodRelation
            } catch (_: Exception) {
                state.foodRelation
            }
            val parsedTimes = times?.mapNotNull {
                try {
                    val parts = it.split(":")
                    LocalTime.of(parts[0].toInt(), parts[1].toInt())
                } catch (_: Exception) {
                    null
                }
            }

            val parsedDays = daysOfWeek?.split(",")?.mapNotNull { it.trim().toIntOrNull() }

            val newEndDate = if (!durationDays.isNullOrBlank()) {
                val d = durationDays.toIntOrNull()
                if (d != null && d > 0) {
                    try {
                        val startLocalDate = Instant.ofEpochMilli(state.startDate).atZone(ZoneId.systemDefault()).toLocalDate()
                        val endLocalDate = startLocalDate.plusDays(d.toLong())
                        endLocalDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (_: Exception) {
                        state.endDate
                    }
                } else {
                    state.endDate
                }
            } else {
                state.endDate
            }

            state.copy(
                name = name ?: state.name,
                dosageText = dosage ?: state.dosageText,
                pillsPerDoseText = pillsPerDose ?: state.pillsPerDoseText,
                initialStockText = initialStock ?: state.initialStockText,
                frequencyType = freqType,
                timesOfDay = if (!parsedTimes.isNullOrEmpty()) parsedTimes else state.timesOfDay,
                intervalHoursText = intervalHours ?: state.intervalHoursText,
                daysOfWeek = if (!parsedDays.isNullOrEmpty()) parsedDays else state.daysOfWeek,
                endDate = newEndDate,
                foodRelation = food,
                instructions = instructions ?: state.instructions,
                acceptProposalId = acceptProposalId ?: state.acceptProposalId,
                prescribedByDoctorUid = prescribedByDoctorUid ?: state.prescribedByDoctorUid,
                prescribedByDoctorName = prescribedByDoctorName ?: state.prescribedByDoctorName
            )
        }
    }

    fun onNameChange(name: String) = _uiState.update { it.copy(name = name, errorMessage = null) }
    fun onDosageChange(dosage: String) = _uiState.update { it.copy(dosageText = dosage, errorMessage = null) }
    fun onPillsPerDoseChange(pills: String) = _uiState.update { it.copy(pillsPerDoseText = pills, errorMessage = null) }
    fun onInitialStockChange(stock: String) = _uiState.update { it.copy(initialStockText = stock, errorMessage = null) }
    fun onFrequencyTypeChange(freq: FrequencyType) = _uiState.update { it.copy(frequencyType = freq, errorMessage = null) }
    fun onIntervalHoursChange(hours: String) = _uiState.update { it.copy(intervalHoursText = hours, errorMessage = null) }
    fun onStartDateChange(date: Long) = _uiState.update { it.copy(startDate = date, errorMessage = null) }
    fun onEndDateChange(date: Long?) = _uiState.update { it.copy(endDate = date, errorMessage = null) }
    fun onFoodRelationChange(food: FoodRelation) = _uiState.update { it.copy(foodRelation = food, errorMessage = null) }
    fun onInstructionsChange(instr: String) = _uiState.update { it.copy(instructions = instr, errorMessage = null) }
    fun onReminderEnabledChange(enabled: Boolean) = _uiState.update { it.copy(reminderEnabled = enabled, errorMessage = null) }

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

    fun onPhotoCapturedWithLedger(photoPath: String, baselineEmpty: Int) {
        _uiState.update { it.copy(baselinePhotoPath = photoPath, baselineEmptyCells = baselineEmpty) }
    }

    fun onSkipPhoto() {
        // A doctor's prescription is only activated with the patient's own baseline blister photo.
        if (!_uiState.value.acceptProposalId.isNullOrBlank()) {
            _uiState.update {
                it.copy(errorMessage = "A baseline blister strip photo is required to accept a doctor's prescription.")
            }
            return
        }
        _uiState.update { it.copy(baselinePhotoPath = null) }
        saveMedicine()
    }

    fun onConfirmPhotoAndSave() {
        if (!_uiState.value.acceptProposalId.isNullOrBlank() && _uiState.value.baselinePhotoPath.isNullOrBlank()) {
            _uiState.update {
                it.copy(errorMessage = "A baseline blister strip photo is required to accept a doctor's prescription.")
            }
            return
        }
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
                val currentUserId = userPreferences.currentUserId.firstOrNull()
                if (currentUserId.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "No active user session. Please sign in to save medicines."
                        )
                    }
                    return@launch
                }

                // A proposal that was declined, replaced, or belongs to someone else must not create a medicine.
                if (!state.acceptProposalId.isNullOrBlank() && acceptProposalUseCase != null &&
                    !acceptProposalUseCase.canAccept(state.acceptProposalId)
                ) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "This prescription proposal can no longer be accepted. Please go back and check with your doctor."
                        )
                    }
                    return@launch
                }

                val now = System.currentTimeMillis()
                val existing = state.editingMedicineId?.let { medicineRepository.getById(it) }

                val medicine = if (existing != null) {
                    existing.copy(
                        patientId = if (existing.patientId.isBlank()) currentUserId else existing.patientId,
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
                        baselineEmptyCells = if (state.baselinePhotoPath != null && state.baselinePhotoPath != existing.baselinePhotoPath) {
                            state.baselineEmptyCells
                        } else {
                            existing.baselineEmptyCells
                        },
                        baselinePhotoPath = state.baselinePhotoPath ?: existing.baselinePhotoPath,
                        lowStockNotified = existing.lowStockNotified,
                        updatedAt = now
                    )
                } else {
                    Medicine(
                        id = if (!state.acceptProposalId.isNullOrBlank()) "med_${state.acceptProposalId}" else "med_${UUID.randomUUID()}",
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
                        baselineEmptyCells = state.baselineEmptyCells,
                        baselinePhotoPath = state.baselinePhotoPath,
                        prescribedByDoctorUid = state.prescribedByDoctorUid,
                        prescribedByDoctorName = state.prescribedByDoctorName,
                        stoppedByDoctor = false,
                        isDeleted = false,
                        createdAt = now,
                        updatedAt = now
                    )
                }

                val generatedDoses = if (state.isEditing) {
                    saveMedicineUseCase.updateMedicine(medicine)
                } else {
                    saveMedicineUseCase.saveNewMedicine(medicine)
                }

                if (!state.acceptProposalId.isNullOrBlank()) {
                    val accepted = acceptProposalUseCase?.markProposalAccepted(state.acceptProposalId, medicine.id) == true
                    // Tell the doctor's side too, so the proposal does not stay "pending" for them.
                    if (accepted) {
                        try {
                            if (doctorCloud?.isAvailable == true) {
                                doctorCloud.updateProposal(
                                    state.acceptProposalId,
                                    mapOf(
                                        "status" to com.meditrack.app.domain.model.ProposalStatus.ACCEPTED.name,
                                        "medicineId" to medicine.id,
                                        "resolvedAt" to now
                                    )
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
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
    override fun onCleared() {
        super.onCleared()
        if (stripDetector is java.io.Closeable) {
            try {
                stripDetector.close()
            } catch (_: Throwable) {}
        }
    }

    fun deleteMedicine(onDeleted: () -> Unit) {
        val medicineId = _uiState.value.editingMedicineId ?: return
        viewModelScope.launch {
            try {
                saveMedicineUseCase.deleteMedicine(medicineId)
                onDeleted()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to delete medicine: ${e.message}")
                }
            }
        }
    }
}
