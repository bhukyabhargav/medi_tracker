package com.meditrack.app.feature.reminder.alarm

import com.meditrack.app.domain.model.DoseEvent

interface AlarmScheduler {
    fun scheduleDoseAlarm(dose: DoseEvent, triggerAtMillis: Long = dose.scheduledAt)
    fun cancelDoseAlarm(doseId: String)
    suspend fun rescheduleAllPendingAlarms()
}
