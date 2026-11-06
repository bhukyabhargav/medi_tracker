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
class ReviseProposalUseCase @Inject constructor(
    private val doctorRepository: DoctorRepository,
    private val outboxRepository: OutboxRepository
) {
    suspend operator fun invoke(
        originalProposalId: String,
        doctorUid: String,
        doctorName: String,
        newPayload: PrescriptionProposalPayload
    ): Result<MedicineProposal> {
        val original = doctorRepository.getProposalById(originalProposalId)
            ?: return Result.failure(IllegalArgumentException("Original proposal not found: $originalProposalId"))

        if (original.doctorUid != doctorUid) {
            return Result.failure(IllegalStateException("Proposal was not created by this doctor."))
        }

        val nextRevision = original.revision + 1
        val transitionResult = ProposalStateMachine.transition(
            original.status,
            ProposalTransitionEvent.Supersede(nextRevision)
        )
        if (transitionResult.isFailure) {
            return Result.failure(transitionResult.exceptionOrNull() ?: IllegalStateException("Illegal state transition"))
        }

        val supersededOriginal = original.copy(status = ProposalStatus.SUPERSEDED)
        doctorRepository.updateProposal(supersededOriginal)

        val newProposalId = "prop_${UUID.randomUUID()}"
        val payloadWithDoc = newPayload.copy(doctorName = doctorName)
        val newProposal = MedicineProposal(
            id = newProposalId,
            patientId = original.patientId,
            doctorUid = doctorUid,
            type = original.type,
            medicineId = original.medicineId,
            payloadJson = payloadWithDoc.toJson(),
            status = ProposalStatus.PENDING,
            revision = nextRevision,
            createdAt = System.currentTimeMillis()
        )

        doctorRepository.insertProposal(newProposal)

        val outboxPayload = JsonPayload.build(
            "type" to NotifType.PRESCRIPTION_REVISED.name,
            "patientUid" to original.patientId,
            "doctorUid" to doctorUid,
            "doctorName" to doctorName,
            "medicineName" to newPayload.medicineName,
            "proposalId" to newProposalId,
            "revision" to nextRevision,
            "createdAt" to System.currentTimeMillis()
        )

        outboxRepository.enqueue(
            NotificationOutbox(
                id = "out_${UUID.randomUUID()}",
                type = NotifType.PRESCRIPTION_REVISED,
                payloadJson = outboxPayload,
                createdAt = System.currentTimeMillis()
            )
        )

        return Result.success(newProposal)
    }
}
