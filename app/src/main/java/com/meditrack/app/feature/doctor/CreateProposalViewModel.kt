package com.meditrack.app.feature.doctor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.PrescriptionProposalPayload
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.usecase.CreateProposalUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateProposalUiState(
    val patientId: String = "",
    val doctorUid: String = "",
    val doctorName: String = "",
    val medicineName: String = "",
    val dosageText: String = "",
    val pillsPerDose: String = "1",
    val timesOfDay: String = "08:00, 20:00",
    val frequencyType: FrequencyType = FrequencyType.DAILY,
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val instructions: String = "",
    val initialStock: String = "30",
    val isSubmitting: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class CreateProposalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val createProposalUseCase: CreateProposalUseCase,
    private val userPreferences: UserPreferences,
    private val userDao: UserDao
) : ViewModel() {

    private val patientId: String = savedStateHandle.get<String>("patientId") ?: ""

    private val _uiState = MutableStateFlow(CreateProposalUiState(patientId = patientId))
    val uiState: StateFlow<CreateProposalUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val uid = userPreferences.currentUserId.firstOrNull() ?: ""
            val user = userDao.getUserById(uid)
            _uiState.value = _uiState.value.copy(
                doctorUid = uid,
                doctorName = user?.name ?: "Doctor"
            )
        }
    }

    fun onMedicineNameChange(v: String) { _uiState.value = _uiState.value.copy(medicineName = v) }
    fun onDosageTextChange(v: String) { _uiState.value = _uiState.value.copy(dosageText = v) }
    fun onPillsPerDoseChange(v: String) { _uiState.value = _uiState.value.copy(pillsPerDose = v) }
    fun onTimesOfDayChange(v: String) { _uiState.value = _uiState.value.copy(timesOfDay = v) }
    fun onFoodRelationChange(v: FoodRelation) { _uiState.value = _uiState.value.copy(foodRelation = v) }
    fun onInstructionsChange(v: String) { _uiState.value = _uiState.value.copy(instructions = v) }
    fun onInitialStockChange(v: String) { _uiState.value = _uiState.value.copy(initialStock = v) }

    fun submitProposal() {
        val s = _uiState.value
        if (s.medicineName.isBlank()) {
            _uiState.value = s.copy(error = "Medicine name is required.")
            return
        }
        if (s.dosageText.isBlank()) {
            _uiState.value = s.copy(error = "Dosage (e.g. 500mg) is required.")
            return
        }

        viewModelScope.launch {
            _uiState.value = s.copy(isSubmitting = true, error = null)
            val timesList = s.timesOfDay.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val pills = s.pillsPerDose.toIntOrNull() ?: 1
            val stock = s.initialStock.toIntOrNull() ?: 30

            val payload = PrescriptionProposalPayload(
                medicineName = s.medicineName.trim(),
                dosageText = s.dosageText.trim(),
                pillsPerDose = pills,
                frequencyType = s.frequencyType,
                timesOfDay = timesList,
                startDate = System.currentTimeMillis(),
                foodRelation = s.foodRelation,
                instructions = s.instructions.trim(),
                initialStock = stock,
                doctorName = s.doctorName
            )

            val result = createProposalUseCase(
                patientId = s.patientId,
                doctorUid = s.doctorUid,
                doctorName = s.doctorName,
                type = ProposalType.ADD,
                payload = payload
            )

            result.onSuccess {
                _uiState.value = _uiState.value.copy(isSubmitting = false, isSuccess = true)
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = ex.message ?: "Failed to submit prescription proposal."
                )
            }
        }
    }
}
