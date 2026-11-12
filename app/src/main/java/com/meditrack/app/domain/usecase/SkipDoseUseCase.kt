package com.meditrack.app.domain.usecase

import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkipDoseUseCase @Inject constructor(
    private val doseRepository: DoseRepository,
    private val caregiverRepository: CaregiverRepository? = null,
    private val outboxRepository: OutboxRepository? = null
) {

    /**
     * Transitions a dose to SKIPPED:
     * - Validates state transition via DoseStateMachine (legal only from PENDING)
     * - Updates dose record in database
     * - If an accepted caregiver link exists for the patient, enqueues a DOSE_SKIPPED event in NotificationOutbox
     */
    suspend operator fun invoke(
        doseId: String,
        now: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val dose = doseRepository.getById(doseId)
            ?: return Result.failure(IllegalArgumentException("Dose not found: $doseId"))

        val transitionResult = DoseStateMachine.transition(
            currentStatus = dose.status,
            event = DoseTransitionEvent.Skip
        )

        if (transitionResult.isFailure) {
            return Result.failure(transitionResult.exceptionOrNull()!!)
        }

        val updatedDose = dose.copy(
            status = DoseStatus.SKIPPED,
            updatedAt = now
        )
        doseRepository.update(updatedDose)

        // The skip itself is already saved; a notification problem must never undo or fail it.
        try {
            val caregivers = caregiverRepository
            val outbox = outboxRepository
            if (caregivers != null && outbox != null && dose.patientId.isNotBlank() &&
                caregivers.hasConnectedCaregiver(dose.patientId)
            ) {
                outbox.enqueue(
                    NotificationOutbox(
                        id = "outbox_${UUID.randomUUID()}",
                        type = NotifType.DOSE_SKIPPED,
                        payloadJson = JsonPayload.build(
                            "doseId" to doseId,
                            "medicineId" to dose.medicineId,
                            "scheduledAt" to dose.scheduledAt
                        ),
                        createdAt = now,
                        delivered = false
                    )
                )
            }
        } catch (_: Exception) {
        }

        return Result.success(Unit)
    }
}
