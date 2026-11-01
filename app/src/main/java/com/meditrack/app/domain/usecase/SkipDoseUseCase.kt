package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkipDoseUseCase @Inject constructor(
    private val doseRepository: DoseRepository
) {

    /**
     * Transitions a dose to SKIPPED:
     * - Validates state transition via DoseStateMachine (legal only from PENDING)
     * - Updates dose record in database
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

        return Result.success(Unit)
    }
}
