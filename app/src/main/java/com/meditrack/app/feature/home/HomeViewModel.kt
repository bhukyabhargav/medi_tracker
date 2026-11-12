package com.meditrack.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.core.config.AppConfig
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.meditrack.app.domain.usecase.AdherenceCalculator
import com.meditrack.app.domain.usecase.AdherenceReport
import com.meditrack.app.feature.reminder.MissedDoseNotifier
import com.meditrack.app.feature.caregiver.CaregiverAlertNotifier
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

data class HomeUiState(
    val user: User? = null,
    val activeMedicines: List<Medicine> = emptyList(),
    val todayDoses: List<DoseEvent> = emptyList(),
    val upcomingDoses: List<DoseEvent> = emptyList(),
    val missedDoses: List<DoseEvent> = emptyList(),
    val adherenceReport: AdherenceReport? = null,
    val medicineAdherence: List<com.meditrack.app.domain.usecase.MedicineAdherence> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    @ApplicationContext private val context: Context? = null
) : ViewModel() {

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }
    internal var zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    companion object {
        private const val MILLIS_PER_HOUR = 3600 * 1000L
        private const val LATE_WINDOW_MILLIS = AppConfig.LATE_DOSE_MAX_HOURS * MILLIS_PER_HOUR // 24 hours
        private const val UPCOMING_WINDOW_MILLIS = 24 * MILLIS_PER_HOUR // next 24 hours
    }

    init {
        loadDashboard()
    }

    fun markDoseAsMissed(doseId: String) {
        viewModelScope.launch {
            val dose = doseRepository.getById(doseId) ?: return@launch
            val updated = dose.copy(status = DoseStatus.MISSED, updatedAt = timeProvider())
            doseRepository.update(updated)

            context?.let { ctx ->
                val medName = medicineRepository.getById(dose.medicineId)?.name ?: "Scheduled Medicine"
                try {
                    MissedDoseNotifier.post(ctx, dose.id, medName)
                    CaregiverAlertNotifier.postMissedAlert(ctx, "Demo Patient", medName)
                } catch (_: Exception) {}
            }
        }
    }

    fun loadDashboard() {
        viewModelScope.launch {
            val now = timeProvider()
            try {
                val overdue = doseRepository.getOverduePendingDoses(now)
                for (dose in overdue) {
                    doseRepository.update(dose.copy(status = DoseStatus.MISSED, updatedAt = now))
                }
            } catch (_: Exception) {}
            val zoneId = zoneProvider()
            val todayDate = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
            val startOfToday = ZonedDateTime.of(todayDate, LocalTime.MIN, zoneId).toInstant().toEpochMilli()
            val endOfToday = ZonedDateTime.of(todayDate, LocalTime.MAX, zoneId).toInstant().toEpochMilli()

            val historyStart = now - (14L * 24 * MILLIS_PER_HOUR)
            val queryStart = minOf(startOfToday - (14L * 24 * MILLIS_PER_HOUR), now - LATE_WINDOW_MILLIS)
            val queryEnd = maxOf(endOfToday, now + UPCOMING_WINDOW_MILLIS)

            combine(
                authRepository.observeCurrentUser(),
                medicineRepository.observeActive(),
                doseRepository.observeBetween(queryStart, queryEnd)
            ) { user, medicines, allDoses ->
                val today = allDoses.filter { it.scheduledAt in startOfToday..endOfToday }
                    .sortedBy { it.scheduledAt }

                val upcoming = allDoses.filter {
                    it.status == DoseStatus.PENDING && it.scheduledAt in now..(now + UPCOMING_WINDOW_MILLIS)
                }.sortedBy { it.scheduledAt }

                val missed = allDoses.filter {
                    it.status == DoseStatus.MISSED && (now - it.scheduledAt) in 0..LATE_WINDOW_MILLIS
                }.sortedBy { it.scheduledAt }

                val adherence = AdherenceCalculator.calculate(allDoses)

                HomeUiState(
                    user = user,
                    activeMedicines = medicines,
                    todayDoses = today,
                    upcomingDoses = upcoming,
                    missedDoses = missed,
                    adherenceReport = adherence,
                    medicineAdherence = AdherenceCalculator.calculateByMedicine(
                        allDoses,
                        medicines.associate { it.id to it.name }
                    ),
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }
}
