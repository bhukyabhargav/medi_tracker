package com.meditrack.app.feature.verification

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.RecordVerifiedDoseUseCase
import com.meditrack.app.domain.usecase.SkipDoseUseCase
import com.meditrack.app.domain.usecase.SnoozeDoseUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VerificationUiState(
    val isLoading: Boolean = true,
    val dose: DoseEvent? = null,
    val medicine: Medicine? = null,
    val isSuccess: Boolean = false,
    val isSnoozed: Boolean = false,
    val isSkipped: Boolean = false,
    val isSimulating: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

@HiltViewModel
class VerificationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository,
    private val recordVerifiedDoseUseCase: RecordVerifiedDoseUseCase,
    private val snoozeDoseUseCase: SnoozeDoseUseCase,
    private val skipDoseUseCase: SkipDoseUseCase,
    private val alarmScheduler: AlarmScheduler
) : ViewModel() {

    val doseId: String = checkNotNull(savedStateHandle["doseId"])

    private val _uiState = MutableStateFlow(VerificationUiState())
    val uiState: StateFlow<VerificationUiState> = _uiState.asStateFlow()

    init {
        loadDose()
    }

    private fun loadDose() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val dose = doseRepository.getById(doseId)
            if (dose != null) {
                val medicine = medicineRepository.getById(dose.medicineId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        dose = dose,
                        medicine = medicine,
                        isSuccess = dose.status == DoseStatus.TAKEN,
                        isSkipped = dose.status == DoseStatus.SKIPPED
                    )
                }
            } else {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Dose not found: $doseId")
                }
            }
        }
    }

    fun simulateVerifiedTake() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSimulating = true, errorMessage = null, infoMessage = null) }
            val result = recordVerifiedDoseUseCase(
                doseId = doseId,
                photoPath = "simulated_verification.jpg",
                emptyCellsAfter = 1
            )
            result.onSuccess {
                val updatedDose = doseRepository.getById(doseId)
                val updatedMed = updatedDose?.let { medicineRepository.getById(it.medicineId) }
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        isSuccess = true,
                        dose = updatedDose,
                        medicine = updatedMed,
                        infoMessage = "Dose verified as TAKEN. Stock updated."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        errorMessage = error.message ?: "Failed to verify dose"
                    )
                }
            }
        }
    }

    fun snooze(minutes: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSimulating = true, errorMessage = null, infoMessage = null) }
            val result = snoozeDoseUseCase(doseId, snoozeMinutes = minutes)
            result.onSuccess { nextTrigger ->
                val updatedDose = doseRepository.getById(doseId)
                if (updatedDose != null) {
                    alarmScheduler.scheduleDoseAlarm(updatedDose, nextTrigger)
                }
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        isSnoozed = true,
                        dose = updatedDose,
                        infoMessage = "Dose snoozed for $minutes minutes."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        errorMessage = error.message ?: "Failed to snooze dose"
                    )
                }
            }
        }
    }

    fun skip() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSimulating = true, errorMessage = null, infoMessage = null) }
            val result = skipDoseUseCase(doseId)
            result.onSuccess {
                alarmScheduler.cancelDoseAlarm(doseId)
                val updatedDose = doseRepository.getById(doseId)
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        isSkipped = true,
                        dose = updatedDose,
                        infoMessage = "Dose marked as SKIPPED."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        errorMessage = error.message ?: "Failed to skip dose"
                    )
                }
            }
        }
    }
}
