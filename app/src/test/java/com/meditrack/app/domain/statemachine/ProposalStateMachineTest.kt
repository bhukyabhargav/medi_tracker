package com.meditrack.app.domain.statemachine

import com.meditrack.app.domain.model.ProposalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalStateMachineTest {

    @Test
    fun pending_canTransitionToAccepted() {
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.PENDING, ProposalStatus.ACCEPTED))
        assertEquals(ProposalStatus.ACCEPTED, ProposalStateMachine.transition(ProposalStatus.PENDING, ProposalStatus.ACCEPTED))
    }

    @Test
    fun pending_canTransitionToDeclined() {
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.PENDING, ProposalStatus.DECLINED))
        assertEquals(ProposalStatus.DECLINED, ProposalStateMachine.transition(ProposalStatus.PENDING, ProposalStatus.DECLINED))
    }

    @Test
    fun pending_canTransitionToClarificationRequested() {
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.PENDING, ProposalStatus.CLARIFICATION_REQUESTED))
        assertEquals(ProposalStatus.CLARIFICATION_REQUESTED, ProposalStateMachine.transition(ProposalStatus.PENDING, ProposalStatus.CLARIFICATION_REQUESTED))
    }

    @Test
    fun pending_canTransitionToSuperseded() {
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.PENDING, ProposalStatus.SUPERSEDED))
        assertEquals(ProposalStatus.SUPERSEDED, ProposalStateMachine.transition(ProposalStatus.PENDING, ProposalStatus.SUPERSEDED))
    }

    @Test
    fun clarificationRequested_canTransitionToAcceptedDeclinedOrSuperseded() {
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.CLARIFICATION_REQUESTED, ProposalStatus.ACCEPTED))
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.CLARIFICATION_REQUESTED, ProposalStatus.DECLINED))
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.CLARIFICATION_REQUESTED, ProposalStatus.SUPERSEDED))
    }

    @Test
    fun accepted_canTransitionToSupersededOnRevision() {
        assertTrue(ProposalStateMachine.canTransition(ProposalStatus.ACCEPTED, ProposalStatus.SUPERSEDED))
    }

    @Test(expected = IllegalStateException::class)
    fun declined_cannotTransitionToAccepted() {
        assertFalse(ProposalStateMachine.canTransition(ProposalStatus.DECLINED, ProposalStatus.ACCEPTED))
        ProposalStateMachine.transition(ProposalStatus.DECLINED, ProposalStatus.ACCEPTED)
    }

    @Test(expected = IllegalStateException::class)
    fun superseded_cannotTransitionToPending() {
        assertFalse(ProposalStateMachine.canTransition(ProposalStatus.SUPERSEDED, ProposalStatus.PENDING))
        ProposalStateMachine.transition(ProposalStatus.SUPERSEDED, ProposalStatus.PENDING)
    }

    @Test(expected = IllegalStateException::class)
    fun accepted_cannotTransitionDirectlyToDeclined() {
        assertFalse(ProposalStateMachine.canTransition(ProposalStatus.ACCEPTED, ProposalStatus.DECLINED))
        ProposalStateMachine.transition(ProposalStatus.ACCEPTED, ProposalStatus.DECLINED)
    }
}
