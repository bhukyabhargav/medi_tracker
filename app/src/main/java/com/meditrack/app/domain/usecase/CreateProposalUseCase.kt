package com.meditrack.app.domain.usecase

import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.PrescriptionProposalPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.OutboxRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreateProposalUseCase @Inject constructor(
    private val doctorRepository: DoctorRepository,
    private val outboxRepository: OutboxRepository
) {
    suspend operator fun invoke(
        patientId: String,
        doctorUid: String,
        doctorName: String,
        type: ProposalType,
        medicineId: String? = null,
        payload: PrescriptionProposalPayload
    ): Result<MedicineProposal> {
        val link = doctorRepository.getDoctorLinkByDoctorAndPatient(doctorUid, patientId)
        if (link == null || link.status != DoctorLinkStatus.ACCEPTED) {
            return Result.failure(
                IllegalStateException("Doctor is not actively connected to patient (link status must be ACCEPTED).")
            )
        }

        val proposalId = "prop_${UUID.randomUUID()}"
        val payloadWithDoc = payload.copy(doctorName = doctorName)
        val proposal = MedicineProposal(
            id = proposalId,
            patientId = patientId,
            doctorUid = doctorUid,
            type = type,
            medicineId = medicineId,
            payloadJson = payloadWithDoc.toJson(),
            status = ProposalStatus.PENDING,
            revision = 1,
            createdAt = System.currentTimeMillis()
        )

        doctorRepository.insertProposal(proposal)

        val outboxPayload = JsonPayload.build(
            "type" to NotifType.PRESCRIPTION_PROPOSAL_RECEIVED.name,
            "patientUid" to patientId,
            "doctorUid" to doctorUid,
            "doctorName" to doctorName,
            "medicineName" to payload.medicineName,
            "proposalId" to proposalId,
            "createdAt" to System.currentTimeMillis()
        )

        outboxRepository.enqueue(
            NotificationOutbox(
                id = "out_${UUID.randomUUID()}",
                type = NotifType.PRESCRIPTION_PROPOSAL_RECEIVED,
                payloadJson = outboxPayload,
                createdAt = System.currentTimeMillis()
            )
        )

        return Result.success(proposal)
    }
}
