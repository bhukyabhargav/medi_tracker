package com.meditrack.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.AdherenceCalculator
import com.meditrack.app.domain.usecase.AdherenceReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

enum class HistoryTimeRange {
    TODAY,
    WEEK,
    MONTH
}

data class HistoryItem(
    val dose: DoseEvent,
    val medicineName: String,
    val dosageText: String,
    val statusLabel: String,
    val isLate: Boolean,
    val isManualUnverified: Boolean,
    val photoPath: String?
)

data class HistoryUiState(
    val timeRange: HistoryTimeRange = HistoryTimeRange.TODAY,
    val items: List<HistoryItem> = emptyList(),
    val adherenceReport: AdherenceReport = AdherenceCalculator.calculate(emptyList()),
    val selectedItem: HistoryItem? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository
) : ViewModel() {

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }
    internal var zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }

    private val _timeRange = MutableStateFlow(HistoryTimeRange.TODAY)
    private val _selectedItem = MutableStateFlow<HistoryItem?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HistoryUiState> = _timeRange
        .flatMapLatest { range ->
            val now = timeProvider()
            val zoneId = zoneProvider()
            val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()

            val (startMillis, endMillis) = when (range) {
                HistoryTimeRange.TODAY -> {
                    val start = ZonedDateTime.of(today, LocalTime.MIN, zoneId).toInstant().toEpochMilli()
                    val end = ZonedDateTime.of(today, LocalTime.MAX, zoneId).toInstant().toEpochMilli()
                    Pair(start, end)
                }
                HistoryTimeRange.WEEK -> {
                    val start = ZonedDateTime.of(today.minusDays(6), LocalTime.MIN, zoneId).toInstant().toEpochMilli()
                    val end = ZonedDateTime.of(today, LocalTime.MAX, zoneId).toInstant().toEpochMilli()
                    Pair(start, end)
                }
                HistoryTimeRange.MONTH -> {
                    val start = ZonedDateTime.of(today.minusDays(29), LocalTime.MIN, zoneId).toInstant().toEpochMilli()
                    val end = ZonedDateTime.of(today, LocalTime.MAX, zoneId).toInstant().toEpochMilli()
                    Pair(start, end)
                }
            }

            combine(
                doseRepository.observeBetween(startMillis, endMillis),
                medicineRepository.observeAll(),
                _selectedItem
            ) { doses, medicines, selected ->
                val medMap = medicines.associateBy { it.id }

                // History displays closed doses only (Taken, Taken Late, Missed, Skipped, Unverified)
                val closedDoses = doses.filter { it.status != DoseStatus.PENDING }

                val items = closedDoses.sortedByDescending { it.scheduledAt }.map { dose ->
                    val med = medMap[dose.medicineId]
                    val statusLabel = when {
                        dose.manualUnverified -> "Reported without photo"
                        dose.status == DoseStatus.TAKEN_LATE -> "Taken Late"
                        dose.status == DoseStatus.TAKEN -> "Taken"
                        dose.status == DoseStatus.MISSED -> "Missed"
                        dose.status == DoseStatus.SKIPPED -> "Skipped"
                        dose.status == DoseStatus.UNVERIFIED -> "Unverified"
                        else -> "Pending"
                    }

                    HistoryItem(
                        dose = dose,
                        medicineName = med?.name ?: "Unknown Medicine",
                        dosageText = med?.dosageText ?: "",
                        statusLabel = statusLabel,
                        isLate = dose.lateEntry || dose.status == DoseStatus.TAKEN_LATE,
                        isManualUnverified = dose.manualUnverified,
                        photoPath = dose.verifiedPhotoPath
                    )
                }

                val adherence = AdherenceCalculator.calculate(doses)

                HistoryUiState(
                    timeRange = range,
                    items = items,
                    adherenceReport = adherence,
                    selectedItem = selected,
                    isLoading = false
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HistoryUiState(isLoading = true)
        )

    fun setTimeRange(range: HistoryTimeRange) {
        _timeRange.value = range
    }

    fun selectItem(item: HistoryItem?) {
        _selectedItem.value = item
    }
}
