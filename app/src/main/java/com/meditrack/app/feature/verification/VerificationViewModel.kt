package com.meditrack.app.feature.verification

import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.BuildConfig
import java.io.File
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
import com.meditrack.app.feature.verification.pipeline.VerificationFailureReason
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
    val failureReason: VerificationFailureReason? = null,
    val retryHint: String? = null,
    /** Photo of the last rejected attempt; lets the person choose "Use Photo Anyway". */
    val lastRejectedPhotoPath: String? = null,
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
        if (!BuildConfig.DEBUG) return
        fakeStripDetector.behavior = behavior
        FakeStripDetector.debugForcedBehavior = behavior
        _uiState.update { it.copy(forcedBehavior = behavior) }
    }

    fun processCapturedPhoto(photoPath: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, errorMessage = null, infoMessage = null, lastRejectedPhotoPath = null) }
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
                            failureReason = result.failureReason,
                            retryHint = result.retryHint,
                            // Never offer "use anyway" for TOO_EARLY: it would bypass the early-intake guard.
                            lastRejectedPhotoPath = if (result.reason == RejectReason.TOO_EARLY) null else photoPath,
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
                            failureReason = result.failureReason,
                            retryHint = result.retryHint,
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
                val level1 = com.meditrack.app.feature.verification.quality.ImageQualityGate.analyzeLevel1(bitmap)
                if (!level1.passed) {
                    _uiState.update {
                        it.copy(
                            errorMessage = level1.friendlyMessage ?: "Baseline photo quality check failed. Please retake in good lighting."
                        )
                    }
                    return@launch
                }

                val detection = try {
                    stripDetector.detect(bitmap)
                } catch (e: Exception) {
                    null
                }
                val presence = if (detection?.isRoboflowWorkflow == true) {
                    val accepted = detection.roboflowPackStatus.equals("accepted", ignoreCase = true)
                    com.meditrack.app.feature.verification.pipeline.BlisterPresenceValidator.Result(
                        present = accepted,
                        validCells = detection.cells,
                        message = if (accepted) null else com.meditrack.app.feature.verification.pipeline.BlisterPresenceValidator.MSG_NOT_FOUND
                    )
                } else {
                    com.meditrack.app.feature.verification.pipeline.BlisterPresenceValidator.evaluate(detection)
                }

                if (!presence.present || detection == null || !detection.stripFullyInFrame) {
                    _uiState.update {
                        it.copy(
                            errorMessage = if (detection?.stripFullyInFrame == false) {
                                "Blister strip is partially outside the photo. Please place the entire blister pack fully in view and retake."
                            } else {
                                presence.message ?: "No blister pack detected."
                            }
                        )
                    }
                    return@launch
                }

                val stripBox = com.meditrack.app.feature.verification.quality.ImageQualityGate.resolveStripBox(detection.stripBox, detection.cells)
                val level2Quality = com.meditrack.app.feature.verification.quality.ImageQualityGate.analyzeBlisterCrop(bitmap, stripBox)
                if (!level2Quality.passed) {
                    _uiState.update {
                        it.copy(
                            errorMessage = level2Quality.friendlyMessage ?: "Blister photo quality check failed. Please retake with better lighting."
                        )
                    }
                    return@launch
                }

                val cellGuard = com.meditrack.app.feature.verification.pipeline.BlisterCellGuards.evaluate(detection.cells)
                if (cellGuard is com.meditrack.app.feature.verification.pipeline.BlisterCellGuards.GuardResult.Rejected) {
                    _uiState.update {
                        it.copy(errorMessage = cellGuard.message)
                    }
                    return@launch
                }

                val emptyCells = if (detection.isRoboflowWorkflow && detection.roboflowMissingCount != null) {
                    detection.roboflowMissingCount
                } else {
                    presence.validCells.count {
                        it.state == com.meditrack.app.feature.verification.pipeline.CellState.EMPTY
                    }
                }
                val updatedMed = med.copy(
                    baselinePhotoPath = photoPath,
                    baselineEmptyCells = emptyCells
                )
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
                _uiState.update {
                    it.copy(errorMessage = "Could not read the baseline photo. Please retake it.")
                }
            }
        }
    }

    /**
     * "Use Photo Anyway": the person confirms the dose was taken although automatic verification rejected the photo.
     * Records the dose as TAKEN (or TAKEN_LATE) with the rejected photo as proof, keeping the empty-cell ledger moving
     * by the prescribed pills-per-dose so later verifications still compare correctly.
     */
    fun useRejectedPhotoAnyway() {
        if (_uiState.value.isSimulating || _uiState.value.isAnalyzing) return // ignore double taps
        val photoPath = _uiState.value.lastRejectedPhotoPath
        if (photoPath.isNullOrBlank()) return

        val photoFile = File(photoPath)
        if (!photoFile.exists() || photoFile.length() == 0L) {
            _uiState.update {
                it.copy(errorMessage = "Verification photo is missing. Please take a photo first.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSimulating = true, errorMessage = null, infoMessage = null) }
            val dose = doseRepository.getById(doseId)
            val medicine = dose?.let { medicineRepository.getById(it.medicineId) }
            if (dose == null || medicine == null) {
                _uiState.update { it.copy(isSimulating = false, errorMessage = "Dose not found.") }
                return@launch
            }

            // Early verification guard: block "Use Photo Anyway" when too early
            val earlyToleranceMillis = AppConfig.EARLY_VERIFY_TOLERANCE_MINUTES * 60_000L
            val now = System.currentTimeMillis()
            if (now < dose.scheduledAt - earlyToleranceMillis) {
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        errorMessage = "It is too early to take this dose. Please wait until your scheduled time."
                    )
                }
                return@launch
            }

            // Guard against closed/skipped dose transition bypass
            if (dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.TAKEN_LATE || dose.status == DoseStatus.SKIPPED) {
                _uiState.update {
                    it.copy(isSimulating = false, errorMessage = "Dose has already been recorded.")
                }
                return@launch
            }

            val lastRefillAt = medicine.lastRefillAt ?: 0L
            val latest = if (lastRefillAt > 0L) {
                doseRepository.getLatestVerifiedDoseAfter(medicine.id, lastRefillAt)
            } else {
                doseRepository.getLatestVerifiedDose(medicine.id)
            }
            val emptyCells = (latest?.emptyCellsAfter ?: medicine.baselineEmptyCells) + medicine.pillsPerDose

            recordVerifiedDoseUseCase(
                doseId = doseId,
                photoPath = photoPath,
                emptyCellsAfter = emptyCells,
                takenAt = now
            ).onSuccess {
                val updatedDose = doseRepository.getById(doseId)
                val updatedMed = updatedDose?.let { medicineRepository.getById(it.medicineId) }
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        isSuccess = true,
                        isFailed = false,
                        dose = updatedDose,
                        medicine = updatedMed,
                        infoMessage = "Dose marked as taken."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isSimulating = false, errorMessage = error.message ?: "Failed to record dose")
                }
            }
        }
    }

    /**
     * Resets the strip ledger when a patient confirms they started a new or different blister pack,
     * recording the dose directly with the new strip baseline without forcing navigation to Refill.
     */
    fun confirmNewStripAndVerify() {
        if (_uiState.value.isSimulating || _uiState.value.isAnalyzing) return
        val photoPath = _uiState.value.lastRejectedPhotoPath
        if (photoPath.isNullOrBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSimulating = true, errorMessage = null, infoMessage = null) }
            val dose = doseRepository.getById(doseId)
            val medicine = dose?.let { medicineRepository.getById(it.medicineId) }
            if (dose == null || medicine == null) {
                _uiState.update { it.copy(isSimulating = false, errorMessage = "Dose not found.") }
                return@launch
            }

            val bitmap = try {
                com.meditrack.app.core.util.BitmapLoader.decodeSampled(photoPath, maxSidePx = 1920)
            } catch (t: Throwable) {
                null
            }
            if (bitmap == null) {
                _uiState.update {
                    it.copy(isSimulating = false, errorMessage = "Could not decode verification photo. Please retake.")
                }
                return@launch
            }

            val detection = try {
                stripDetector.detect(bitmap)
            } catch (e: Exception) {
                null
            }
            if (detection == null || !detection.stripFullyInFrame) {
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        errorMessage = "Could not clearly detect the new blister strip in this photo. Please retake."
                    )
                }
                return@launch
            }

            val measuredEmpty = if (detection.isRoboflowWorkflow && detection.roboflowMissingCount != null) {
                detection.roboflowMissingCount
            } else {
                detection.cells.count { it.state == com.meditrack.app.feature.verification.pipeline.CellState.EMPTY }
            }

            val now = System.currentTimeMillis()
            val newBaselineEmpty = kotlin.math.max(0, measuredEmpty - medicine.pillsPerDose)
            val updatedMed = medicine.copy(
                baselineEmptyCells = newBaselineEmpty,
                baselinePhotoPath = photoPath,
                lastRefillAt = now,
                updatedAt = now
            )
            medicineRepository.update(updatedMed)

            val emptyCells = measuredEmpty
            recordVerifiedDoseUseCase(
                doseId = doseId,
                photoPath = photoPath,
                emptyCellsAfter = emptyCells,
                takenAt = now
            ).onSuccess {
                val updatedDose = doseRepository.getById(doseId)
                val refreshedMed = medicineRepository.getById(medicine.id)
                _uiState.update {
                    it.copy(
                        isSimulating = false,
                        isSuccess = true,
                        isFailed = false,
                        dose = updatedDose,
                        medicine = refreshedMed,
                        infoMessage = "New strip confirmed. Dose verified as taken."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isSimulating = false, errorMessage = error.message ?: "Failed to record dose with new strip")
                }
            }
        }
    }

    fun simulateVerifiedTake() {
        if (!BuildConfig.DEBUG) return
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
