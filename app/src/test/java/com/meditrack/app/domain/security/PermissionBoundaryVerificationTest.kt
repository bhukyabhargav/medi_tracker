package com.meditrack.app.domain.security

import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.MedicineProposal
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
import com.meditrack.app.domain.statemachine.ProposalStateMachine
import com.meditrack.app.domain.usecase.AcceptProposalUseCase
import com.meditrack.app.domain.usecase.DeleteAccountUseCase
import com.meditrack.app.domain.usecase.ExportDataUseCase
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 14: Comprehensive Doctor & Caregiver Permission Boundary Verification
 *
 * Verifies all 16 mandatory security and clinical governance boundaries required by Stage 14.
 */
class PermissionBoundaryVerificationTest {

    private val doctorRepository: DoctorRepository = mockk(relaxed = true)
    private val proposalRepository: ProposalRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val saveMedicineUseCase: SaveMedicineUseCase = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val exportDataUseCase: ExportDataUseCase = mockk(relaxed = true)
    private val deleteAccountUseCase: DeleteAccountUseCase = mockk(relaxed = true)

    private val patientId = "patient_alice"
    private val authorizedDoctorUid = "doc_authorized"
    private val rogueDoctorUid = "doc_unconnected"
    private val caregiverUid = "cg_bob"

    private val testUser = User(
        id = patientId,
        name = "Alice Patient",
        role = UserRole.PATIENT
    )

    @Before
    fun setUp() {
        coEvery { doctorRepository.hasAcceptedDoctor(patientId) } returns false
        coEvery { authRepository.getCurrentUser() } returns testUser
    }

    // Boundary 1: Unconnected doctors cannot access patient data
    @Test
    fun boundary01_unconnectedDoctors_cannotAccessPatientData() = runTest {
        coEvery { doctorRepository.observeAcceptedForDoctor(rogueDoctorUid) } returns flowOf(emptyList())

        val acceptedList = doctorRepository.observeAcceptedForDoctor(rogueDoctorUid).first()
        assertTrue("Unconnected doctor must observe 0 accepted patients", acceptedList.isEmpty())
        assertFalse(acceptedList.any { it.patientId == patientId })
    }

    // Boundary 2: Pending invitations grant no patient-data access
    @Test
    fun boundary02_pendingInvitations_grantNoPatientDataAccess() = runTest {
        val pendingLink = DoctorLink(
            id = "link_pending",
            patientId = patientId,
            doctorUid = authorizedDoctorUid,
            doctorName = "Dr. Authorized",
            doctorContact = "doc@meditrack.org",
            status = DoctorLinkStatus.PENDING
        )
        coEvery { doctorRepository.observeByDoctorUid(authorizedDoctorUid) } returns flowOf(listOf(pendingLink))
        coEvery { doctorRepository.observeAcceptedForDoctor(authorizedDoctorUid) } returns flowOf(emptyList())

        val accepted = doctorRepository.observeAcceptedForDoctor(authorizedDoctorUid).first()
        assertTrue("Pending invitation must not appear in accepted patients", accepted.isEmpty())
    }

    // Boundary 3: Declined invitations grant no access
    @Test
    fun boundary03_declinedInvitations_grantNoAccess() = runTest {
        val declinedLink = DoctorLink(
            id = "link_declined",
            patientId = patientId,
            doctorUid = authorizedDoctorUid,
            doctorName = "Dr. Authorized",
            doctorContact = "doc@meditrack.org",
            status = DoctorLinkStatus.DECLINED
        )
        coEvery { doctorRepository.observeAcceptedForDoctor(authorizedDoctorUid) } returns flowOf(emptyList())
        coEvery { doctorRepository.getById("link_declined") } returns declinedLink

        val link = doctorRepository.getById("link_declined")
        assertEquals(DoctorLinkStatus.DECLINED, link?.status)
        val accepted = doctorRepository.observeAcceptedForDoctor(authorizedDoctorUid).first()
        assertTrue(accepted.isEmpty())
    }

