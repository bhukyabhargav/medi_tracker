package com.meditrack.app.domain.usecase

import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.PrescriptionPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.ProposalRepository
import com.meditrack.app.domain.statemachine.ProposalStateMachine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviseProposalUseCase @Inject constructor(
    private val proposalRepository: ProposalRepository,
    private val outboxRepository: OutboxRepository
) {

    suspend operator fun invoke(
        oldProposalId: String,
        updatedPayload: PrescriptionPayload
    ): MedicineProposal? {
        val oldProposal = proposalRepository.getById(oldProposalId) ?: return null
        val newOldStatus = ProposalStateMachine.transition(oldProposal.status, ProposalStatus.SUPERSEDED)

        // Mark the previous proposal as SUPERSEDED
        proposalRepository.update(oldProposal.copy(status = newOldStatus, resolvedAt = System.currentTimeMillis()))

        // Extract and increment revision number
        val oldPayload = try {
            PrescriptionPayload.fromJson(oldProposal.payloadJson)
        } catch (_: Exception) {
            null
        }
        val newRevisionNumber = (oldPayload?.revision ?: 1) + 1
        val finalPayload = updatedPayload.copy(revision = newRevisionNumber, clarificationNote = null)

        val newProposalId = "prop_${UUID.randomUUID()}"
        val newProposal = MedicineProposal(
            id = newProposalId,
            patientId = oldProposal.patientId,
            doctorUid = oldProposal.doctorUid,
            type = oldProposal.type,
            medicineId = oldProposal.medicineId,
            payloadJson = finalPayload.toJson(),
            status = ProposalStatus.PENDING,
            revision = newRevisionNumber,
            supersedesProposalId = oldProposalId,
            createdAt = System.currentTimeMillis()
        )
        proposalRepository.insert(newProposal)

        val outbox = NotificationOutbox(
            id = "outbox_${UUID.randomUUID()}",
            type = NotifType.PRESCRIPTION_REVISED,
            payloadJson = JsonPayload.build("doctorName" to finalPayload.doctorName, "medicineName" to finalPayload.medicineName, "patientId" to oldProposal.patientId, "revision" to newRevisionNumber),
            createdAt = System.currentTimeMillis(),
            delivered = false
        )
        outboxRepository.enqueue(outbox)

        return newProposal
    }
}
