package com.meditrack.app.domain.usecase

import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.PrescriptionProposalPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.statemachine.ProposalStateMachine
import com.meditrack.app.domain.statemachine.ProposalTransitionEvent
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AcceptProposalUseCase @Inject constructor(
    private val doctorRepository: DoctorRepository,
    private val medicineRepository: MedicineRepository,
    private val saveMedicineUseCase: SaveMedicineUseCase,
    private val outboxRepository: OutboxRepository
) {
    suspend operator fun invoke(
        proposalId: String,
        patientId: String,
        patientName: String = "Patient"
    ): Result<MedicineProposal> {
        val proposal = doctorRepository.getProposalById(proposalId)
            ?: return Result.failure(IllegalArgumentException("Proposal not found: $proposalId"))

        if (proposal.patientId != patientId) {
            return Result.failure(IllegalStateException("Proposal does not belong to this patient."))
        }

        val transitionResult = ProposalStateMachine.transition(proposal.status, ProposalTransitionEvent.Accept)
        if (transitionResult.isFailure) {
            return Result.failure(transitionResult.exceptionOrNull() ?: IllegalStateException("Illegal state transition"))
        }

        val payload = PrescriptionProposalPayload.fromJson(proposal.payloadJson)

        when (proposal.type) {
            ProposalType.ADD -> {
                val times = payload.timesOfDay.mapNotNull {
                    runCatching { LocalTime.parse(it) }.getOrNull()
                }.ifEmpty { listOf(LocalTime.of(8, 0)) }

                val newMedicine = Medicine(
                    id = "med_${UUID.randomUUID()}",
                    patientId = patientId,
                    name = payload.medicineName,
                    dosageText = payload.dosageText,
                    pillsPerDose = payload.pillsPerDose,
                    frequencyType = payload.frequencyType,
                    timesOfDay = times,
                    intervalHours = payload.intervalHours,
                    daysOfWeek = payload.daysOfWeek,
                    startDate = payload.startDate,
                    endDate = payload.endDate,
                    foodRelation = payload.foodRelation,
                    instructions = payload.instructions,
                    initialStock = payload.initialStock,
                    currentStock = payload.initialStock,
                    prescribedByDoctorUid = proposal.doctorUid,
                    prescribedByDoctorName = payload.doctorName.ifBlank { "Doctor" },
                    stoppedByDoctor = false
                )
                saveMedicineUseCase.saveNewMedicine(newMedicine)
            }
            ProposalType.EDIT -> {
                val medId = proposal.medicineId
                val existing = if (medId != null) medicineRepository.getById(medId) else null
                if (existing != null) {
                    val times = if (payload.timesOfDay.isNotEmpty()) {
                        payload.timesOfDay.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
                    } else existing.timesOfDay

                    val updatedMed = existing.copy(
                        name = payload.medicineName.ifBlank { existing.name },
                        dosageText = payload.dosageText.ifBlank { existing.dosageText },
                        pillsPerDose = payload.pillsPerDose,
                        frequencyType = payload.frequencyType,
                        timesOfDay = times,
                        intervalHours = payload.intervalHours ?: existing.intervalHours,
                        daysOfWeek = payload.daysOfWeek ?: existing.daysOfWeek,
                        startDate = payload.startDate,
                        endDate = payload.endDate ?: existing.endDate,
                        foodRelation = payload.foodRelation,
                        instructions = payload.instructions,
                        prescribedByDoctorUid = proposal.doctorUid,
                        prescribedByDoctorName = payload.doctorName.ifBlank { existing.prescribedByDoctorName }
                    )
                    saveMedicineUseCase.updateMedicine(updatedMed)
                }
            }
            ProposalType.STOP -> {
                val medId = proposal.medicineId
                val existing = if (medId != null) medicineRepository.getById(medId) else null
                if (existing != null) {
                    val stoppedMed = existing.copy(stoppedByDoctor = true)
                    saveMedicineUseCase.updateMedicine(stoppedMed)
                }
            }
        }

        val updatedProposal = proposal.copy(
            status = ProposalStatus.ACCEPTED,
            resolvedAt = System.currentTimeMillis()
        )
        doctorRepository.updateProposal(updatedProposal)

        val outboxPayload = JsonPayload.build(
            "type" to NotifType.PRESCRIPTION_ACCEPTED.name,
            "patientUid" to patientId,
            "patientName" to patientName,
            "doctorUid" to proposal.doctorUid,
            "medicineName" to payload.medicineName,
            "proposalId" to proposalId,
            "createdAt" to System.currentTimeMillis()
        )

        outboxRepository.enqueue(
            NotificationOutbox(
                id = "out_${UUID.randomUUID()}",
                type = NotifType.PRESCRIPTION_ACCEPTED,
                payloadJson = outboxPayload,
                createdAt = System.currentTimeMillis()
            )
        )

        return Result.success(updatedProposal)
    }
}
