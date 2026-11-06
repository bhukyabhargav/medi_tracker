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
    fun doctorNotifications_produceExactSpecifiedText() {
        val (_, docInvite) = NotificationText.buildText(NotifType.DOCTOR_INVITE_RECEIVED, doctorName = "House")
        assertEquals("House wants to connect as your doctor on MediTrack. Review the request.", docInvite)

        val (_, docInviteAcc) = NotificationText.buildText(NotifType.DOCTOR_INVITE_ACCEPTED, patientName = "Bob")
        assertEquals("Bob accepted your doctor connection.", docInviteAcc)

        val (_, docPropNew) = NotificationText.buildText(NotifType.PRESCRIPTION_PROPOSAL_RECEIVED, doctorName = "House", medicineName = "Metformin")
        assertEquals("Dr House proposed a prescription for Metformin. Review and accept.", docPropNew)

        val (_, docPropAcc) = NotificationText.buildText(NotifType.PRESCRIPTION_ACCEPTED, patientName = "Bob", medicineName = "Metformin")
        assertEquals("Bob accepted the prescription proposal for Metformin.", docPropAcc)

        val (_, docPropRej) = NotificationText.buildText(NotifType.PRESCRIPTION_DECLINED, patientName = "Bob", medicineName = "Metformin")
        assertEquals("Bob declined the prescription proposal for Metformin.", docPropRej)

        val (_, docPropClar) = NotificationText.buildText(NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED, patientName = "Bob", medicineName = "Metformin")
        assertEquals("Bob requested clarification on Metformin.", docPropClar)

        val (_, docInviteDeclined) = NotificationText.buildText(NotifType.DOCTOR_INVITE_DECLINED, patientName = "Bob")
        assertEquals("Bob declined your doctor connection.", docInviteDeclined)

        val (_, docInviteRevoked) = NotificationText.buildText(NotifType.DOCTOR_INVITE_REVOKED)
        assertEquals("Doctor access has been revoked.", docInviteRevoked)

        val (_, docPropRevised) = NotificationText.buildText(NotifType.PRESCRIPTION_REVISED, doctorName = "House", medicineName = "Metformin")
        assertEquals("Dr House updated the prescription proposal for Metformin. Review the new revision.", docPropRevised)
    }
}
