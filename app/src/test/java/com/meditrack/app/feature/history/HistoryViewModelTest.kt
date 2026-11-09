package com.meditrack.app.feature.history

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)

    private val testZoneId = ZoneId.of("UTC")
    // 2026-11-02 12:00:00 UTC
    private val testDateTime = ZonedDateTime.of(
        LocalDate.of(2026, 11, 2),
        LocalTime.of(12, 0),
        testZoneId
    )
    private val testNowMillis = testDateTime.toInstant().toEpochMilli()

    private val sampleMed = Medicine(
        id = "med_1",
        name = "Amoxicillin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(9, 0)),
        startDate = testNowMillis - (30L * 24 * 60 * 60 * 1000L),
        foodRelation = FoodRelation.AFTER,
        initialStock = 100,
        currentStock = 50
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { medicineRepository.observeAll() } returns flowOf(listOf(sampleMed))
        every { medicineRepository.observeActive() } returns flowOf(listOf(sampleMed))
        every { doseRepository.observeBetween(any(), any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HistoryViewModel {
        return HistoryViewModel(doseRepository, medicineRepository).apply {
            timeProvider = { testNowMillis }
            zoneProvider = { testZoneId }
        }
    }

    @Test
    fun `empty state returns no items and dash adherence`() = runTest {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(HistoryTimeRange.TODAY, state.timeRange)
        assertTrue(state.items.isEmpty())
        assertNull(state.adherenceReport.percentage)
        assertEquals("—", state.adherenceReport.displayText)
        assertNull(state.selectedItem)

        job.cancel()
    }

    @Test
    fun `setting time range triggers range queries for Today Week and Month`() = runTest {
        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        // Today start/end
        val today = LocalDate.of(2026, 11, 2)
        val todayStart = ZonedDateTime.of(today, LocalTime.MIN, testZoneId).toInstant().toEpochMilli()
        val todayEnd = ZonedDateTime.of(today, LocalTime.MAX, testZoneId).toInstant().toEpochMilli()

        verify { doseRepository.observeBetween(todayStart, todayEnd) }

        // Switch to Week
        vm.setTimeRange(HistoryTimeRange.WEEK)
        advanceUntilIdle()

        val weekStart = ZonedDateTime.of(today.minusDays(6), LocalTime.MIN, testZoneId).toInstant().toEpochMilli()
        verify { doseRepository.observeBetween(weekStart, todayEnd) }

        // Switch to Month
        vm.setTimeRange(HistoryTimeRange.MONTH)
        advanceUntilIdle()

        val monthStart = ZonedDateTime.of(today.minusDays(29), LocalTime.MIN, testZoneId).toInstant().toEpochMilli()
        verify { doseRepository.observeBetween(monthStart, todayEnd) }

        job.cancel()
    }

    @Test
    fun `seeded data produces exactly 92 percent adherence with 4 late and 4 missed`() = runTest {
        // Build 50 closed doses: 42 taken, 4 taken late, 4 missed (1 manual unverified)
        val seededDoses = mutableListOf<DoseEvent>()
        for (i in 0 until 50) {
            val status = when (i) {
                in 42..45 -> DoseStatus.TAKEN_LATE
                in 46..49 -> DoseStatus.MISSED
                else -> DoseStatus.TAKEN
            }
            seededDoses.add(
                DoseEvent(
                    id = "dose_$i",
                    medicineId = sampleMed.id,
                    scheduledAt = testNowMillis - (i * 3600000L),
                    windowEndsAt = testNowMillis - (i * 3600000L) + 3600000L,
                    status = status,
                    takenAt = if (status == DoseStatus.TAKEN || status == DoseStatus.TAKEN_LATE) testNowMillis - (i * 3600000L) + 600000L else null,
                    lateEntry = status == DoseStatus.TAKEN_LATE,
                    manualUnverified = i == 49
                )
            )
        }

        every { doseRepository.observeBetween(any(), any()) } returns flowOf(seededDoses)

        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(50, state.items.size)
        val report = state.adherenceReport

        // Invariants:
        // verifiedTaken = 42 + 4 = 46
        // closedDoses = 46 + 4 missed = 50
        // percentage = 46 / 50 * 100 = 92%
        assertEquals(92.0, report.percentage!!, 0.001)
        assertEquals("92%", report.percentageFormatted)
        assertEquals("92% (4 late)", report.displayText)
        assertEquals(46, report.verifiedTakenCount)
        assertEquals(42, report.takenOnTimeCount)
        assertEquals(4, report.takenLateCount)
        assertEquals(4, report.missedCount)
        assertEquals(0, report.skippedCount)
        assertEquals(1, report.manualUnverifiedCount)

        job.cancel()
    }

    @Test
    fun `selecting an item updates selectedItem state`() = runTest {
        val dose = DoseEvent(
            id = "dose_test",
            medicineId = sampleMed.id,
            scheduledAt = testNowMillis,
            windowEndsAt = testNowMillis + 3600000L,
            status = DoseStatus.TAKEN,
            verifiedPhotoPath = "/path/to/proof.jpg"
        )
        every { doseRepository.observeBetween(any(), any()) } returns flowOf(listOf(dose))

        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val item = vm.uiState.value.items.first()
        assertEquals("Amoxicillin", item.medicineName)
        assertEquals("Taken", item.statusLabel)
        assertEquals("/path/to/proof.jpg", item.photoPath)

        vm.selectItem(item)
        advanceUntilIdle()
        assertEquals(item, vm.uiState.value.selectedItem)

        vm.selectItem(null)
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedItem)

        job.cancel()
    }

    @Test
    fun `history excludes future pending doses`() = runTest {
        val pendingDose = DoseEvent(
            id = "dose_pending",
            medicineId = sampleMed.id,
            scheduledAt = testNowMillis + 3600000L,
            windowEndsAt = testNowMillis + 7200000L,
            status = DoseStatus.PENDING
        )
        val takenDose = DoseEvent(
            id = "dose_taken",
            medicineId = sampleMed.id,
            scheduledAt = testNowMillis - 3600000L,
            windowEndsAt = testNowMillis,
            status = DoseStatus.TAKEN
        )
        every { doseRepository.observeBetween(any(), any()) } returns flowOf(listOf(pendingDose, takenDose))

        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("dose_taken", vm.uiState.value.items.first().dose.id)

        job.cancel()
    }
    @Test
    fun `history preserves name and dosage for deleted medicines`() = runTest {
        val deletedMed = sampleMed.copy(id = "med_deleted", name = "Discontinued Penicillin", isDeleted = true)
        val takenDose = DoseEvent(
            id = "dose_deleted_med",
            medicineId = "med_deleted",
            scheduledAt = testNowMillis - 3600000L,
            windowEndsAt = testNowMillis,
            status = DoseStatus.TAKEN
        )
        every { medicineRepository.observeAll() } returns flowOf(listOf(deletedMed))
        every { doseRepository.observeBetween(any(), any()) } returns flowOf(listOf(takenDose))

        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val items = vm.uiState.value.items
        assertEquals(1, items.size)
        assertEquals("Discontinued Penicillin", items.first().medicineName)
        assertEquals("500mg", items.first().dosageText)
        assertEquals("Taken", items.first().statusLabel)

        job.cancel()
    }

    @Test
    fun `skipped doses are reflected in history list and adherence denominator`() = runTest {
        val skippedDose = DoseEvent(
            id = "dose_skipped",
            medicineId = sampleMed.id,
            scheduledAt = testNowMillis - 3600000L,
            windowEndsAt = testNowMillis,
            status = DoseStatus.SKIPPED
        )
        val takenDose = DoseEvent(
            id = "dose_taken_on_time",
            medicineId = sampleMed.id,
            scheduledAt = testNowMillis - 7200000L,
            windowEndsAt = testNowMillis - 3600000L,
            status = DoseStatus.TAKEN
        )
        every { doseRepository.observeBetween(any(), any()) } returns flowOf(listOf(skippedDose, takenDose))

        val vm = createViewModel()
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val items = vm.uiState.value.items
        assertEquals(2, items.size)
        val skippedItem = items.first { it.dose.id == "dose_skipped" }
        assertEquals("Skipped", skippedItem.statusLabel)

        val report = vm.uiState.value.adherenceReport
        assertEquals(1, report.verifiedTakenCount)
        assertEquals(1, report.skippedCount)
        assertEquals(2, report.closedDosesCount)
        assertEquals(50.0, report.percentage!!, 0.001)

        job.cancel()
    }
}
