package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.PrescriptionPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.ProposalRepository
import com.meditrack.app.domain.statemachine.ProposalStateMachine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RequestClarificationUseCase @Inject constructor(
    private val proposalRepository: ProposalRepository,
    private val outboxRepository: OutboxRepository,
    private val authRepository: AuthRepository
) {

    suspend operator fun invoke(proposalId: String, clarificationNote: String): Boolean {
        val proposal = proposalRepository.getById(proposalId) ?: return false
        val newStatus = ProposalStateMachine.transition(proposal.status, ProposalStatus.CLARIFICATION_REQUESTED)

        val payload = try {
            PrescriptionPayload.fromJson(proposal.payloadJson)
        } catch (_: Exception) {
            null
        }

        val updatedPayload = payload?.copy(clarificationNote = clarificationNote)
        val patientName = authRepository.getCurrentUser()?.name ?: "Patient"
        val medicineName = payload?.medicineName ?: "Medicine"

        val updated = proposal.copy(
            status = newStatus,
            payloadJson = updatedPayload?.toJson() ?: proposal.payloadJson
        )
        proposalRepository.update(updated)

        val outbox = NotificationOutbox(
            id = "outbox_${UUID.randomUUID()}",
            type = NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED,
            payloadJson = """{"patientName":"$patientName","medicineName":"$medicineName","doctorUid":"${proposal.doctorUid}","clarificationNote":"$clarificationNote"}""",
            createdAt = System.currentTimeMillis(),
            delivered = false
        )
        outboxRepository.enqueue(outbox)

        return true
    }
}
