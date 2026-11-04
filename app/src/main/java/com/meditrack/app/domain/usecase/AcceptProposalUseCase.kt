package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.PrescriptionPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.ProposalRepository
import com.meditrack.app.domain.statemachine.ProposalStateMachine
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AcceptProposalUseCase @Inject constructor(
    private val proposalRepository: ProposalRepository,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val saveMedicineUseCase: SaveMedicineUseCase,
    private val outboxRepository: OutboxRepository,
    private val authRepository: AuthRepository
) {

    suspend operator fun invoke(proposalId: String): Boolean {
        val proposal = proposalRepository.getById(proposalId) ?: return false
        val newStatus = ProposalStateMachine.transition(proposal.status, ProposalStatus.ACCEPTED)

        val payload = try {
            PrescriptionPayload.fromJson(proposal.payloadJson)
        } catch (_: Exception) {
            return false
        }

        val patientName = authRepository.getCurrentUser()?.name ?: "Patient"

        when (proposal.type) {
            ProposalType.ADD -> {
                val times = payload.times.mapNotNull { parseTime(it) }.ifEmpty { listOf(LocalTime.of(9, 0)) }
                val startEpoch = parseDateToEpoch(payload.startDate)
                val endEpoch = payload.endDate?.let { parseDateToEpoch(it) }

                val medicineId = proposal.medicineId ?: "med_${UUID.randomUUID()}"
                val newMedicine = Medicine(
                    id = medicineId,
                    name = payload.medicineName,
                    dosageText = payload.dosageText,
                    pillsPerDose = payload.pillsPerDose,
                    frequencyType = payload.frequencyType,
                    timesOfDay = times,
                    startDate = startEpoch,
                    endDate = endEpoch,
                    foodRelation = payload.foodRelation,
                    instructions = payload.additionalInstructions ?: "",
                    initialStock = payload.initialStock,
                    currentStock = payload.initialStock,
                    prescribedByDoctorUid = proposal.doctorUid,
                    prescribedByDoctorName = payload.doctorName,
                    stoppedByDoctor = false,
                    isDeleted = false,
                    createdAt = System.currentTimeMillis()
                )
                saveMedicineUseCase.saveNewMedicine(newMedicine)
            }
            ProposalType.EDIT -> {
                val targetId = proposal.medicineId ?: return false
                val existing = medicineRepository.getById(targetId) ?: return false
                val times = payload.times.mapNotNull { parseTime(it) }.ifEmpty { existing.timesOfDay }
                val endEpoch = payload.endDate?.let { parseDateToEpoch(it) } ?: existing.endDate

                val updated = existing.copy(
                    name = payload.medicineName.ifBlank { existing.name },
                    dosageText = payload.dosageText.ifBlank { existing.dosageText },
                    pillsPerDose = payload.pillsPerDose,
                    frequencyType = payload.frequencyType,
                    timesOfDay = times,
                    endDate = endEpoch,
                    foodRelation = payload.foodRelation,
                    instructions = payload.additionalInstructions ?: existing.instructions
                )
                // updateMedicine updates future doses only, leaving past doses untouched
                saveMedicineUseCase.updateMedicine(updated)
            }
            ProposalType.STOP -> {
                val targetId = proposal.medicineId ?: return false
                val existing = medicineRepository.getById(targetId) ?: return false
                val updated = existing.copy(stoppedByDoctor = true)
                medicineRepository.update(updated)

                // Cancel future pending doses and their alarms, preserving past history
                val now = System.currentTimeMillis()
                doseRepository.deleteFuturePendingDoses(targetId, now)
            }
        }

        // Update proposal in Room
        val updatedProposal = proposal.copy(
            status = newStatus,
            resolvedAt = System.currentTimeMillis()
        )
        proposalRepository.update(updatedProposal)

        // Enqueue outbox notification
        val outbox = NotificationOutbox(
            id = "outbox_${UUID.randomUUID()}",
            type = NotifType.PRESCRIPTION_ACCEPTED,
            payloadJson = """{"patientName":"$patientName","medicineName":"${payload.medicineName}","doctorUid":"${proposal.doctorUid}"}""",
            createdAt = System.currentTimeMillis(),
            delivered = false
        )
        outboxRepository.enqueue(outbox)

        return true
    }

    private fun parseTime(str: String): LocalTime? {
        return try {
            LocalTime.parse(str.trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDateToEpoch(dateStr: String): Long {
        return try {
            val localDate = LocalDate.parse(dateStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
            localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
