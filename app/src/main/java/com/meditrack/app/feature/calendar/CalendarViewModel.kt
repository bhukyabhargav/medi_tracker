package com.meditrack.app.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.LateEntryPolicy
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
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

enum class CalendarDayStatus {
    NONE,       // Grey - no closed doses (or no doses)
    ALL_TAKEN,  // Green - all closed doses taken on time (0 late, 0 missed, 0 unverified)
    SOME_LATE,  // Orange - some doses taken late or unverified, none missed
    MISSED      // Red - at least one missed dose
}

data class CalendarDoseItem(
    val dose: DoseEvent,
    val medicine: Medicine?,
    val canLogLate: Boolean,
    val isLocked: Boolean
)

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val dayStatusMap: Map<LocalDate, CalendarDayStatus> = emptyMap(),
    val selectedDateDoses: List<CalendarDoseItem> = emptyList(),
    val selectedDoseDetail: CalendarDoseItem? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository
) : ViewModel() {

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }
    internal var zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }

    private val _customMonth = MutableStateFlow<YearMonth?>(null)
    private val _customDate = MutableStateFlow<LocalDate?>(null)
    private val _selectedDoseDetail = MutableStateFlow<CalendarDoseItem?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<CalendarUiState> = combine(
        _customMonth,
        _customDate,
        _selectedDoseDetail
    ) { customMonth, customDate, selectedDetail ->
        Triple(customMonth, customDate, selectedDetail)
    }.flatMapLatest { (customMonth, customDate, selectedDetail) ->
        val zoneId = zoneProvider()
        val now = timeProvider()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val month = customMonth ?: YearMonth.from(today)
        val selectedDate = customDate ?: today

        val startOfMonth = ZonedDateTime.of(month.atDay(1), LocalTime.MIN, zoneId).toInstant().toEpochMilli()
        val endOfMonth = ZonedDateTime.of(month.atEndOfMonth(), LocalTime.MAX, zoneId).toInstant().toEpochMilli()
        val queryHorizon = endOfMonth + (7 * 24 * 60 * 60 * 1000L) // Include cross-boundary window for nextScheduled

        combine(
            doseRepository.observeBetween(startOfMonth, queryHorizon),
            medicineRepository.observeAll()
        ) { allHorizonDoses, allMedicines ->
            val medMap = allMedicines.associateBy { it.id }
            val monthDoses = allHorizonDoses.filter { it.scheduledAt <= endOfMonth }

            // Group doses by day for current month
            val dosesByDay = monthDoses.groupBy { dose ->
                Instant.ofEpochMilli(dose.scheduledAt).atZone(zoneId).toLocalDate()
            }

            // Compute day status for every day with doses
            val dayStatusMap = mutableMapOf<LocalDate, CalendarDayStatus>()
            dosesByDay.forEach { (date, doses) ->
                dayStatusMap[date] = computeDayStatus(doses)
            }

            // Doses for selected date
            val dosesForSelectedDate = dosesByDay[selectedDate] ?: emptyList()
            val doseItems = dosesForSelectedDate.sortedBy { it.scheduledAt }.map { dose ->
                val med = medMap[dose.medicineId]
                val isMissed = dose.status == DoseStatus.MISSED
                // Look ahead across month boundary to find next scheduled dose
                val nextScheduled = allHorizonDoses
                    .filter { it.medicineId == dose.medicineId && it.scheduledAt > dose.scheduledAt }
                    .minByOrNull { it.scheduledAt }?.scheduledAt

                val canLogLate = if (isMissed) {
                    LateEntryPolicy.canLogLate(dose, nextScheduled, now)
                } else {
                    false
                }
                val isLocked = isMissed && !canLogLate

                CalendarDoseItem(
                    dose = dose,
                    medicine = med,
                    canLogLate = canLogLate,
                    isLocked = isLocked
                )
            }

            CalendarUiState(
                currentMonth = month,
                selectedDate = selectedDate,
                dayStatusMap = dayStatusMap,
                selectedDateDoses = doseItems,
                selectedDoseDetail = selectedDetail,
                isLoading = false
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CalendarUiState(isLoading = true)
    )

    fun previousMonth() {
        val current = _customMonth.value ?: YearMonth.from(Instant.ofEpochMilli(timeProvider()).atZone(zoneProvider()))
        _customMonth.value = current.minusMonths(1)
    }

    fun nextMonth() {
        val current = _customMonth.value ?: YearMonth.from(Instant.ofEpochMilli(timeProvider()).atZone(zoneProvider()))
        _customMonth.value = current.plusMonths(1)
    }

    fun selectDate(date: LocalDate) {
        _customDate.value = date
    }

    fun selectDoseDetail(doseItem: CalendarDoseItem?) {
        _selectedDoseDetail.value = doseItem
    }

    companion object {
        fun computeDayStatus(doses: List<DoseEvent>): CalendarDayStatus {
            val closedDoses = doses.filter { it.status != DoseStatus.PENDING }
            if (closedDoses.isEmpty()) {
                return CalendarDayStatus.NONE
            }

            val hasMissed = closedDoses.any { it.status == DoseStatus.MISSED }
            if (hasMissed) {
                return CalendarDayStatus.MISSED
            }

            val hasLateOrUnverified = closedDoses.any {
                it.status == DoseStatus.TAKEN_LATE || it.status == DoseStatus.UNVERIFIED || it.lateEntry
            }
            if (hasLateOrUnverified) {
                return CalendarDayStatus.SOME_LATE
            }

            // When evaluating all taken, exclude skipped doses (skipping is an approved action)
            val nonSkipped = closedDoses.filter { it.status != DoseStatus.SKIPPED }
            if (nonSkipped.isNotEmpty() && nonSkipped.all { it.status == DoseStatus.TAKEN }) {
                return CalendarDayStatus.ALL_TAKEN
            }

            return CalendarDayStatus.NONE
        }
    }
}
