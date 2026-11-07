package com.meditrack.app.domain.statemachine

import com.meditrack.app.domain.model.ProposalStatus

object ProposalStateMachine {

    /**
     * Map of legal status transitions for a prescription proposal.
     */
    private val legalTransitions: Map<ProposalStatus, Set<ProposalStatus>> = mapOf(
        ProposalStatus.PENDING to setOf(
            ProposalStatus.ACCEPTED,
            ProposalStatus.DECLINED,
            ProposalStatus.CLARIFICATION_REQUESTED,
            ProposalStatus.SUPERSEDED
        ),
        ProposalStatus.CLARIFICATION_REQUESTED to setOf(
            ProposalStatus.ACCEPTED,
            ProposalStatus.DECLINED,
            ProposalStatus.SUPERSEDED
        ),
        ProposalStatus.ACCEPTED to setOf(
            ProposalStatus.SUPERSEDED
        ),
        ProposalStatus.DECLINED to emptySet<ProposalStatus>(),
        ProposalStatus.SUPERSEDED to emptySet<ProposalStatus>()
    )

    fun canTransition(from: ProposalStatus, to: ProposalStatus): Boolean {
        if (from == to) return true
        return legalTransitions[from]?.contains(to) == true
    }

    fun transition(from: ProposalStatus, to: ProposalStatus): ProposalStatus {
        if (canTransition(from, to)) {
            return to
        }
        throw IllegalStateException("Illegal prescription proposal transition from $from to $to")
    }
}
