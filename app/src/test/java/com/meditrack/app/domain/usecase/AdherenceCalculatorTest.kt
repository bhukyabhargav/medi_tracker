package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdherenceCalculatorTest {

    private fun createDose(
        status: DoseStatus,
        manualUnverified: Boolean = false,
        id: String = "dose_${System.nanoTime()}"
    ): DoseEvent {
        return DoseEvent(
            id = id,
            medicineId = "med_1",
            scheduledAt = 1000L,
            windowEndsAt = 4600L,
            status = status,
            manualUnverified = manualUnverified
        )
    }

    @Test
    fun zero_closed_doses_returns_null_and_dash() {
        val doses = listOf(
            createDose(DoseStatus.PENDING),
            createDose(DoseStatus.PENDING)
        )

        val report = AdherenceCalculator.calculate(doses)
        assertNull(report.percentage)
        assertEquals("—", report.percentageFormatted)
        assertEquals("—", report.displayText)
        assertEquals(0, report.closedDosesCount)
    }

    @Test
    fun all_verified_taken_returns_100_percent() {
        val doses = listOf(
            createDose(DoseStatus.TAKEN),
            createDose(DoseStatus.TAKEN),
            createDose(DoseStatus.TAKEN)
        )

        val report = AdherenceCalculator.calculate(doses)
        assertEquals(100.0, report.percentage!!, 0.01)
        assertEquals("100%", report.percentageFormatted)
        assertEquals("100%", report.displayText)
        assertEquals(3, report.verifiedTakenCount)
        assertEquals(3, report.closedDosesCount)
    }

    @Test
    fun adherence_calculation_with_late_doses_and_missed() {
        // 46 taken on time, 4 taken late, 4 missed
        val doses = mutableListOf<DoseEvent>()
        repeat(46) { doses.add(createDose(DoseStatus.TAKEN)) }
        repeat(4) { doses.add(createDose(DoseStatus.TAKEN_LATE)) }
        repeat(4) { doses.add(createDose(DoseStatus.MISSED)) }

        val report = AdherenceCalculator.calculate(doses)

        // verifiedTaken = 50, closedDoses = 54
        // 50 / 54 * 100 = 92.59% -> rounds to 93%
        assertEquals(50, report.verifiedTakenCount)
        assertEquals(54, report.closedDosesCount)
        assertEquals(92.59, report.percentage!!, 0.01)
        assertEquals("93%", report.percentageFormatted)
        assertEquals("93% (4 late)", report.displayText)
    }

    @Test
    fun manual_unverified_counts_as_missed_and_never_as_taken() {
        val doses = listOf(
            createDose(DoseStatus.TAKEN),
            createDose(DoseStatus.MISSED, manualUnverified = true)
        )

        val report = AdherenceCalculator.calculate(doses)
        // verifiedTaken = 1, closedDoses = 2 -> 50%
        assertEquals(1, report.verifiedTakenCount)
        assertEquals(2, report.closedDosesCount)
        assertEquals(50.0, report.percentage!!, 0.01)
        assertEquals(1, report.manualUnverifiedCount)
        assertEquals(1, report.missedCount)
    }

    @Test
    fun unverifiedDoseWithOpenWindow_isNotCountedAsClosed() {
        val now = 10_000L
        val openUnverified = DoseEvent(
            id = "u_open", medicineId = "med_1", scheduledAt = 9_000L, windowEndsAt = 12_000L,
            status = DoseStatus.UNVERIFIED
        )
        val taken = createDose(DoseStatus.TAKEN)
        val report = AdherenceCalculator.calculate(listOf(openUnverified, taken), now = now)
        assertEquals(1, report.closedDosesCount)
        assertEquals(0, report.unverifiedClosedCount)
        assertEquals("100%", report.percentageFormatted)

        val closedLater = AdherenceCalculator.calculate(listOf(openUnverified, taken), now = 12_000L)
        assertEquals(2, closedLater.closedDosesCount)
        assertEquals("50%", closedLater.percentageFormatted)
    }
}
