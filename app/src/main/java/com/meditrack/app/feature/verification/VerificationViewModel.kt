package com.meditrack.app.feature.verification

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.RecordVerifiedDoseUseCase
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
    val isSimulating: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class VerificationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository,
    private val recordVerifiedDoseUseCase: RecordVerifiedDoseUseCase
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
                        isSuccess = dose.status == com.meditrack.app.domain.model.DoseStatus.TAKEN
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
            _uiState.update { it.copy(isSimulating = true, errorMessage = null) }
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
                        medicine = updatedMed
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
}
