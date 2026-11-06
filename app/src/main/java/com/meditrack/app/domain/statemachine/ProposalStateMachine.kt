package com.meditrack.app.domain.statemachine

import com.meditrack.app.domain.model.ProposalStatus

sealed class ProposalTransitionEvent {
    data object Accept : ProposalTransitionEvent()
    data object Decline : ProposalTransitionEvent()
    data class RequestClarification(val note: String) : ProposalTransitionEvent()
    data class Supersede(val newRevision: Int) : ProposalTransitionEvent()
}

class IllegalProposalTransitionException(from: ProposalStatus, event: ProposalTransitionEvent) :
    IllegalStateException("Illegal transition from proposal status $from with event ${event::class.simpleName}")

object ProposalStateMachine {

    fun transition(currentStatus: ProposalStatus, event: ProposalTransitionEvent): Result<ProposalStatus> {
        val nextStatus = when (currentStatus) {
            ProposalStatus.PENDING -> when (event) {
                is ProposalTransitionEvent.Accept -> ProposalStatus.ACCEPTED
                is ProposalTransitionEvent.Decline -> ProposalStatus.DECLINED
                is ProposalTransitionEvent.RequestClarification -> ProposalStatus.CLARIFICATION_REQUESTED
                is ProposalTransitionEvent.Supersede -> ProposalStatus.SUPERSEDED
            }
            ProposalStatus.CLARIFICATION_REQUESTED -> when (event) {
                is ProposalTransitionEvent.Accept -> ProposalStatus.ACCEPTED
                is ProposalTransitionEvent.Decline -> ProposalStatus.DECLINED
                is ProposalTransitionEvent.Supersede -> ProposalStatus.SUPERSEDED
                is ProposalTransitionEvent.RequestClarification -> ProposalStatus.CLARIFICATION_REQUESTED
            }
            ProposalStatus.ACCEPTED,
            ProposalStatus.DECLINED,
            ProposalStatus.SUPERSEDED -> null
        }

        return if (nextStatus != null) {
            Result.success(nextStatus)
        } else {
            Result.failure(IllegalProposalTransitionException(currentStatus, event))
        }
    }
}