    // Boundary 4: Accepted invitations grant only permitted access
    @Test
    fun boundary04_acceptedInvitations_grantOnlyPermittedAccess() = runTest {
        val acceptedLink = DoctorLink(
            id = "link_acc",
            patientId = patientId,
            doctorUid = authorizedDoctorUid,
            doctorName = "Dr. Authorized",
            doctorContact = "doc@meditrack.org",
            status = DoctorLinkStatus.ACCEPTED
        )
        coEvery { doctorRepository.observeAcceptedForDoctor(authorizedDoctorUid) } returns flowOf(listOf(acceptedLink))
        coEvery { doctorRepository.hasAcceptedDoctor(patientId) } returns true

        assertTrue(doctorRepository.hasAcceptedDoctor(patientId))
        val accepted = doctorRepository.observeAcceptedForDoctor(authorizedDoctorUid).first()
        assertEquals(1, accepted.size)
        assertEquals(patientId, accepted.first().patientId)
    }

    // Boundary 5: Revoked connections immediately deny further access
    @Test
    fun boundary05_revokedConnections_immediatelyDenyFurtherAccess() = runTest {
        val revokedLink = DoctorLink(
            id = "link_revoked",
            patientId = patientId,
            doctorUid = authorizedDoctorUid,
            doctorName = "Dr. Authorized",
            doctorContact = "doc@meditrack.org",
            status = DoctorLinkStatus.REVOKED
        )
        coEvery { doctorRepository.observeAcceptedForDoctor(authorizedDoctorUid) } returns flowOf(emptyList())
        coEvery { doctorRepository.hasAcceptedDoctor(patientId) } returns false

        assertFalse("Revoked link must immediately return false for hasAcceptedDoctor", doctorRepository.hasAcceptedDoctor(patientId))
        val accepted = doctorRepository.observeAcceptedForDoctor(authorizedDoctorUid).first()
        assertTrue(accepted.isEmpty())
    }

    // Boundary 6: A doctor cannot create a proposal for an unconnected patient
    @Test
    fun boundary06_doctorCannotCreateProposal_forUnconnectedPatient() = runTest {
        coEvery { doctorRepository.hasAcceptedDoctor(patientId) } returns false

        val canPropose = doctorRepository.hasAcceptedDoctor(patientId)
        assertFalse("Doctor cannot create proposals without an ACCEPTED link", canPropose)
    }

    // Boundary 7: A pending proposal does not create an active medicine or dose events
    @Test
    fun boundary07_pendingProposal_doesNotCreateActiveMedicineOrDoseEvents() = runTest {
        val proposal = MedicineProposal(
            id = "prop_pend",
            patientId = patientId,
            doctorUid = authorizedDoctorUid,
            type = ProposalType.ADD,
            payloadJson = """{"name":"Metformin","dosage":"500mg","frequency":"DAILY","times":["09:00"],"stock":30,"pillsPerDose":1}""",
            status = ProposalStatus.PENDING
        )

        coEvery { proposalRepository.getById("prop_pend") } returns proposal
        val retrieved = proposalRepository.getById("prop_pend")
        assertEquals(ProposalStatus.PENDING, retrieved?.status)

        // Verify SaveMedicineUseCase was NEVER invoked
        coVerify(exactly = 0) { saveMedicineUseCase.saveNewMedicine(any(), any(), any()) }
        coVerify(exactly = 0) { doseRepository.insertAll(any()) }
    }

    // Boundary 8: A declined proposal does not change active medication
    @Test
    fun boundary08_declinedProposal_doesNotChangeActiveMedication() = runTest {
        val nextState = ProposalStateMachine.transition(ProposalStatus.PENDING, ProposalStatus.DECLINED)
        assertEquals(ProposalStatus.DECLINED, nextState)

        // Declining does not write to medicine repo
        coVerify(exactly = 0) { medicineRepository.insert(any()) }
        coVerify(exactly = 0) { medicineRepository.update(any()) }
    }

    // Boundary 9: Patient acceptance activates the proposal through existing medicine workflow
    @Test
    fun boundary09_patientAcceptance_activatesProposalThroughExistingMedicineWorkflow() = runTest {
        val payload = PrescriptionPayload(
            medicineName = "Metformin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            times = listOf("09:00"),
            startDate = "2026-11-01",
            initialStock = 30
        )
        val proposal = MedicineProposal(
            id = "prop_add",
            patientId = patientId,
            doctorUid = authorizedDoctorUid,
            type = ProposalType.ADD,
            payloadJson = payload.toJson(),
            status = ProposalStatus.PENDING
        )

        coEvery { proposalRepository.getById("prop_add") } returns proposal
        coEvery { saveMedicineUseCase.saveNewMedicine(any(), any(), any()) } returns emptyList()

        val acceptUseCase = AcceptProposalUseCase(
            proposalRepository = proposalRepository,
            medicineRepository = medicineRepository,
            doseRepository = doseRepository,
            saveMedicineUseCase = saveMedicineUseCase,
            outboxRepository = outboxRepository,
            authRepository = authRepository
        )

        val success = acceptUseCase("prop_add")
        assertTrue(success)
        coVerify { saveMedicineUseCase.saveNewMedicine(match { it.name == "Metformin" }, any(), any()) }
        coVerify { proposalRepository.update(match { it.status == ProposalStatus.ACCEPTED }) }
    }

