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

    @Test
    fun calculateByMedicine_splitsDosesPerMedicine_andListsEveryMedicine() {
        fun dose(id: String, med: String, status: DoseStatus) = DoseEvent(
            id = id, medicineId = med, scheduledAt = 1_000L, windowEndsAt = 2_000L, status = status
        )
        val doses = listOf(
            dose("a1", "med_a", DoseStatus.TAKEN),
            dose("a2", "med_a", DoseStatus.TAKEN),
            dose("b1", "med_b", DoseStatus.TAKEN),
            dose("b2", "med_b", DoseStatus.MISSED),
            dose("x1", "med_gone", DoseStatus.SKIPPED)
        )
        val names = mapOf("med_b" to "Metformin", "med_a" to "Dolo", "med_c" to "Aspirin")

        val result = AdherenceCalculator.calculateByMedicine(doses, names, now = 5_000L)

        // Sorted by name; a medicine without doses is still listed; an unknown medicine keeps its doses
        assertEquals(listOf("Aspirin", "Dolo", "Metformin", "Removed medicine"), result.map { it.medicineName })
        assertEquals("—", result[0].report.percentageFormatted)
        assertEquals("100%", result[1].report.percentageFormatted)
        assertEquals("50%", result[2].report.percentageFormatted)
        assertEquals(1, result[2].report.missedCount)
        assertEquals("0%", result[3].report.percentageFormatted)

        // The per-medicine counts add up to the overall report
        val overall = AdherenceCalculator.calculate(doses, now = 5_000L)
        assertEquals(overall.closedDosesCount, result.sumOf { it.report.closedDosesCount })
        assertEquals(overall.verifiedTakenCount, result.sumOf { it.report.verifiedTakenCount })
    }
}
