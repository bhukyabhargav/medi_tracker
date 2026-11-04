package com.meditrack.app.feature.doctor

import android.content.Context
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.PrescriptionPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.ProposalRepository
import com.meditrack.app.domain.usecase.AcceptProposalUseCase
import com.meditrack.app.domain.usecase.DeclineProposalUseCase
import com.meditrack.app.domain.usecase.RequestClarificationUseCase
import com.meditrack.app.domain.usecase.ReviseProposalUseCase
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
class DoctorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val doctorRepository: DoctorRepository = mockk(relaxed = true)
    private val proposalRepository: ProposalRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val acceptProposalUseCase: AcceptProposalUseCase = mockk(relaxed = true)
    private val declineProposalUseCase: DeclineProposalUseCase = mockk(relaxed = true)
    private val requestClarificationUseCase: RequestClarificationUseCase = mockk(relaxed = true)
    private val reviseProposalUseCase: ReviseProposalUseCase = mockk(relaxed = true)

    private lateinit var viewModel: DoctorViewModel

    private val sampleDoctor = User(id = "doc_1", name = "Dr. Adams", role = UserRole.DOCTOR)
    private val sampleLink = DoctorLink(
        id = "link_1",
        patientId = "pat_1",
        doctorUid = "doc_1",
        doctorName = "Dr. Adams",
        doctorContact = "adams@clinic.com",
        status = DoctorLinkStatus.PENDING
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        coEvery { authRepository.getCurrentUser() } returns sampleDoctor
        coEvery { userPreferences.currentUserId } returns flowOf("doc_1")
        coEvery { doctorRepository.observeAcceptedForDoctor("doc_1") } returns flowOf(emptyList())
        coEvery { doctorRepository.observeByPatientId("doc_1") } returns flowOf(emptyList())
        coEvery { proposalRepository.observeAll() } returns flowOf(emptyList())
        coEvery { doctorRepository.getById("link_1") } returns sampleLink

        viewModel = DoctorViewModel(
            context = context,
            doctorRepository = doctorRepository,
            proposalRepository = proposalRepository,
            medicineRepository = medicineRepository,
            doseRepository = doseRepository,
            outboxRepository = outboxRepository,
            authRepository = authRepository,
            userPreferences = userPreferences,
            acceptProposalUseCase = acceptProposalUseCase,
            declineProposalUseCase = declineProposalUseCase,
            requestClarificationUseCase = requestClarificationUseCase,
            reviseProposalUseCase = reviseProposalUseCase
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun generateDoctorInvite_createsFormattedCode() {
        viewModel.generateDoctorInvite()

        val code = viewModel.uiState.value.generatedInviteCode
        assertNotNull(code)
        assertTrue(code!!.startsWith("DOC-"))
    }

    @Test
    fun patientConnectWithCode_createsPendingDoctorLink_andEnqueuesOutbox() = runTest {
        val linkSlot = slot<DoctorLink>()
        coEvery { doctorRepository.insert(capture(linkSlot)) } returns Unit

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        viewModel.patientConnectWithCode("DOC-1234", "Dr. Adams")
        advanceUntilIdle()

        coVerify(exactly = 1) { doctorRepository.insert(any()) }
        assertEquals(DoctorLinkStatus.PENDING, linkSlot.captured.status)
        assertEquals("Dr. Adams", linkSlot.captured.doctorName)

        coVerify(exactly = 1) { outboxRepository.enqueue(any()) }
        assertEquals(NotifType.DOCTOR_INVITE_RECEIVED, outboxSlot.captured.type)
    }

    @Test
    fun acceptDoctorConnection_setsAcceptedStatus_andEnqueuesOutbox() = runTest {
        val linkSlot = slot<DoctorLink>()
        coEvery { doctorRepository.update(capture(linkSlot)) } returns Unit

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        viewModel.acceptDoctorConnection("link_1")
        advanceUntilIdle()

        coVerify(exactly = 1) { doctorRepository.update(any()) }
        assertEquals(DoctorLinkStatus.ACCEPTED, linkSlot.captured.status)

        coVerify(exactly = 1) { outboxRepository.enqueue(any()) }
        assertEquals(NotifType.DOCTOR_INVITE_ACCEPTED, outboxSlot.captured.type)
    }

    @Test
    fun declineDoctorConnection_setsDeclinedStatus_andEnqueuesOutbox() = runTest {
        val linkSlot = slot<DoctorLink>()
        coEvery { doctorRepository.update(capture(linkSlot)) } returns Unit

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        viewModel.declineDoctorConnection("link_1")
        advanceUntilIdle()

        coVerify(exactly = 1) { doctorRepository.update(any()) }
        assertEquals(DoctorLinkStatus.DECLINED, linkSlot.captured.status)

        coVerify(exactly = 1) { outboxRepository.enqueue(any()) }
        assertEquals(NotifType.DOCTOR_INVITE_DECLINED, outboxSlot.captured.type)
    }

    @Test
    fun revokeDoctorAccess_setsRevokedStatus_andEnqueuesOutbox() = runTest {
        val linkSlot = slot<DoctorLink>()
        coEvery { doctorRepository.update(capture(linkSlot)) } returns Unit

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        viewModel.revokeDoctorAccess("link_1")
        advanceUntilIdle()

        coVerify(exactly = 1) { doctorRepository.update(any()) }
        assertEquals(DoctorLinkStatus.REVOKED, linkSlot.captured.status)

        coVerify(exactly = 1) { outboxRepository.enqueue(any()) }
        assertEquals(NotifType.DOCTOR_INVITE_REVOKED, outboxSlot.captured.type)
    }

    @Test
    fun selectPatient_loadsMedicinesDosesAndAdherenceReadOnly() = runTest {
        val doses = listOf(
            DoseEvent(id = "d1", medicineId = "m1", scheduledAt = 1000L, windowEndsAt = 2000L, status = DoseStatus.TAKEN),
            DoseEvent(id = "d2", medicineId = "m1", scheduledAt = 3000L, windowEndsAt = 4000L, status = DoseStatus.MISSED)
        )
        coEvery { doseRepository.observeBetween(any(), any()) } returns flowOf(doses)
        coEvery { medicineRepository.observeAll() } returns flowOf(emptyList())

        viewModel.selectPatient("pat_1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("pat_1", state.selectedPatientId)
        assertEquals(2, state.selectedPatientDoses.size)
        assertNotNull(state.selectedPatientAdherence)
        assertEquals(50.0, state.selectedPatientAdherence?.percentage ?: 0.0, 0.01)
    }

    @Test
    fun createPrescriptionProposal_createsPendingProposal_andEnqueuesOutbox() = runTest {
        val proposalSlot = slot<MedicineProposal>()
        coEvery { proposalRepository.insert(capture(proposalSlot)) } returns Unit

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        val payload = PrescriptionPayload(
            medicineName = "Amoxicillin",
            dosageText = "500mg",
            startDate = "2026-11-05"
        )
        viewModel.createPrescriptionProposal("pat_1", ProposalType.ADD, payload)
        advanceUntilIdle()

        coVerify(exactly = 1) { proposalRepository.insert(any()) }
        assertEquals(ProposalStatus.PENDING, proposalSlot.captured.status)
        assertEquals(ProposalType.ADD, proposalSlot.captured.type)

        coVerify(exactly = 1) { outboxRepository.enqueue(any()) }
        assertEquals(NotifType.PRESCRIPTION_PROPOSAL_RECEIVED, outboxSlot.captured.type)
    }
}
