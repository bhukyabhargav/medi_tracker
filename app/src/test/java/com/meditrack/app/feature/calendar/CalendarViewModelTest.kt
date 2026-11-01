package com.meditrack.app.feature.calendar

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)

    private val testZoneId = ZoneId.of("UTC")
    // Base date: 2026-11-02 12:00:00 UTC
    private val testDateTime = ZonedDateTime.of(
        LocalDate.of(2026, 11, 2),
        LocalTime.of(12, 0),
        testZoneId
    )
    private val testNowMillis = testDateTime.toInstant().toEpochMilli()

    private val sampleMed = Medicine(
        id = "med_1",
        name = "Metformin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = testNowMillis - (30L * 24 * 60 * 60 * 1000L),
        foodRelation = FoodRelation.AFTER,
        initialStock = 60,
        currentStock = 30
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { medicineRepository.observeActive() } returns flowOf(listOf(sampleMed))
        every { doseRepository.observeBetween(any(), any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): CalendarViewModel {
        return CalendarViewModel(doseRepository, medicineRepository).apply {
            timeProvider = { testNowMillis }
            zoneProvider = { testZoneId }
        }
    }

    @Test
    fun `computeDayStatus accurately maps status according to priority rules`() {
        fun makeDose(status: DoseStatus, late: Boolean = false, manualUnverified: Boolean = false): DoseEvent {
            return DoseEvent(
                id = "d_${status.name}",
                medicineId = "med_1",
                scheduledAt = testNowMillis,
                windowEndsAt = testNowMillis + 3600000L,
                status = status,
                lateEntry = late,
                manualUnverified = manualUnverified
            )
        }

        // 1. Empty or all pending -> NONE (Grey)
        assertEquals(CalendarDayStatus.NONE, CalendarViewModel.computeDayStatus(emptyList()))
        assertEquals(CalendarDayStatus.NONE, CalendarViewModel.computeDayStatus(listOf(makeDose(DoseStatus.PENDING))))

        // 2. All taken on-time -> ALL_TAKEN (Green)
        assertEquals(
            CalendarDayStatus.ALL_TAKEN,
            CalendarViewModel.computeDayStatus(listOf(makeDose(DoseStatus.TAKEN), makeDose(DoseStatus.TAKEN)))
        )

        // 3. Some late or unverified (and none missed) -> SOME_LATE (Orange)
        assertEquals(
            CalendarDayStatus.SOME_LATE,
            CalendarViewModel.computeDayStatus(listOf(makeDose(DoseStatus.TAKEN), makeDose(DoseStatus.TAKEN_LATE, late = true)))
        )
        assertEquals(
            CalendarDayStatus.SOME_LATE,
            CalendarViewModel.computeDayStatus(listOf(makeDose(DoseStatus.TAKEN), makeDose(DoseStatus.UNVERIFIED)))
        )

        // 4. Any missed -> MISSED (Red), even if others are taken
        assertEquals(
            CalendarDayStatus.MISSED,
            CalendarViewModel.computeDayStatus(listOf(makeDose(DoseStatus.TAKEN), makeDose(DoseStatus.MISSED)))
        )
        assertEquals(
            CalendarDayStatus.MISSED,
            CalendarViewModel.computeDayStatus(listOf(makeDose(DoseStatus.TAKEN_LATE), makeDose(DoseStatus.MISSED)))
        )

        // 5. Manual unverified is MISSED -> Red
        assertEquals(
            CalendarDayStatus.MISSED,
            CalendarViewModel.computeDayStatus(listOf(makeDose(DoseStatus.MISSED, manualUnverified = true)))
        )
    }

    @Test
    fun `month pagination changes current month correctly`() = runTest {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 11), vm.uiState.value.currentMonth)

        vm.nextMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 12), vm.uiState.value.currentMonth)

        vm.previousMonth()
        vm.previousMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 10), vm.uiState.value.currentMonth)

        job.cancel()
    }

    @Test
    fun `selecting date filters doses for that day and computes late entry lock state`() = runTest {
        val nov2Millis = ZonedDateTime.of(LocalDate.of(2026, 11, 2), LocalTime.of(8, 0), testZoneId).toInstant().toEpochMilli()
        val nov3Millis = ZonedDateTime.of(LocalDate.of(2026, 11, 3), LocalTime.of(8, 0), testZoneId).toInstant().toEpochMilli()

        // Dose 1 on Nov 2: Missed, but within 24h -> canLogLate = true
        val doseNov2 = DoseEvent(
            id = "dose_nov_2",
            medicineId = sampleMed.id,
            scheduledAt = nov2Millis,
            windowEndsAt = nov2Millis + 3600000L,
            status = DoseStatus.MISSED
        )

        // Dose 2 on Nov 3: Taken
        val doseNov3 = DoseEvent(
            id = "dose_nov_3",
            medicineId = sampleMed.id,
            scheduledAt = nov3Millis,
            windowEndsAt = nov3Millis + 3600000L,
            status = DoseStatus.TAKEN,
            takenAt = nov3Millis + 600000L
        )

        every { doseRepository.observeBetween(any(), any()) } returns flowOf(listOf(doseNov2, doseNov3))

        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        // Default selectedDate is Nov 2
        assertEquals(LocalDate.of(2026, 11, 2), vm.uiState.value.selectedDate)
        assertEquals(1, vm.uiState.value.selectedDateDoses.size)
        val itemNov2 = vm.uiState.value.selectedDateDoses.first()
        assertEquals("dose_nov_2", itemNov2.dose.id)
        assertTrue("Recent missed dose should allow late entry", itemNov2.canLogLate)
        assertFalse(itemNov2.isLocked)

        // Select Nov 3
        vm.selectDate(LocalDate.of(2026, 11, 3))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.selectedDateDoses.size)
        val itemNov3 = vm.uiState.value.selectedDateDoses.first()
        assertEquals("dose_nov_3", itemNov3.dose.id)
        assertFalse(itemNov3.canLogLate)
        assertFalse(itemNov3.isLocked)

        // Test dose detail selection
        vm.selectDoseDetail(itemNov3)
        advanceUntilIdle()
        assertEquals(itemNov3, vm.uiState.value.selectedDoseDetail)

        vm.selectDoseDetail(null)
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedDoseDetail)

        job.cancel()
    }
}
