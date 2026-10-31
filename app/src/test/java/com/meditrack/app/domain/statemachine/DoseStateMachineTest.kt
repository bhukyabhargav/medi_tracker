package com.meditrack.app.domain.statemachine

import com.meditrack.app.domain.model.DoseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseStateMachineTest {

    // --- LEGAL TRANSITIONS ---

    @Test
    fun pending_to_taken_when_verification_passes() {
        val event = DoseTransitionEvent.VerifyPassed(
            photoPath = "/proofs/dose1.jpg",
            emptyCellsAfter = 1,
            takenAt = 1000L
        )
        val result = DoseStateMachine.transition(DoseStatus.PENDING, event)
        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.TAKEN, result.getOrNull())
    }

    @Test
    fun pending_to_skipped_when_skipped() {
        val result = DoseStateMachine.transition(DoseStatus.PENDING, DoseTransitionEvent.Skip)
        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.SKIPPED, result.getOrNull())
    }

    @Test
    fun pending_to_unverified_when_photo_attempts_exhausted() {
        val result = DoseStateMachine.transition(
            DoseStatus.PENDING,
            DoseTransitionEvent.VerificationFailedExhausted
        )
        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.UNVERIFIED, result.getOrNull())
    }

    @Test
    fun pending_to_missed_when_window_expires() {
        val result = DoseStateMachine.transition(
            DoseStatus.PENDING,
            DoseTransitionEvent.WindowExpired
        )
        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.MISSED, result.getOrNull())
    }

    @Test
    fun unverified_to_missed_when_window_expires() {
        val result = DoseStateMachine.transition(
            DoseStatus.UNVERIFIED,
            DoseTransitionEvent.WindowExpired
        )
        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.MISSED, result.getOrNull())
    }

    @Test
    fun missed_to_taken_late_when_late_verification_passes() {
        val event = DoseTransitionEvent.LateVerifyPassed(
            photoPath = "/proofs/dose_late.jpg",
            emptyCellsAfter = 2,
            takenAt = 5000L
        )
        val result = DoseStateMachine.transition(DoseStatus.MISSED, event)
        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.TAKEN_LATE, result.getOrNull())
    }

    @Test
    fun missed_to_missed_when_reported_manual_unverified() {
        val result = DoseStateMachine.transition(
            DoseStatus.MISSED,
            DoseTransitionEvent.ReportManualUnverified
        )
        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.MISSED, result.getOrNull())
    }

    // --- ILLEGAL TRANSITIONS ---

    @Test
    fun taken_cannot_transition_to_any_state() {
        val events = listOf(
            DoseTransitionEvent.VerifyPassed("/p.jpg", 1, 100L),
            DoseTransitionEvent.Skip,
            DoseTransitionEvent.WindowExpired,
            DoseTransitionEvent.LateVerifyPassed("/p.jpg", 1, 100L),
            DoseTransitionEvent.ReportManualUnverified
        )
        for (event in events) {
            val result = DoseStateMachine.transition(DoseStatus.TAKEN, event)
            assertTrue("Expected failure for TAKEN with $event", result.isFailure)
        }
    }

    @Test
    fun taken_late_cannot_transition_to_any_state() {
        val events = listOf(
            DoseTransitionEvent.VerifyPassed("/p.jpg", 1, 100L),
            DoseTransitionEvent.Skip,
            DoseTransitionEvent.WindowExpired,
            DoseTransitionEvent.LateVerifyPassed("/p.jpg", 1, 100L),
            DoseTransitionEvent.ReportManualUnverified
        )
        for (event in events) {
            val result = DoseStateMachine.transition(DoseStatus.TAKEN_LATE, event)
            assertTrue("Expected failure for TAKEN_LATE with $event", result.isFailure)
        }
    }

    @Test
    fun skipped_cannot_transition_to_any_state() {
        val events = listOf(
            DoseTransitionEvent.VerifyPassed("/p.jpg", 1, 100L),
            DoseTransitionEvent.Skip,
            DoseTransitionEvent.WindowExpired,
            DoseTransitionEvent.LateVerifyPassed("/p.jpg", 1, 100L),
            DoseTransitionEvent.ReportManualUnverified
        )
        for (event in events) {
            val result = DoseStateMachine.transition(DoseStatus.SKIPPED, event)
            assertTrue("Expected failure for SKIPPED with $event", result.isFailure)
        }
    }

    @Test
    fun pending_cannot_transition_to_taken_late() {
        val event = DoseTransitionEvent.LateVerifyPassed("/p.jpg", 1, 100L)
        val result = DoseStateMachine.transition(DoseStatus.PENDING, event)
        assertTrue(result.isFailure)
    }

    @Test
    fun missed_cannot_transition_to_on_time_taken() {
        val event = DoseTransitionEvent.VerifyPassed("/p.jpg", 1, 100L)
        val result = DoseStateMachine.transition(DoseStatus.MISSED, event)
        assertTrue(result.isFailure)
    }

    @Test
    fun unverified_cannot_transition_to_skipped() {
        val result = DoseStateMachine.transition(DoseStatus.UNVERIFIED, DoseTransitionEvent.Skip)
        assertTrue(result.isFailure)
    }
}
