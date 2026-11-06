package com.meditrack.app.domain.usecase

import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessMissedDoseUseCase @Inject constructor(
    private val doseRepository: DoseRepository,
    private val caregiverRepository: CaregiverRepository,
    private val outboxRepository: OutboxRepository,
    private val userPreferences: UserPreferences
) {

    /**
     * Evaluates a dose when its intake window expires:
     * - If still PENDING or UNVERIFIED, transitions to MISSED via DoseStateMachine
     * - If an accepted caregiver link exists for the patient, enqueues a MISSED_DOSE event in NotificationOutbox
     * Returns true if the dose transitioned to MISSED, false if it was already closed.
     */
    suspend operator fun invoke(
        doseId: String,
        now: Long = System.currentTimeMillis()
    ): Result<Boolean> {
        val dose = doseRepository.getById(doseId)
            ?: return Result.failure(IllegalArgumentException("Dose not found: $doseId"))

        if (now < dose.windowEndsAt) {
            // Never close a dose before its intake window has ended (clock changes, early jobs).
            return Result.success(false)
        }

        if (dose.status != DoseStatus.PENDING && dose.status != DoseStatus.UNVERIFIED) {
            // Already taken, skipped, or previously marked missed
            return Result.success(false)
        }

        val transitionResult = DoseStateMachine.transition(
            currentStatus = dose.status,
            event = DoseTransitionEvent.WindowExpired
        )

        if (transitionResult.isFailure) {
            return Result.failure(transitionResult.exceptionOrNull()!!)
        }

        val updatedDose = dose.copy(
            status = DoseStatus.MISSED,
            updatedAt = now
        )
        doseRepository.update(updatedDose)

        // Check if an accepted caregiver is connected to the patient
        val currentUserId = userPreferences.currentUserId.first()
        val hasConnectedCaregiver = if (currentUserId != null) {
            caregiverRepository.hasConnectedCaregiver(currentUserId)
        } else {
            false
        }

        if (hasConnectedCaregiver) {
            val payload = """{"doseId":"$doseId","medicineId":"${dose.medicineId}","scheduledAt":${dose.scheduledAt},"windowEndsAt":${dose.windowEndsAt}}"""
            val outboxEvent = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.MISSED_DOSE,
                payloadJson = payload,
                createdAt = now,
                delivered = false
            )
            outboxRepository.enqueue(outboxEvent)
        }

        return Result.success(true)
    }
}