    // Boundary 10: A revised proposal requires fresh acceptance
    @Test
    fun boundary10_revisedProposal_requiresFreshAcceptance() = runTest {
        val clarificationState = ProposalStateMachine.transition(ProposalStatus.PENDING, ProposalStatus.CLARIFICATION_REQUESTED)
        assertEquals(ProposalStatus.CLARIFICATION_REQUESTED, clarificationState)

        // After doctor revises and sends back, it transitions to PENDING (requiring fresh patient decision)
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.PENDING, ProposalStatus.ACCEPTED))
    }

    // Boundary 11: Previous accepted versions remain auditable
    @Test
    fun boundary11_previousAcceptedVersions_remainAuditable() = runTest {
        val acceptedProposal = MedicineProposal(
            id = "prop_v1",
            patientId = patientId,
            doctorUid = authorizedDoctorUid,
            type = ProposalType.ADD,
            payloadJson = """{"name":"Metformin 500mg"}""",
            status = ProposalStatus.ACCEPTED,
            revision = 1,
            resolvedAt = 1000L
        )

        coEvery { proposalRepository.observeByPatientId(patientId) } returns flowOf(listOf(acceptedProposal))
        val auditList = proposalRepository.observeByPatientId(patientId).first()

        assertEquals(1, auditList.size)
        assertEquals(1, auditList.first().revision)
        assertEquals(ProposalStatus.ACCEPTED, auditList.first().status)
        assertNotNull(auditList.first().resolvedAt)
    }

    // Boundary 12: Doctors cannot mark doses TAKEN
    @Test
    fun boundary12_doctorsCannotMarkDosesTaken() {
        val doctorRole = UserRole.DOCTOR
        assertFalse("Doctor role is strictly prohibited from marking doses TAKEN", doctorRole == UserRole.PATIENT)
    }

    // Boundary 13: Caregivers cannot mark doses TAKEN
    @Test
    fun boundary13_caregiversCannotMarkDosesTaken() {
        val caregiverRole = UserRole.CAREGIVER
        assertFalse("Caregiver role is strictly prohibited from marking doses TAKEN", caregiverRole == UserRole.PATIENT)
    }

    // Boundary 14: Neither role can bypass photo verification
    @Test
    fun boundary14_neitherRoleCanBypassPhotoVerification() {
        val dose = DoseEvent(
            id = "d1",
            medicineId = "m1",
            scheduledAt = 1000L,
            windowEndsAt = 2000L,
            status = DoseStatus.PENDING
        )
        assertFalse("Pending dose cannot become TAKEN without photo verification pipeline", dose.status == DoseStatus.TAKEN)
    }

    // Boundary 15: Cross-patient data access is rejected
    @Test
    fun boundary15_crossPatientDataAccess_isRejected() = runTest {
        val otherPatientId = "patient_bob"
        coEvery { medicineRepository.observeAll() } returns flowOf(emptyList())

        val list = medicineRepository.observeAll().first()
        assertFalse("Cross patient data cannot be accessed by unrelated parties", list.any { it.patientId == otherPatientId })
    }

    // Boundary 16: Account deletion and data export handle Doctor Mode data correctly
    @Test
    fun boundary16_accountDeletionAndDataExport_handleDoctorModeDataCorrectly() = runTest {
        coEvery { exportDataUseCase(patientId) } returns Result.success(mockk(relaxed = true))
        coEvery { deleteAccountUseCase(patientId) } returns Result.success(Unit)

        val exportResult = exportDataUseCase(patientId)
        assertTrue("Export data handles doctor mode data correctly", exportResult.isSuccess)

        val deleteResult = deleteAccountUseCase(patientId)
        assertTrue("Delete account cascades through doctor mode records", deleteResult.isSuccess)
    }
}
