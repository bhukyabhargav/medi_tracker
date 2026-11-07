package com.meditrack.app.feature.reminder.alarm

import com.meditrack.app.domain.model.DoseEvent

interface AlarmScheduler {
    fun scheduleDoseAlarm(dose: DoseEvent, triggerAtMillis: Long = dose.nextReminderAt ?: dose.scheduledAt)
    fun cancelDoseAlarm(doseId: String, cancelMissedWorker: Boolean = true)
    /** Only enqueue the MISSED check for this dose (used when reminders are switched off). */
    fun scheduleMissedCheck(dose: DoseEvent)
    suspend fun rescheduleAllPendingAlarms()
}
