package com.meditrack.app.feature.caregiver

import android.content.Context
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.LinkStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)

    private lateinit var viewModel: CaregiverViewModel

    private val sampleUser = User(
        id = "patient_1",
        name = "Bob",
        email = "bob@example.com",
        role = UserRole.PATIENT
    )

    private val sampleLink = CaregiverLink(
        id = "link_123",
        patientId = "patient_1",
        caregiverName = "Sarah",
        caregiverContact = "+1-555-0100",
        relationship = "Daughter",
        status = LinkStatus.PENDING
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        coEvery { authRepository.getCurrentUser() } returns sampleUser
        coEvery { authRepository.getUserByContact(any()) } returns null
        coEvery { userPreferences.currentUserId } returns flowOf("patient_1")
        coEvery { caregiverRepository.observeByPatientId("patient_1") } returns flowOf(listOf(sampleLink))
        coEvery { caregiverRepository.observeAll() } returns flowOf(listOf(sampleLink))
        coEvery { caregiverRepository.getById("link_123") } returns sampleLink

        viewModel = CaregiverViewModel(
            context = context,
            caregiverRepository = caregiverRepository,
            outboxRepository = outboxRepository,
            authRepository = authRepository,
            doseRepository = doseRepository,
            medicineRepository = medicineRepository,
            userPreferences = userPreferences
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sendInvitation_insertsPendingCaregiverLink_andEnqueuesOutbox() = runTest {
        val linkSlot = slot<CaregiverLink>()
        coEvery { caregiverRepository.insert(capture(linkSlot)) } returns Unit

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        viewModel.sendInvitation("Alice", "alice@example.com", "Spouse")
        advanceUntilIdle()

        coVerify(exactly = 1) { caregiverRepository.insert(any()) }
        assertEquals("Alice", linkSlot.captured.caregiverName)
        assertEquals("alice@example.com", linkSlot.captured.caregiverContact)
        assertEquals(LinkStatus.PENDING, linkSlot.captured.status)

        coVerify(exactly = 1) { outboxRepository.enqueue(any()) }
        assertEquals(NotifType.CAREGIVER_INVITE, outboxSlot.captured.type)
        assertTrue(outboxSlot.captured.payloadJson.contains("Alice"))
    }

    @Test
    fun simulateCaregiverResponse_accept_updatesStatusToAccepted() = runTest {
        val updateSlot = slot<CaregiverLink>()
        coEvery { caregiverRepository.update(capture(updateSlot)) } returns Unit

        viewModel.simulateCaregiverResponse("link_123", accept = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { caregiverRepository.update(any()) }
        assertEquals(LinkStatus.ACCEPTED, updateSlot.captured.status)
        assertNotNull(updateSlot.captured.caregiverUid)
    }

    @Test
    fun simulateCaregiverResponse_reject_remainsPending() = runTest {
        val updateSlot = slot<CaregiverLink>()
        coEvery { caregiverRepository.update(capture(updateSlot)) } returns Unit

        viewModel.simulateCaregiverResponse("link_123", accept = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { caregiverRepository.update(any()) }
        assertEquals(LinkStatus.PENDING, updateSlot.captured.status)
        assertEquals("Invitation remains pending", viewModel.uiState.value.userMessage)
    }

    @Test
    fun selectPatient_computesAdherenceAndLoadsDoses() = runTest {
        val doses = listOf(
            DoseEvent(id = "1", medicineId = "m1", patientId = "patient_1", scheduledAt = 1000L, windowEndsAt = 2000L, status = DoseStatus.TAKEN),
            DoseEvent(id = "2", medicineId = "m1", patientId = "patient_1", scheduledAt = 3000L, windowEndsAt = 4000L, status = DoseStatus.MISSED)
        )
        coEvery { doseRepository.observeBetween(any(), any()) } returns flowOf(doses)
        coEvery { medicineRepository.observeAll() } returns flowOf(emptyList())

        viewModel.selectPatient("patient_1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("patient_1", state.selectedPatientId)
        assertEquals(2, state.selectedPatientDoses.size)
        assertNotNull(state.adherenceReport)
        assertEquals(50.0, state.adherenceReport?.percentage ?: 0.0, 0.01)
    }

    @Test
    fun acceptInviteCode_offlineSimulation_createsAcceptedLink() = runTest {
        val linkSlot = slot<CaregiverLink>()
        coEvery { caregiverRepository.insert(capture(linkSlot)) } returns Unit

        viewModel.acceptInviteCode("INVITE123")
        advanceUntilIdle()

        coVerify(exactly = 1) { caregiverRepository.insert(any()) }
        assertEquals(LinkStatus.ACCEPTED, linkSlot.captured.status)
        assertEquals("link_INVITE123", linkSlot.captured.id)
    }
}
