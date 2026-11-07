package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
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
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.ProposalRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class PrescriptionProposalWorkflowTest {

    private val proposalRepository: ProposalRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val saveMedicineUseCase: SaveMedicineUseCase = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)

    private lateinit var acceptUseCase: AcceptProposalUseCase
    private lateinit var declineUseCase: DeclineProposalUseCase
    private lateinit var requestClarificationUseCase: RequestClarificationUseCase
    private lateinit var reviseUseCase: ReviseProposalUseCase

    private val sampleUser = User(id = "pat_1", name = "Alice", role = UserRole.PATIENT)

    private val addPayload = PrescriptionPayload(
        medicineName = "Amoxicillin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        times = listOf("08:00", "20:00"),
        startDate = "2026-11-05",
        doctorName = "Dr. Smith"
    )

    private val sampleAddProposal = MedicineProposal(
        id = "prop_1",
        patientId = "pat_1",
        doctorUid = "doc_1",
        type = ProposalType.ADD,
        medicineId = "med_1",
        payloadJson = addPayload.toJson(),
        status = ProposalStatus.PENDING
    )

    @Before
    fun setUp() {
        coEvery { authRepository.getCurrentUser() } returns sampleUser
        coEvery { proposalRepository.getById("prop_1") } returns sampleAddProposal

        acceptUseCase = AcceptProposalUseCase(
            proposalRepository = proposalRepository,
            medicineRepository = medicineRepository,
            doseRepository = doseRepository,
            saveMedicineUseCase = saveMedicineUseCase,
            outboxRepository = outboxRepository,
            authRepository = authRepository
        )

        declineUseCase = DeclineProposalUseCase(
            proposalRepository = proposalRepository,
            outboxRepository = outboxRepository,
            authRepository = authRepository
        )

        requestClarificationUseCase = RequestClarificationUseCase(
            proposalRepository = proposalRepository,
            outboxRepository = outboxRepository,
            authRepository = authRepository
        )

        reviseUseCase = ReviseProposalUseCase(
            proposalRepository = proposalRepository,
            outboxRepository = outboxRepository
        )
    }

    @Test
    fun acceptAddProposal_createsMedicineAndFutureDoses_andMarksAccepted() = runTest {
        val medicineSlot = slot<Medicine>()
        coEvery { saveMedicineUseCase.saveNewMedicine(capture(medicineSlot)) } returns emptyList()

        val proposalSlot = slot<MedicineProposal>()
        coEvery { proposalRepository.update(capture(proposalSlot)) } returns Unit

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        val success = acceptUseCase("prop_1")

        assertTrue(success)
        coVerify(exactly = 1) { saveMedicineUseCase.saveNewMedicine(any()) }
        assertEquals("Amoxicillin", medicineSlot.captured.name)
        assertEquals("doc_1", medicineSlot.captured.prescribedByDoctorUid)
        assertEquals("Dr. Smith", medicineSlot.captured.prescribedByDoctorName)

        coVerify(exactly = 1) { proposalRepository.update(any()) }
        assertEquals(ProposalStatus.ACCEPTED, proposalSlot.captured.status)

        coVerify(exactly = 1) { outboxRepository.enqueue(any()) }
        assertEquals(NotifType.PRESCRIPTION_ACCEPTED, outboxSlot.captured.type)
    }

    @Test
    fun acceptEditProposal_updatesMedicine_affectsFutureDosesOnly() = runTest {
        val existingMedicine = Medicine(
            id = "med_1",
            patientId = "pat_1",
            name = "Amoxicillin",
            dosageText = "250mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = 1000L,
            initialStock = 20,
            currentStock = 15,
            prescribedByDoctorUid = "doc_1"
        )
        val editProposal = sampleAddProposal.copy(
            id = "prop_edit",
            type = ProposalType.EDIT,
            medicineId = "med_1",
            payloadJson = addPayload.copy(dosageText = "500mg").toJson()
        )
        coEvery { proposalRepository.getById("prop_edit") } returns editProposal
        coEvery { medicineRepository.getById("med_1") } returns existingMedicine

        val updatedSlot = slot<Medicine>()
        coEvery { saveMedicineUseCase.updateMedicine(capture(updatedSlot)) } returns emptyList()

        val success = acceptUseCase("prop_edit")

        assertTrue(success)
        coVerify(exactly = 1) { saveMedicineUseCase.updateMedicine(any()) }
        assertEquals("500mg", updatedSlot.captured.dosageText)
    }

    @Test
    fun acceptStopProposal_stopsMedicine_deletesFuturePendingDoses_preservesHistory() = runTest {
        val existingMedicine = Medicine(
            id = "med_1",
            patientId = "pat_1",
            name = "Amoxicillin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = 1000L,
            initialStock = 20,
            currentStock = 15,
            prescribedByDoctorUid = "doc_1"
        )
        val stopProposal = sampleAddProposal.copy(
            id = "prop_stop",
            type = ProposalType.STOP,
            medicineId = "med_1"
        )
        coEvery { proposalRepository.getById("prop_stop") } returns stopProposal
        coEvery { medicineRepository.getById("med_1") } returns existingMedicine

        val success = acceptUseCase("prop_stop")

        assertTrue(success)
        coVerify(exactly = 1) { medicineRepository.update(match { it.stoppedByDoctor }) }
        coVerify(exactly = 1) { doseRepository.deleteFuturePendingDoses("med_1", any()) }
    }

    @Test
    fun acceptProposal_invalidPayload_rejectsAndDoesNotInsert() = runTest {
        val invalidPayload = addPayload.copy(medicineName = "   ")
        val invalidProposal = sampleAddProposal.copy(
            id = "prop_invalid",
            payloadJson = invalidPayload.toJson()
        )
        coEvery { proposalRepository.getById("prop_invalid") } returns invalidProposal

        val success = acceptUseCase("prop_invalid")

        assertFalse(success)
        coVerify(exactly = 0) { saveMedicineUseCase.saveNewMedicine(any()) }
        coVerify(exactly = 0) { proposalRepository.update(any()) }
    }

    @Test
    fun acceptProposal_doubleAccept_isIdempotent() = runTest {
        val alreadyAccepted = sampleAddProposal.copy(
            id = "prop_accepted",
            status = ProposalStatus.ACCEPTED
        )
        coEvery { proposalRepository.getById("prop_accepted") } returns alreadyAccepted

        val success = acceptUseCase("prop_accepted")

        assertTrue(success)
        coVerify(exactly = 0) { saveMedicineUseCase.saveNewMedicine(any()) }
        coVerify(exactly = 0) { proposalRepository.update(any()) }
    }

    @Test
    fun acceptProposal_patientMismatch_fails() = runTest {
        val otherPatientProposal = sampleAddProposal.copy(
            id = "prop_other",
            patientId = "other_patient_99"
        )
        coEvery { proposalRepository.getById("prop_other") } returns otherPatientProposal

        val success = acceptUseCase("prop_other")

        assertFalse(success)
        coVerify(exactly = 0) { saveMedicineUseCase.saveNewMedicine(any()) }
    }

    @Test
    fun acceptEditProposal_doctorMismatch_fails() = runTest {
        val existingMedicine = Medicine(
            id = "med_1",
            patientId = "pat_1",
            name = "Amoxicillin",
            dosageText = "250mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = 1000L,
            initialStock = 20,
            currentStock = 15,
            prescribedByDoctorUid = "different_doctor_42"
        )
        val editProposal = sampleAddProposal.copy(
            id = "prop_edit_diff_doc",
            type = ProposalType.EDIT,
            doctorUid = "doc_1",
            medicineId = "med_1",
            payloadJson = addPayload.toJson()
        )
        coEvery { proposalRepository.getById("prop_edit_diff_doc") } returns editProposal
        coEvery { medicineRepository.getById("med_1") } returns existingMedicine

        val success = acceptUseCase("prop_edit_diff_doc")

        assertFalse(success)
        coVerify(exactly = 0) { saveMedicineUseCase.updateMedicine(any()) }
    }

    @Test
    fun declineProposal_marksDeclined_createsNothing() = runTest {
        val proposalSlot = slot<MedicineProposal>()
        coEvery { proposalRepository.update(capture(proposalSlot)) } returns Unit

        val success = declineUseCase("prop_1")

        assertTrue(success)
        coVerify(exactly = 0) { medicineRepository.insert(any()) }
        coVerify(exactly = 0) { doseRepository.insertAll(any()) }
        assertEquals(ProposalStatus.DECLINED, proposalSlot.captured.status)
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.PRESCRIPTION_DECLINED }) }
    }

    @Test
    fun requestClarification_updatesStatusAndRecordsNote() = runTest {
        val proposalSlot = slot<MedicineProposal>()
        coEvery { proposalRepository.update(capture(proposalSlot)) } returns Unit

        val success = requestClarificationUseCase("prop_1", "Should I take this with milk?")

        assertTrue(success)
        assertEquals(ProposalStatus.CLARIFICATION_REQUESTED, proposalSlot.captured.status)
        assertTrue(proposalSlot.captured.payloadJson.contains("Should I take this with milk?"))
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED }) }
    }

    @Test
    fun reviseProposal_supersedesOldProposal_createsNewRevision() = runTest {
        val oldSlot = slot<MedicineProposal>()
        coEvery { proposalRepository.update(capture(oldSlot)) } returns Unit

        val newSlot = slot<MedicineProposal>()
        coEvery { proposalRepository.insert(capture(newSlot)) } returns Unit

        val revisedPayload = addPayload.copy(dosageText = "1000mg")
        val newProposal = reviseUseCase("prop_1", revisedPayload)

        assertNotNull(newProposal)
        assertEquals(ProposalStatus.SUPERSEDED, oldSlot.captured.status)
        assertEquals(ProposalStatus.PENDING, newSlot.captured.status)
        val parsedNewPayload = PrescriptionPayload.fromJson(newSlot.captured.payloadJson)
        assertEquals(2, parsedNewPayload.revision)
        assertEquals("1000mg", parsedNewPayload.dosageText)
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.PRESCRIPTION_REVISED }) }
    }
}
