package com.meditrack.app.domain.statemachine

import com.meditrack.app.domain.model.DoseStatus

sealed class DoseTransitionEvent {
    data class VerifyPassed(
        val photoPath: String,
        val emptyCellsAfter: Int,
        val takenAt: Long
    ) : DoseTransitionEvent()

    data object Skip : DoseTransitionEvent()

    data object VerificationFailedExhausted : DoseTransitionEvent()

    data object WindowExpired : DoseTransitionEvent()

    data class LateVerifyPassed(
        val photoPath: String,
        val emptyCellsAfter: Int,
        val takenAt: Long
    ) : DoseTransitionEvent()

    data object ReportManualUnverified : DoseTransitionEvent()
}

class IllegalDoseTransitionException(from: DoseStatus, event: DoseTransitionEvent) :
    IllegalStateException("Illegal transition from status $from with event ${event::class.simpleName}")

object DoseStateMachine {

    fun transition(currentStatus: DoseStatus, event: DoseTransitionEvent): Result<DoseStatus> {
        val nextStatus = when (currentStatus) {
            DoseStatus.PENDING -> when (event) {
                is DoseTransitionEvent.VerifyPassed -> DoseStatus.TAKEN
                is DoseTransitionEvent.Skip -> DoseStatus.SKIPPED
                is DoseTransitionEvent.VerificationFailedExhausted -> DoseStatus.UNVERIFIED
                is DoseTransitionEvent.WindowExpired -> DoseStatus.MISSED
                else -> null
            }
            DoseStatus.UNVERIFIED -> when (event) {
                is DoseTransitionEvent.WindowExpired -> DoseStatus.MISSED
                else -> null
            }
            DoseStatus.MISSED -> when (event) {
                is DoseTransitionEvent.LateVerifyPassed -> DoseStatus.TAKEN_LATE
                is DoseTransitionEvent.ReportManualUnverified -> DoseStatus.MISSED
                else -> null
            }
            DoseStatus.TAKEN,
            DoseStatus.TAKEN_LATE,
            DoseStatus.SKIPPED -> null
        }

        return if (nextStatus != null) {
            Result.success(nextStatus)
        } else {
            Result.failure(IllegalDoseTransitionException(currentStatus, event))
        }
    }
}
