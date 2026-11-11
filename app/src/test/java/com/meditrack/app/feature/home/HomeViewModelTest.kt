package com.meditrack.app.feature.home

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)

    private val testZoneId = ZoneId.of("UTC")
    // Base time: 2026-11-01 at 22:00:00 (10:00 PM) UTC
    private val baseDateTime = ZonedDateTime.of(
        LocalDate.of(2026, 11, 1),
        LocalTime.of(22, 0),
        testZoneId
    )
    private val baseEpochMillis = baseDateTime.toInstant().toEpochMilli()

    private val sampleUser = User(
        id = "user_1",
        name = "Alice",
        role = UserRole.PATIENT
    )

    private val sampleMedicine = Medicine(
        id = "med_1",
        name = "Aspirin",
        dosageText = "100mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = baseEpochMillis - (7 * 86400000L),
        initialStock = 30,
        currentStock = 25
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { authRepository.observeCurrentUser() } returns flowOf(sampleUser)
        coEvery { medicineRepository.observeActive() } returns flowOf(listOf(sampleMedicine))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createDose(
        id: String,
        scheduledAt: Long,
        status: DoseStatus
    ) = DoseEvent(
        id = id,
        medicineId = "med_1",
        scheduledAt = scheduledAt,
        windowEndsAt = scheduledAt + (60 * 60 * 1000L),
        status = status
    )

    @Test
    fun `upcoming doses include next day doses across midnight within 24 hours`() = runTest(testDispatcher) {
        val doseFlow = MutableStateFlow<List<DoseEvent>>(emptyList())
        coEvery { doseRepository.observeBetween(any(), any()) } returns doseFlow

        val viewModel = HomeViewModel(authRepository, medicineRepository, doseRepository)
        viewModel.timeProvider = { baseEpochMillis } // 10:00 PM today
        viewModel.zoneProvider = { testZoneId }
        viewModel.loadDashboard()
        testDispatcher.scheduler.advanceUntilIdle()

        // Dose 1: Today at 23:00 (11:00 PM) - 1 hour ahead
        val doseToday = createDose("d1", baseEpochMillis + (1 * 3600000L), DoseStatus.PENDING)
        // Dose 2: Tomorrow at 08:00 AM - 10 hours ahead (across midnight!)
        val doseTomorrowMorning = createDose("d2", baseEpochMillis + (10 * 3600000L), DoseStatus.PENDING)
        // Dose 3: Day after tomorrow at 23:00 - 25 hours ahead (outside 24h window)
        val doseFarFuture = createDose("d3", baseEpochMillis + (25 * 3600000L), DoseStatus.PENDING)

        doseFlow.value = listOf(doseToday, doseTomorrowMorning, doseFarFuture)
        testDispatcher.scheduler.advanceUntilIdle()

        val upcoming = viewModel.uiState.value.upcomingDoses
        // Must include today at 11pm and tomorrow at 8am, but NOT 25 hours ahead
        assertEquals(2, upcoming.size)
        assertTrue(upcoming.any { it.id == "d1" })
        assertTrue(upcoming.any { it.id == "d2" })
        assertFalse(upcoming.any { it.id == "d3" })

        // Today doses should only include d1 (before midnight)
        val todayDoses = viewModel.uiState.value.todayDoses
        assertEquals(1, todayDoses.size)
        assertEquals("d1", todayDoses[0].id)
    }

    @Test
    fun `missed doses include yesterday missed dose within 24 hours across midnight`() = runTest(testDispatcher) {
        val doseFlow = MutableStateFlow<List<DoseEvent>>(emptyList())
        coEvery { doseRepository.observeBetween(any(), any()) } returns doseFlow

        // Current time: 08:00 AM on 2026-11-02
        val morningDateTime = ZonedDateTime.of(
            LocalDate.of(2026, 11, 2),
            LocalTime.of(8, 0),
            testZoneId
        )
        val morningEpochMillis = morningDateTime.toInstant().toEpochMilli()

        val viewModel = HomeViewModel(authRepository, medicineRepository, doseRepository)
        viewModel.timeProvider = { morningEpochMillis }
        viewModel.zoneProvider = { testZoneId }
        viewModel.loadDashboard()
        testDispatcher.scheduler.advanceUntilIdle()

        // Dose from yesterday at 22:00 (10 hours ago - within 24h window, but before today midnight!)
        val missedYesterday = createDose("missed_yesterday", morningEpochMillis - (10 * 3600000L), DoseStatus.MISSED)
        // Dose from 23 hours ago (still within 24h window)
        val missedWithinWindow = createDose("missed_23h", morningEpochMillis - (23 * 3600000L), DoseStatus.MISSED)
        // Dose from 25 hours ago (expired beyond 24h late cutoff)
        val missedExpired = createDose("missed_expired", morningEpochMillis - (25 * 3600000L), DoseStatus.MISSED)

        doseFlow.value = listOf(missedYesterday, missedWithinWindow, missedExpired)
        testDispatcher.scheduler.advanceUntilIdle()

        val missed = viewModel.uiState.value.missedDoses
        assertEquals(2, missed.size)
        assertTrue(missed.any { it.id == "missed_yesterday" })
        assertTrue(missed.any { it.id == "missed_23h" })
        assertFalse(missed.any { it.id == "missed_expired" })
    }

    @Test
    fun `missed doses exclude non-missed doses and upcoming doses exclude non-pending doses`() = runTest(testDispatcher) {
        val doseFlow = MutableStateFlow<List<DoseEvent>>(emptyList())
        coEvery { doseRepository.observeBetween(any(), any()) } returns doseFlow

        val viewModel = HomeViewModel(authRepository, medicineRepository, doseRepository)
        viewModel.timeProvider = { baseEpochMillis }
        viewModel.zoneProvider = { testZoneId }
        viewModel.loadDashboard()
        testDispatcher.scheduler.advanceUntilIdle()

        val takenDose = createDose("taken_1", baseEpochMillis + (2 * 3600000L), DoseStatus.TAKEN)
        val skippedDose = createDose("skipped_1", baseEpochMillis - (2 * 3600000L), DoseStatus.SKIPPED)

        doseFlow.value = listOf(takenDose, skippedDose)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.upcomingDoses.isEmpty())
        assertTrue(viewModel.uiState.value.missedDoses.isEmpty())
    }

    @Test
    fun `user with doctor role correctly propagates to uiState`() = runTest(testDispatcher) {
        val doctorUser = User(id = "doc_1", name = "Dr. Strange", role = UserRole.DOCTOR)
        coEvery { authRepository.observeCurrentUser() } returns flowOf(doctorUser)
        coEvery { doseRepository.observeBetween(any(), any()) } returns flowOf(emptyList())

        val viewModel = HomeViewModel(authRepository, medicineRepository, doseRepository)
        viewModel.timeProvider = { baseEpochMillis }
        viewModel.zoneProvider = { testZoneId }
        viewModel.loadDashboard()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UserRole.DOCTOR, viewModel.uiState.value.user?.role)
        assertEquals("Dr. Strange", viewModel.uiState.value.user?.name)
    }
}
