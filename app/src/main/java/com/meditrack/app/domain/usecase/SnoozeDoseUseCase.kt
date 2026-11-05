package com.meditrack.app.domain.usecase

import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnoozeDoseUseCase @Inject constructor(
    private val doseRepository: DoseRepository
) {

    /**
     * Reschedules a pending dose for snooze:
     * - Validates snoozeMinutes > 0
     * - Validates snoozeCount < MAX_SNOOZES_PER_DOSE
     * - Enforces that target snooze time does not push past dose.windowEndsAt
     * - Increments snoozeCount and sets nextReminderAt
     * Returns the target alarm trigger time in epoch millis on success.
     */
    suspend operator fun invoke(
        doseId: String,
        snoozeMinutes: Int = 15,
        now: Long = System.currentTimeMillis()
    ): Result<Long> {
        if (snoozeMinutes <= 0) {
            return Result.failure(IllegalArgumentException("Snooze duration must be greater than zero."))
        }

        val dose = doseRepository.getById(doseId)
            ?: return Result.failure(IllegalArgumentException("Dose not found: $doseId"))

        if (dose.status != DoseStatus.PENDING) {
            return Result.failure(IllegalStateException("Only pending doses can be snoozed (current: ${dose.status})"))
        }

        if (dose.snoozeCount >= AppConfig.MAX_SNOOZES_PER_DOSE) {
            return Result.failure(IllegalStateException("Maximum of ${AppConfig.MAX_SNOOZES_PER_DOSE} snoozes reached for this dose"))
        }

        val targetTriggerTime = now + (snoozeMinutes * 60 * 1000L)
        if (targetTriggerTime >= dose.windowEndsAt) {
            return Result.failure(IllegalStateException("Snooze cannot extend past the intake window end time"))
        }

        val updatedDose = dose.copy(
            snoozeCount = dose.snoozeCount + 1,
            nextReminderAt = targetTriggerTime,
            updatedAt = now
        )
        doseRepository.update(updatedDose)

        return Result.success(targetTriggerTime)
    }
}
