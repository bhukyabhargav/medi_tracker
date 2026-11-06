package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.PrescriptionProposalPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class DoctorProposalUseCasesTest {

    private val doctorRepository: DoctorRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val saveMedicineUseCase: SaveMedicineUseCase = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)

    private lateinit var createProposalUseCase: CreateProposalUseCase
    private lateinit var acceptProposalUseCase: AcceptProposalUseCase
    private lateinit var declineProposalUseCase: DeclineProposalUseCase
    private lateinit var requestClarificationUseCase: RequestClarificationUseCase
    private lateinit var reviseProposalUseCase: ReviseProposalUseCase

    private val patientId = "patient_alice"
    private val doctorUid = "doc_bob"
    private val doctorName = "Bob Smith"

    @Before
    fun setup() {
        createProposalUseCase = CreateProposalUseCase(doctorRepository, outboxRepository)
        acceptProposalUseCase = AcceptProposalUseCase(doctorRepository, medicineRepository, saveMedicineUseCase, outboxRepository)
        declineProposalUseCase = DeclineProposalUseCase(doctorRepository, outboxRepository)
        requestClarificationUseCase = RequestClarificationUseCase(doctorRepository, outboxRepository)
        reviseProposalUseCase = ReviseProposalUseCase(doctorRepository, outboxRepository)
    }

    private fun samplePayload(): PrescriptionProposalPayload {
        return PrescriptionProposalPayload(
            medicineName = "Amoxicillin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf("08:00", "20:00"),
            startDate = System.currentTimeMillis(),
            foodRelation = FoodRelation.AFTER,
            instructions = "Take after breakfast and dinner",
            initialStock = 30,
            doctorName = doctorName
        )
    }

    @Test
    fun createProposal_failsIfDoctorNotConnected() = runTest {
        // Link is PENDING or null
        coEvery { doctorRepository.getDoctorLinkByDoctorAndPatient(doctorUid, patientId) } returns null

        val result = createProposalUseCase(
            patientId = patientId,
            doctorUid = doctorUid,
            doctorName = doctorName,
            type = ProposalType.ADD,
            payload = samplePayload()
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { doctorRepository.insertProposal(any()) }
    }

    @Test
    fun createProposal_succeedsWhenConnected() = runTest {
        val activeLink = DoctorLink(
            id = "link_1",
            patientId = patientId,
            doctorUid = doctorUid,
            doctorName = doctorName,
            doctorContact = "+1-555-1234",
            status = DoctorLinkStatus.ACCEPTED
        )
        coEvery { doctorRepository.getDoctorLinkByDoctorAndPatient(doctorUid, patientId) } returns activeLink

        val result = createProposalUseCase(
            patientId = patientId,
            doctorUid = doctorUid,
            doctorName = doctorName,
            type = ProposalType.ADD,
            payload = samplePayload()
        )

        assertTrue(result.isSuccess)
        val proposal = result.getOrNull()
        assertNotNull(proposal)
        assertEquals(ProposalStatus.PENDING, proposal!!.status)
        assertEquals(1, proposal.revision)
        coVerify(exactly = 1) { doctorRepository.insertProposal(any()) }
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.PRESCRIPTION_PROPOSAL_RECEIVED }) }
    }

    @Test
    fun acceptProposal_add_createsMedicineAndDosesAndAlarms() = runTest {
        val proposal = MedicineProposal(
            id = "prop_1",
            patientId = patientId,
            doctorUid = doctorUid,
            type = ProposalType.ADD,
            payloadJson = samplePayload().toJson(),
            status = ProposalStatus.PENDING,
            revision = 1
        )
        coEvery { doctorRepository.getProposalById("prop_1") } returns proposal

        val result = acceptProposalUseCase(proposalId = "prop_1", patientId = patientId)

        assertTrue(result.isSuccess)
        val medSlot = slot<Medicine>()
        coVerify(exactly = 1) { saveMedicineUseCase.saveNewMedicine(capture(medSlot)) }
        assertEquals("Amoxicillin", medSlot.captured.name)
        assertEquals(doctorUid, medSlot.captured.prescribedByDoctorUid)
        assertEquals(doctorName, medSlot.captured.prescribedByDoctorName)

        val updatedPropSlot = slot<MedicineProposal>()
        coVerify(exactly = 1) { doctorRepository.updateProposal(capture(updatedPropSlot)) }
        assertEquals(ProposalStatus.ACCEPTED, updatedPropSlot.captured.status)
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.PRESCRIPTION_ACCEPTED }) }
    }

    @Test
    fun acceptProposal_edit_updatesFutureDosesOnly() = runTest {
        val existingMedicine = Medicine(
            id = "med_1",
            patientId = patientId,
            name = "Amoxicillin",
            dosageText = "250mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = 1000L,
            initialStock = 20,
            currentStock = 15
        )
        coEvery { medicineRepository.getById("med_1") } returns existingMedicine

        val updatedPayload = samplePayload().copy(dosageText = "500mg")
        val proposal = MedicineProposal(
            id = "prop_edit",
            patientId = patientId,
            doctorUid = doctorUid,
            type = ProposalType.EDIT,
            medicineId = "med_1",
            payloadJson = updatedPayload.toJson(),
            status = ProposalStatus.PENDING,
            revision = 1
        )
        coEvery { doctorRepository.getProposalById("prop_edit") } returns proposal

        val result = acceptProposalUseCase(proposalId = "prop_edit", patientId = patientId)

        assertTrue(result.isSuccess)
        val updatedMedSlot = slot<Medicine>()
        coVerify(exactly = 1) { saveMedicineUseCase.updateMedicine(capture(updatedMedSlot)) }
        assertEquals("500mg", updatedMedSlot.captured.dosageText)
    }

    @Test
    fun acceptProposal_stop_cancelsFutureDosesAndPreservesHistory() = runTest {
        val existingMedicine = Medicine(
            id = "med_1",
            patientId = patientId,
            name = "Amoxicillin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = 1000L,
            initialStock = 20,
            currentStock = 10,
            stoppedByDoctor = false
        )
        coEvery { medicineRepository.getById("med_1") } returns existingMedicine

        val proposal = MedicineProposal(
            id = "prop_stop",
            patientId = patientId,
            doctorUid = doctorUid,
            type = ProposalType.STOP,
            medicineId = "med_1",
            payloadJson = samplePayload().toJson(),
            status = ProposalStatus.PENDING,
            revision = 1
        )
        coEvery { doctorRepository.getProposalById("prop_stop") } returns proposal

        val result = acceptProposalUseCase(proposalId = "prop_stop", patientId = patientId)

        assertTrue(result.isSuccess)
        val updatedMedSlot = slot<Medicine>()
        coVerify(exactly = 1) { saveMedicineUseCase.updateMedicine(capture(updatedMedSlot)) }
        assertTrue("stoppedByDoctor must be true", updatedMedSlot.captured.stoppedByDoctor)
    }

    @Test
    fun declineProposal_doesNotCreateMedicineOrDoses() = runTest {
        val proposal = MedicineProposal(
            id = "prop_decline",
            patientId = patientId,
            doctorUid = doctorUid,
            type = ProposalType.ADD,
            payloadJson = samplePayload().toJson(),
            status = ProposalStatus.PENDING,
            revision = 1
        )
        coEvery { doctorRepository.getProposalById("prop_decline") } returns proposal

        val result = declineProposalUseCase(proposalId = "prop_decline", patientId = patientId)

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { saveMedicineUseCase.saveNewMedicine(any()) }
        coVerify(exactly = 0) { saveMedicineUseCase.updateMedicine(any()) }
        coVerify(exactly = 1) { doctorRepository.updateProposal(match { it.status == ProposalStatus.DECLINED }) }
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.PRESCRIPTION_DECLINED }) }
    }

    @Test
    fun requestClarification_recordsNoteAndUpdatesStatus() = runTest {
        val proposal = MedicineProposal(
            id = "prop_clarify",
            patientId = patientId,
            doctorUid = doctorUid,
            type = ProposalType.ADD,
            payloadJson = samplePayload().toJson(),
            status = ProposalStatus.PENDING,
            revision = 1
        )
        coEvery { doctorRepository.getProposalById("prop_clarify") } returns proposal

        val result = requestClarificationUseCase(
            proposalId = "prop_clarify",
            patientId = patientId,
            note = "Can I take this with milk instead of water?"
        )

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            doctorRepository.updateProposal(match {
                it.status == ProposalStatus.CLARIFICATION_REQUESTED &&
                        it.clarificationNote == "Can I take this with milk instead of water?"
            })
        }
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED }) }
    }

    @Test
    fun reviseProposal_supersedesOldAndCreatesNewRevision() = runTest {
        val originalProposal = MedicineProposal(
            id = "prop_orig",
            patientId = patientId,
            doctorUid = doctorUid,
            type = ProposalType.ADD,
            payloadJson = samplePayload().toJson(),
            status = ProposalStatus.CLARIFICATION_REQUESTED,
            revision = 1,
            clarificationNote = "Can I take with milk?"
        )
        coEvery { doctorRepository.getProposalById("prop_orig") } returns originalProposal

        val revisedPayload = samplePayload().copy(instructions = "Yes, can be taken with milk.")
        val result = reviseProposalUseCase(
            originalProposalId = "prop_orig",
            doctorUid = doctorUid,
            doctorName = doctorName,
            newPayload = revisedPayload
        )

        assertTrue(result.isSuccess)
        val newProp = result.getOrNull()
        assertNotNull(newProp)
        assertEquals(2, newProp!!.revision)
        assertEquals(ProposalStatus.PENDING, newProp.status)
        coVerify(exactly = 1) { doctorRepository.updateProposal(match { it.id == "prop_orig" && it.status == ProposalStatus.SUPERSEDED }) }
        coVerify(exactly = 1) { doctorRepository.insertProposal(match { it.revision == 2 && it.status == ProposalStatus.PENDING }) }
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.PRESCRIPTION_REVISED }) }
    }
}
