package com.meditrack.app.domain.usecase

import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus

object LateEntryPolicy {

    private const val MILLIS_PER_HOUR = 3600 * 1000L

    /**
     * Determines whether a dose can still be logged late with a verified photo.
     * A dose is eligible only if:
     * 1. Its status is MISSED.
     * 2. Current time `now` is on or before the earlier of:
     *    - scheduledAt + LATE_DOSE_MAX_HOURS (24 hours)
     *    - nextScheduledAt (if a future dose is scheduled for this medicine)
     */
    fun canLogLate(dose: DoseEvent, nextScheduledAt: Long?, now: Long): Boolean {
        if (dose.status != DoseStatus.MISSED) return false

        val maxAllowedTime = dose.scheduledAt + (AppConfig.LATE_DOSE_MAX_HOURS * MILLIS_PER_HOUR)
        val cutoff = if (nextScheduledAt != null) {
            minOf(maxAllowedTime, nextScheduledAt)
        } else {
            maxAllowedTime
        }

        return now <= cutoff
    }

    /**
     * Evaluates whether the current time falls within the double-dose guard safety gap.
     * Safety gap is max(1 hour, 25% of the interval between the two doses).
     */
    fun isWithinDoubleDoseSafetyGap(scheduledAt: Long, nextScheduledAt: Long, now: Long): Boolean {
        val interval = nextScheduledAt - scheduledAt
        if (interval <= 0) return true
        val safetyGap = maxOf(MILLIS_PER_HOUR, (interval * 0.25).toLong())
        val timeUntilNextDose = nextScheduledAt - now
        return timeUntilNextDose in 0..safetyGap
    }
}
