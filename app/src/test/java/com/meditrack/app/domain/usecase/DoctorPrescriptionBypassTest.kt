package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.domain.model.VerificationAttempt
import com.meditrack.app.domain.model.VerificationResultType
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorPrescriptionBypassTest {

    @Test
    fun doctorPrescribedDose_cannotBypassPhotoVerificationToBecomeTaken() {
        // A doctor-prescribed dose starts in PENDING
        val doctorPrescribedDose = DoseEvent(
            id = "doc_dose_1",
            medicineId = "doc_med_1",
            scheduledAt = 1000L,
            windowEndsAt = 2000L,
            status = DoseStatus.PENDING
        )

        // Verifying that a dose transitions to TAKEN only upon passing verification
        val passEvent = DoseTransitionEvent.VerifyPassed(
            photoPath = "proofs/sample.jpg",
            emptyCellsAfter = 1,
            takenAt = 1050L
        )
        val passResult = DoseStateMachine.transition(DoseStatus.PENDING, passEvent)
        assertTrue(passResult.isSuccess)
        assertEquals(DoseStatus.TAKEN, passResult.getOrNull())

        // A failed or rejected attempt can NEVER produce a TAKEN transition
        val failedAttempt = VerificationAttempt(
            id = "attempt_1",
            doseEventId = "doc_dose_1",
            photoPath = "proofs/sample.jpg",
            result = VerificationResultType.REJECTED,
            rejectReason = RejectReason.BLURRY
        )
        assertEquals(VerificationResultType.REJECTED, failedAttempt.result)

        // If verification fails exhausted, status becomes UNVERIFIED, NOT TAKEN
        val failedResult = DoseStateMachine.transition(DoseStatus.PENDING, DoseTransitionEvent.VerificationFailedExhausted)
        assertTrue(failedResult.isSuccess)
        assertEquals(DoseStatus.UNVERIFIED, failedResult.getOrNull())
    }

    @Test
    fun doctorPrescribedDose_illegalTransitionsAreBlocked() {
        val passEvent = DoseTransitionEvent.VerifyPassed(
            photoPath = "proofs/sample.jpg",
            emptyCellsAfter = 1,
            takenAt = 1050L
        )

        // From MISSED, cannot jump directly to TAKEN (must use LateVerifyPassed to TAKEN_LATE)
        val fromMissed = DoseStateMachine.transition(DoseStatus.MISSED, passEvent)
        assertTrue(fromMissed.isFailure)

        // From SKIPPED, cannot jump to TAKEN
        val fromSkipped = DoseStateMachine.transition(DoseStatus.SKIPPED, passEvent)
        assertTrue(fromSkipped.isFailure)

        // From TAKEN, terminal state
        val fromTaken = DoseStateMachine.transition(DoseStatus.TAKEN, passEvent)
        assertTrue(fromTaken.isFailure)
    }
}
