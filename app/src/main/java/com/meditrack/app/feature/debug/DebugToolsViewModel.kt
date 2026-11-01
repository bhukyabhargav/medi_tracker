package com.meditrack.app.feature.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.ProcessMissedDoseUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import com.meditrack.app.feature.verification.dataset.DatasetCollector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

data class DebugToolsUiState(
    val isShortWindowEnabled: Boolean = false,
    val windowMinutes: Int = AppConfig.DOSE_WINDOW_MINUTES,
    val isDataCollectionEnabled: Boolean = false,
    val datasetCount: Int = 0,
    val statusMessage: String? = null,
    val isActionInProgress: Boolean = false
)

@HiltViewModel
class DebugToolsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val alarmScheduler: AlarmScheduler,
    private val processMissedDoseUseCase: ProcessMissedDoseUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DebugToolsUiState(
            isShortWindowEnabled = AppConfig.debugDoseWindowMinutes != null,
            windowMinutes = AppConfig.effectiveDoseWindowMinutes,
            isDataCollectionEnabled = DatasetCollector.isDataCollectionEnabled,
            datasetCount = DatasetCollector.getSampleCount(context)
        )
    )
    val uiState: StateFlow<DebugToolsUiState> = _uiState.asStateFlow()

    fun toggleShortDoseWindow(enable: Boolean) {
        if (enable) {
            AppConfig.debugDoseWindowMinutes = 1
        } else {
            AppConfig.debugDoseWindowMinutes = null
        }
        _uiState.update {
            it.copy(
                isShortWindowEnabled = enable,
                windowMinutes = AppConfig.effectiveDoseWindowMinutes,
                statusMessage = if (enable) "Dose intake window shortened to 1 minute" else "Dose window reset to 60 minutes"
            )
        }
    }

    fun toggleDataCollection(enable: Boolean) {
        DatasetCollector.isDataCollectionEnabled = enable
        _uiState.update {
            it.copy(
                isDataCollectionEnabled = enable,
                datasetCount = DatasetCollector.getSampleCount(context),
                statusMessage = if (enable) "Data collection mode ENABLED (Stage 5/13)" else "Data collection mode DISABLED"
            )
        }
    }

    fun exportDatasetZip() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionInProgress = true, statusMessage = null) }
            val zip = DatasetCollector.exportDatasetZip(context)
            if (zip != null) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        statusMessage = "Dataset exported to: ${zip.name} (${zip.length() / 1024} KB)"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        statusMessage = "Dataset folder is empty or export failed."
                    )
                }
            }
        }
    }

    fun triggerAlarmNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionInProgress = true, statusMessage = null) }
            try {
                val now = System.currentTimeMillis()
                val activeMeds = medicineRepository.observeActive().first()

                var med = activeMeds.firstOrNull()
                if (med == null) {
                    med = Medicine(
                        id = "debug_med_${UUID.randomUUID()}",
                        name = "Debug Aspirin 81mg",
                        dosageText = "81mg",
                        pillsPerDose = 1,
                        frequencyType = FrequencyType.DAILY,
                        timesOfDay = listOf(LocalTime.now()),
                        startDate = now,
                        foodRelation = FoodRelation.AFTER,
                        initialStock = 30,
                        currentStock = 30
                    )
                    medicineRepository.insert(med)
                }

                val windowMinutes = AppConfig.effectiveDoseWindowMinutes
                val testDose = DoseEvent(
                    id = "debug_dose_${UUID.randomUUID()}",
                    medicineId = med.id,
                    scheduledAt = now,
                    windowEndsAt = now + (windowMinutes * 60 * 1000L),
                    status = DoseStatus.PENDING,
                    updatedAt = now
                )
                doseRepository.insert(testDose)

                alarmScheduler.scheduleDoseAlarm(testDose, triggerAtMillis = now + 1000L)

                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        statusMessage = "Alarm scheduled for dose of '${med.name}' (fires in ~1s, window: ${windowMinutes}m)"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        statusMessage = "Error triggering alarm: ${e.message}"
                    )
                }
            }
        }
    }

    fun triggerMissedEvaluationNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionInProgress = true, statusMessage = null) }
            try {
                val now = System.currentTimeMillis()
                val overdue = doseRepository.getOverduePendingDoses(now)
                var count = 0
                for (dose in overdue) {
                    val res = processMissedDoseUseCase(dose.id, now)
                    if (res.getOrDefault(false)) count++
                }
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        statusMessage = "Processed overdue doses: $count transitioned to MISSED"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        statusMessage = "Error evaluating missed doses: ${e.message}"
                    )
                }
            }
        }
    }
}
