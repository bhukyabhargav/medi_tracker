package com.meditrack.app.feature.verification

import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.RecordVerifiedDoseUseCase
import com.meditrack.app.domain.usecase.SkipDoseUseCase
import com.meditrack.app.domain.usecase.SnoozeDoseUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import com.meditrack.app.feature.verification.pipeline.CoordinatorResult
import com.meditrack.app.feature.verification.pipeline.FakeStripDetector
import com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior
import com.meditrack.app.feature.verification.pipeline.VerificationCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VerificationUiState(
    val isLoading: Boolean = true,
    val isAnalyzing: Boolean = false,
    val dose: DoseEvent? = null,
    val medicine: Medicine? = null,
    val needsBaselinePhoto: Boolean = false,
    val isSuccess: Boolean = false,
    val isFailed: Boolean = false,
    val isExhausted: Boolean = false,
    val isSnoozed: Boolean = false,
    val isSkipped: Boolean = false,
    val isSimulating: Boolean = false,
    val rejectReason: RejectReason? = null,
    val attemptsRemaining: Int = AppConfig.MAX_VERIFICATION_ATTEMPTS,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val forcedBehavior: ForceDetectorBehavior = ForceDetectorBehavior.DEFAULT
)

@HiltViewModel
class VerificationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository,
    private val recordVerifiedDoseUseCase: RecordVerifiedDoseUseCase,
    private val snoozeDoseUseCase: SnoozeDoseUseCase,
    private val skipDoseUseCase: SkipDoseUseCase,
    private val alarmScheduler: AlarmScheduler,
    private val verificationCoordinator: VerificationCoordinator,
    private val fakeStripDetector: FakeStripDetector
) : ViewModel() {

    val doseId: String = checkNotNull(savedStateHandle["doseId"])

    private val _uiState = MutableStateFlow(VerificationUiState())
    val uiState: StateFlow<VerificationUiState> = _uiState.asStateFlow()

    init {
        loadDose()
    }

    fun loadDose() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val dose = doseRepository.getById(doseId)
            if (dose != null) {
                val medicine = medicineRepository.getById(dose.medicineId)
                val needsBaseline = medicine?.baselinePhotoPath.isNullOrBlank()
                val attemptsUsed = dose.verificationAttempts
                val remaining = (AppConfig.MAX_VERIFICATION_ATTEMPTS - attemptsUsed).coerceAtLeast(0)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        dose = dose,
                        medicine = medicine,
                        needsBaselinePhoto = needsBaseline,
                        isSuccess = dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.TAKEN_LATE,
                        isSkipped = dose.status == DoseStatus.SKIPPED,
                        isExhausted = dose.status == DoseStatus.UNVERIFIED,
                        attemptsRemaining = remaining
                    )
                }
            } else {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Dose not found: $doseId")
                }
            }
        }
    }

    fun setForcedDetectorBehavior(behavior: ForceDetectorBehavior) {
        fakeStripDetector.behavior = behavior
        FakeStripDetector.debugForcedBehavior = behavior
        _uiState.update { it.copy(forcedBehavior = behavior) }
    }

    fun processCapturedPhoto(photoPath: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, errorMessage = null, infoMessage = null) }
            val bitmap = BitmapFactory.decodeFile(photoPath)
            if (bitmap == null) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        errorMessage = "Failed to load captured image from: $photoPath"
                    )
                }
                return@launch
            }

            val dose = doseRepository.getById(doseId)
            val medicine = dose?.let { medicineRepository.getById(it.medicineId) }
            if (medicine != null) {
                val latestVerified = doseRepository.getLatestVerifiedDose(medicine.id)
                val prevEmpty = latestVerified?.emptyCellsAfter ?: medicine.baselineEmptyCells
                fakeStripDetector.mockPreviousEmptyCellsCount = prevEmpty
                fakeStripDetector.mockEmptyCellsCount = prevEmpty + medicine.pillsPerDose
            }

            val result = verificationCoordinator.verify(
                doseId = doseId,
                photoPath = photoPath,
                bitmap = bitmap
            )

            val updatedDose = doseRepository.getById(doseId)
            val updatedMed = updatedDose?.let { medicineRepository.getById(it.medicineId) }

            when (result) {
                is CoordinatorResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            isSuccess = true,
                            isFailed = false,
                            dose = updatedDose,
                            medicine = updatedMed,
                            infoMessage = result.message
                        )
                    }
                }
                is CoordinatorResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            isFailed = true,
                            rejectReason = result.reason,
                            attemptsRemaining = result.attemptsRemaining,
                            dose = updatedDose,
                            errorMessage = result.friendlyMessage ?: "Verification rejected: ${result.reason}"
                        )
                    }
                }
                is CoordinatorResult.Exhausted -> {
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            isFailed = true,
                            isExhausted = true,
                            rejectReason = result.reason,
                            attemptsRemaining = 0,
                            dose = updatedDose,
                            errorMessage = result.friendlyMessage
                        )
                    }
                }
                is CoordinatorResult.WindowExpired -> {
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            isFailed = true,
                            dose = updatedDose,
                            errorMessage = result.message
                        )
                    }
                }
                is CoordinatorResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun recordBaselineEnrollment(photoPath: String) {
        viewModelScope.launch {
            val med = uiState.value.medicine ?: return@launch
            val updatedMed = med.copy(baselinePhotoPath = photoPath, baselineEmptyCells = 0)
            medicineRepository.update(updatedMed)
            _uiState.update {
                it.copy(
                    medicine = updatedMed,
                    needsBaselinePhoto = false,
                    infoMessage = "Baseline strip enrolled successfully."
                )
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
