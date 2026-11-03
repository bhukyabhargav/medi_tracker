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
}
