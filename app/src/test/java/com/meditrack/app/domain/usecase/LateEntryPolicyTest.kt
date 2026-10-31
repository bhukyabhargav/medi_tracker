package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LateEntryPolicyTest {

    private val oneHourMillis = 3600 * 1000L

    private fun createMissedDose(scheduledAt: Long): DoseEvent {
        return DoseEvent(
            id = "dose_1",
            medicineId = "med_1",
            scheduledAt = scheduledAt,
            windowEndsAt = scheduledAt + oneHourMillis,
            status = DoseStatus.MISSED
        )
    }

    @Test
    fun non_missed_dose_cannot_be_logged_late() {
        val dose = DoseEvent(
            id = "dose_1",
            medicineId = "med_1",
            scheduledAt = 1000L,
            windowEndsAt = 4600L,
            status = DoseStatus.PENDING
        )
        assertFalse(LateEntryPolicy.canLogLate(dose, nextScheduledAt = 50000L, now = 2000L))
    }

    @Test
    fun next_scheduled_dose_is_earlier_than_24_hours_wins() {
        val scheduledAt = 100000L
        val missedDose = createMissedDose(scheduledAt)
        // Next dose is 12 hours later (earlier than 24h max)
        val nextScheduledAt = scheduledAt + (12 * oneHourMillis)

        // At 10 hours later: within cutoff -> allowed
        assertTrue(LateEntryPolicy.canLogLate(missedDose, nextScheduledAt, now = scheduledAt + (10 * oneHourMillis)))

        // At 13 hours later: after next dose -> locked
        assertFalse(LateEntryPolicy.canLogLate(missedDose, nextScheduledAt, now = scheduledAt + (13 * oneHourMillis)))
    }

    @Test
    fun max_hours_24h_is_earlier_than_next_scheduled_dose_wins() {
        val scheduledAt = 100000L
        val missedDose = createMissedDose(scheduledAt)
        // Next dose is 48 hours later (e.g. alternate days)
        val nextScheduledAt = scheduledAt + (48 * oneHourMillis)

        // At 23 hours later: within 24h -> allowed
        assertTrue(LateEntryPolicy.canLogLate(missedDose, nextScheduledAt, now = scheduledAt + (23 * oneHourMillis)))

        // At 25 hours later: past 24h limit -> locked
        assertFalse(LateEntryPolicy.canLogLate(missedDose, nextScheduledAt, now = scheduledAt + (25 * oneHourMillis)))
    }

    @Test
    fun no_future_dose_uses_24h_limit() {
        val scheduledAt = 100000L
        val missedDose = createMissedDose(scheduledAt)

        // Within 24 hours
        assertTrue(LateEntryPolicy.canLogLate(missedDose, nextScheduledAt = null, now = scheduledAt + (24 * oneHourMillis)))

        // Past 24 hours
        assertFalse(LateEntryPolicy.canLogLate(missedDose, nextScheduledAt = null, now = scheduledAt + (24 * oneHourMillis + 1)))
    }

    @Test
    fun double_dose_guard_calculates_correct_safety_gap() {
        val dose1 = 100000L
        val dose2 = dose1 + (8 * oneHourMillis) // 8 hour interval
        // 25% of 8h = 2h; max(1h, 2h) = 2h safety gap

        // 1 hour before dose 2 -> within safety gap (warning should show)
        assertTrue(LateEntryPolicy.isWithinDoubleDoseSafetyGap(dose1, dose2, now = dose2 - (1 * oneHourMillis)))

        // 3 hours before dose 2 -> outside safety gap
        assertFalse(LateEntryPolicy.isWithinDoubleDoseSafetyGap(dose1, dose2, now = dose2 - (3 * oneHourMillis)))
    }
}
