package com.meditrack.app.domain.statemachine

import com.meditrack.app.domain.model.ProposalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalStateMachineTest {

    @Test
    fun pending_accept_transitionsToAccepted() {
        val result = ProposalStateMachine.transition(ProposalStatus.PENDING, ProposalTransitionEvent.Accept)
        assertTrue(result.isSuccess)
        assertEquals(ProposalStatus.ACCEPTED, result.getOrNull())
    }

    @Test
    fun pending_decline_transitionsToDeclined() {
        val result = ProposalStateMachine.transition(ProposalStatus.PENDING, ProposalTransitionEvent.Decline)
        assertTrue(result.isSuccess)
        assertEquals(ProposalStatus.DECLINED, result.getOrNull())
    }

    @Test
    fun pending_requestClarification_transitionsToClarificationRequested() {
        val result = ProposalStateMachine.transition(
            ProposalStatus.PENDING,
            ProposalTransitionEvent.RequestClarification("Please clarify frequency")
        )
        assertTrue(result.isSuccess)
        assertEquals(ProposalStatus.CLARIFICATION_REQUESTED, result.getOrNull())
    }

    @Test
    fun pending_supersede_transitionsToSuperseded() {
        val result = ProposalStateMachine.transition(
            ProposalStatus.PENDING,
            ProposalTransitionEvent.Supersede(newRevision = 2)
        )
        assertTrue(result.isSuccess)
        assertEquals(ProposalStatus.SUPERSEDED, result.getOrNull())
    }

    @Test
    fun clarificationRequested_accept_transitionsToAccepted() {
        val result = ProposalStateMachine.transition(
            ProposalStatus.CLARIFICATION_REQUESTED,
            ProposalTransitionEvent.Accept
        )
        assertTrue(result.isSuccess)
        assertEquals(ProposalStatus.ACCEPTED, result.getOrNull())
    }

    @Test
    fun clarificationRequested_decline_transitionsToDeclined() {
        val result = ProposalStateMachine.transition(
            ProposalStatus.CLARIFICATION_REQUESTED,
            ProposalTransitionEvent.Decline
        )
        assertTrue(result.isSuccess)
        assertEquals(ProposalStatus.DECLINED, result.getOrNull())
    }

    @Test
    fun clarificationRequested_supersede_transitionsToSuperseded() {
        val result = ProposalStateMachine.transition(
            ProposalStatus.CLARIFICATION_REQUESTED,
            ProposalTransitionEvent.Supersede(newRevision = 2)
        )
        assertTrue(result.isSuccess)
        assertEquals(ProposalStatus.SUPERSEDED, result.getOrNull())
    }

    @Test
    fun accepted_anyEvent_failsIllegalTransition() {
        val events = listOf(
            ProposalTransitionEvent.Accept,
            ProposalTransitionEvent.Decline,
            ProposalTransitionEvent.RequestClarification("Need more info"),
            ProposalTransitionEvent.Supersede(2)
        )
        for (event in events) {
            val result = ProposalStateMachine.transition(ProposalStatus.ACCEPTED, event)
            assertTrue("Expected failure for accepted with $event", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalProposalTransitionException)
        }
    }

    @Test
    fun declined_anyEvent_failsIllegalTransition() {
        val events = listOf(
            ProposalTransitionEvent.Accept,
            ProposalTransitionEvent.Decline,
            ProposalTransitionEvent.RequestClarification("Need more info"),
            ProposalTransitionEvent.Supersede(2)
        )
        for (event in events) {
            val result = ProposalStateMachine.transition(ProposalStatus.DECLINED, event)
            assertTrue("Expected failure for declined with $event", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalProposalTransitionException)
        }
    }

    @Test
    fun superseded_anyEvent_failsIllegalTransition() {
        val events = listOf(
            ProposalTransitionEvent.Accept,
            ProposalTransitionEvent.Decline,
            ProposalTransitionEvent.RequestClarification("Need more info"),
            ProposalTransitionEvent.Supersede(2)
        )
        for (event in events) {
            val result = ProposalStateMachine.transition(ProposalStatus.SUPERSEDED, event)
            assertTrue("Expected failure for superseded with $event", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalProposalTransitionException)
        }
    }
}
