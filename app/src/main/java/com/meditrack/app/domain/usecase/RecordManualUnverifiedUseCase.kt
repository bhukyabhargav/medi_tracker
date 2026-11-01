package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordManualUnverifiedUseCase @Inject constructor(
    private val doseRepository: DoseRepository,
    private val alarmScheduler: AlarmScheduler
) {

    /**
     * Records a dose as taken manually without photo verification:
     * - Status remains MISSED (does not count toward verified adherence)
     * - Sets manualUnverified = true
     * - Stock is NOT decremented
     * - Alarm is cancelled
     */
    suspend operator fun invoke(
        doseId: String,
        reportedAt: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val dose = doseRepository.getById(doseId)
            ?: return Result.failure(IllegalArgumentException("Dose not found: $doseId"))

        val transitionResult = DoseStateMachine.transition(
            currentStatus = dose.status,
            event = DoseTransitionEvent.ReportManualUnverified
        )

        if (transitionResult.isFailure) {
            return Result.failure(transitionResult.exceptionOrNull()!!)
        }

        val updatedDose = dose.copy(
            status = DoseStatus.MISSED,
            manualUnverified = true,
            updatedAt = reportedAt
        )

        doseRepository.update(updatedDose)
        alarmScheduler.cancelDoseAlarm(doseId)

        return Result.success(Unit)
    }
}
