package com.meditrack.app.domain.usecase

import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.PrescriptionProposalPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.statemachine.ProposalStateMachine
import com.meditrack.app.domain.statemachine.ProposalTransitionEvent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RequestClarificationUseCase @Inject constructor(
    private val doctorRepository: DoctorRepository,
    private val outboxRepository: OutboxRepository
) {
    suspend operator fun invoke(
        proposalId: String,
        patientId: String,
        note: String,
        patientName: String = "Patient"
    ): Result<MedicineProposal> {
        val proposal = doctorRepository.getProposalById(proposalId)
            ?: return Result.failure(IllegalArgumentException("Proposal not found: $proposalId"))

        if (proposal.patientId != patientId) {
            return Result.failure(IllegalStateException("Proposal does not belong to this patient."))
        }

        val transitionResult = ProposalStateMachine.transition(
            proposal.status,
            ProposalTransitionEvent.RequestClarification(note)
        )
        if (transitionResult.isFailure) {
            return Result.failure(transitionResult.exceptionOrNull() ?: IllegalStateException("Illegal state transition"))
        }

        val updatedProposal = proposal.copy(
            status = ProposalStatus.CLARIFICATION_REQUESTED,
            clarificationNote = note
        )
        doctorRepository.updateProposal(updatedProposal)

        val payload = PrescriptionProposalPayload.fromJson(proposal.payloadJson)

        val outboxPayload = JsonPayload.build(
            "type" to NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED.name,
            "patientUid" to patientId,
            "patientName" to patientName,
            "doctorUid" to proposal.doctorUid,
            "medicineName" to payload.medicineName,
            "proposalId" to proposalId,
            "note" to note,
            "createdAt" to System.currentTimeMillis()
        )

        outboxRepository.enqueue(
            NotificationOutbox(
                id = "out_${UUID.randomUUID()}",
                type = NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED,
                payloadJson = outboxPayload,
                createdAt = System.currentTimeMillis()
            )
        )

        return Result.success(updatedProposal)
    }
}
