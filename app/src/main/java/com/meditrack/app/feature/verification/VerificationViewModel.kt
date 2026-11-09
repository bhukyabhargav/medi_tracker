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
import com.meditrack.app.domain.usecase.LateEntryPolicy
import com.meditrack.app.domain.usecase.RecordManualUnverifiedUseCase
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
    val isLateMode: Boolean = false,
    val canLogLate: Boolean = true,
    val isLocked: Boolean = false,
    val showDoubleDoseWarning: Boolean = false,
    val isWithinDoubleDoseGap: Boolean = false,
    val doubleDoseConfirmed: Boolean = false,
    val isManualUnverified: Boolean = false,
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
    private val recordManualUnverifiedUseCase: RecordManualUnverifiedUseCase,
    private val snoozeDoseUseCase: SnoozeDoseUseCase,
    private val skipDoseUseCase: SkipDoseUseCase,
    private val alarmScheduler: AlarmScheduler,
    private val verificationCoordinator: VerificationCoordinator,
    private val fakeStripDetector: FakeStripDetector,
    private val stripDetector: com.meditrack.app.feature.verification.pipeline.StripDetector
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

                val now = System.currentTimeMillis()
                val isMissed = dose.status == DoseStatus.MISSED
                val nextDose = doseRepository.getNextScheduledDose(dose.medicineId, dose.scheduledAt)
                val canLate = if (isMissed) LateEntryPolicy.canLogLate(dose, nextDose?.scheduledAt, now) else true
                val isLocked = isMissed && !canLate
                val isWithinSafetyGap = if (isMissed && nextDose != null) {
                    LateEntryPolicy.isWithinDoubleDoseSafetyGap(dose.scheduledAt, nextDose.scheduledAt, now)
                } else {
                    false
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        dose = dose,
                        medicine = medicine,
                        needsBaselinePhoto = needsBaseline,
                        isSuccess = dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.TAKEN_LATE,
                        isSkipped = dose.status == DoseStatus.SKIPPED,
                        isExhausted = dose.status == DoseStatus.UNVERIFIED,
                        isLateMode = isMissed,
                        canLogLate = canLate,
                        isLocked = isLocked,
                        isWithinDoubleDoseGap = isWithinSafetyGap,
                        isManualUnverified = dose.manualUnverified,
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

    fun confirmDoubleDoseWarning() {
        _uiState.update { it.copy(showDoubleDoseWarning = false, doubleDoseConfirmed = true) }
    }

    fun dismissDoubleDoseWarning() {
        _uiState.update { it.copy(showDoubleDoseWarning = false) }
    }

    fun requestCapturePhoto(onProceed: () -> Unit) {
        val state = _uiState.value
        if (state.isWithinDoubleDoseGap && !state.doubleDoseConfirmed) {
            _uiState.update { it.copy(showDoubleDoseWarning = true) }
        } else {
            onProceed()
        }
    }

    fun recordManualUnverified() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = recordManualUnverifiedUseCase(doseId)
            if (result.isSuccess) {
                val updatedDose = doseRepository.getById(doseId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        dose = updatedDose,
                        isManualUnverified = true,
                        infoMessage = "Marked as taken (unverified)"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to report without photo"
                    )
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
            val bitmap = try {
                com.meditrack.app.core.util.BitmapLoader.decodeSampled(photoPath, maxSidePx = 1920)
            } catch (t: Throwable) {
                null
            }
            if (bitmap == null) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        errorMessage = "Failed to load verification photo due to memory limitations. Please retake."
                    )
                }
                return@launch
            }

            val dose = doseRepository.getById(doseId)
            val medicine = dose?.let { medicineRepository.getById(it.medicineId) }
            if (medicine != null && fakeStripDetector.behavior != ForceDetectorBehavior.DEFAULT) {
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
            val bitmap = try {
                com.meditrack.app.core.util.BitmapLoader.decodeSampled(photoPath, maxSidePx = 1920)
            } catch (t: Throwable) {
                null
            }
            if (bitmap != null) {
                val quality = com.meditrack.app.feature.verification.quality.ImageQualityGate.analyze(bitmap)
                if (!quality.passed) {
                    _uiState.update {
                        it.copy(
                            errorMessage = quality.friendlyMessage ?: "Baseline photo quality check failed. Please retake in good lighting."
                        )
                    }
                    return@launch
                }

                val detection = try {
                    stripDetector.detect(bitmap)
                } catch (e: Exception) {
                    null
                }
                val hasBlisterPack = detection != null && detection.cells.isNotEmpty()

                if (!hasBlisterPack) {
                    _uiState.update {
                        it.copy(
                            errorMessage = "No blister pack detected. Please ensure your blister pack with pill cavities is clearly visible to enroll."
                        )
                    }
                    return@launch
                }

                if (!detection.stripFullyInFrame) {
                    _uiState.update {
                        it.copy(
                            errorMessage = "The blister pack is cut off at the edge. Please center the entire blister strip inside the camera guide."
                        )
                    }
                    return@launch
                }

                val validCells = detection.cells.filter { it.confidence >= com.meditrack.app.core.config.AppConfig.DETECTOR_MIN_CONFIDENCE }
                val emptyCells = validCells.count { it.state == com.meditrack.app.feature.verification.pipeline.CellState.EMPTY }
                val updatedMed = med.copy(baselinePhotoPath = photoPath, baselineEmptyCells = emptyCells)
                medicineRepository.update(updatedMed)
                _uiState.update {
                    it.copy(
                        medicine = updatedMed,
                        needsBaselinePhoto = false,
                        errorMessage = null,
                        infoMessage = "Baseline strip enrolled successfully."
                    )
                }
            } else {
                val updatedMed = med.copy(baselinePhotoPath = photoPath, baselineEmptyCells = 0)
                medicineRepository.update(updatedMed)
                _uiState.update {
                    it.copy(
                        medicine = updatedMed,
                        needsBaselinePhoto = false,
                        errorMessage = null,
                        infoMessage = "Baseline strip enrolled successfully."
                    )
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

    override fun onCleared() {
        super.onCleared()
        if (stripDetector is java.io.Closeable) {
            try {
                stripDetector.close()
            } catch (_: Throwable) {}
        }
    }
}
