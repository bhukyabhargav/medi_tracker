package com.meditrack.app.feature.reminder.alarm

import com.meditrack.app.domain.model.DoseEvent

interface AlarmScheduler {
    fun scheduleDoseAlarm(dose: DoseEvent, triggerAtMillis: Long = dose.nextReminderAt ?: dose.scheduledAt)
    fun scheduleMissedCheck(dose: DoseEvent)
    fun cancelDoseAlarm(doseId: String, cancelMissedWorker: Boolean = true)
    suspend fun rescheduleAllPendingAlarms()
}
