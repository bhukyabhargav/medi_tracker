package com.meditrack.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {

    @Test
    fun missedDose_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(NotifType.MISSED_DOSE)
        assertEquals("Medicine dose has been missed.", body)
    }

    @Test
    fun lowStock_producesExactSpecifiedText_withMedicineName() {
        val (_, body) = NotificationText.buildText(NotifType.LOW_STOCK, medicineName = "Metformin")
        assertEquals("Metformin is running low \u2013 refill needed.", body)
    }

    @Test
    fun doseUnverified_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(NotifType.DOSE_UNVERIFIED)
        assertEquals("Dose could not be verified.", body)
    }

    @Test
    fun doseTakenLate_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(NotifType.DOSE_TAKEN_LATE)
        assertEquals("Missed dose was taken late (verified).", body)
    }

    @Test
    fun caregiverInvite_producesExactSpecifiedText_withPatientName() {
        val (_, body) = NotificationText.buildText(NotifType.CAREGIVER_INVITE, patientName = "Alice")
        assertEquals("Alice invited you to be their caregiver.", body)
    }

    @Test
    fun doctorInviteReceived_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(NotifType.DOCTOR_INVITE_RECEIVED, doctorName = "Dr. Smith")
        assertEquals("Dr. Smith wants to connect as your doctor on MediTrack. Review the request.", body)
    }

    @Test
    fun doctorInviteAccepted_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(NotifType.DOCTOR_INVITE_ACCEPTED, patientName = "Alice")
        assertEquals("Alice accepted your doctor connection.", body)
    }

    @Test
    fun doctorInviteDeclined_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(NotifType.DOCTOR_INVITE_DECLINED, patientName = "Alice")
        assertEquals("Alice declined your doctor connection.", body)
    }

    @Test
    fun doctorInviteRevoked_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(NotifType.DOCTOR_INVITE_REVOKED)
        assertEquals("Doctor access has been revoked.", body)
    }

    @Test
    fun prescriptionProposalReceived_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(
            NotifType.PRESCRIPTION_PROPOSAL_RECEIVED,
            doctorName = "Smith",
            medicineName = "Amoxicillin"
        )
        assertEquals("Dr Smith proposed a prescription for Amoxicillin. Review and accept.", body)
    }

    @Test
    fun prescriptionAccepted_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(
            NotifType.PRESCRIPTION_ACCEPTED,
            patientName = "Alice",
            medicineName = "Amoxicillin"
        )
        assertEquals("Alice accepted the prescription proposal for Amoxicillin.", body)
    }

    @Test
    fun prescriptionDeclined_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(
            NotifType.PRESCRIPTION_DECLINED,
            patientName = "Alice",
            medicineName = "Amoxicillin"
        )
        assertEquals("Alice declined the prescription proposal for Amoxicillin.", body)
    }

    @Test
    fun prescriptionClarificationRequested_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(
            NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED,
            patientName = "Alice",
            medicineName = "Amoxicillin"
        )
        assertEquals("Alice requested clarification on Amoxicillin.", body)
    }

    @Test
    fun prescriptionRevised_producesExactSpecifiedText() {
        val (_, body) = NotificationText.buildText(
            NotifType.PRESCRIPTION_REVISED,
            doctorName = "Smith",
            medicineName = "Amoxicillin"
        )
        assertEquals("Dr Smith updated the prescription proposal for Amoxicillin. Review the new revision.", body)
    }
}
