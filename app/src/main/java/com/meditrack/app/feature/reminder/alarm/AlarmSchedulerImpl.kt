package com.meditrack.app.feature.reminder.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.meditrack.app.MainActivity
import com.meditrack.app.MediTrackApp
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.feature.reminder.NotificationChannels
import com.meditrack.app.feature.reminder.receiver.ReminderReceiver
import com.meditrack.app.feature.reminder.worker.MissedDoseWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository
) : AlarmScheduler {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override fun scheduleDoseAlarm(dose: DoseEvent, triggerAtMillis: Long) {
        val now = System.currentTimeMillis()
        if (now >= dose.windowEndsAt) {
            Log.d(MediTrackApp.TAG, "Skipping alarm for dose ${dose.id}: intake window already expired")
            return
        }

        // If triggerAtMillis is in the past, but the dose is still inside its window, trigger immediately
        val effectiveTrigger = maxOf(now + 1000L, triggerAtMillis)

        // Beyond the alarm horizon only the MISSED check is enqueued; AlarmMaintenanceWorker arms
        // the exact alarm later (keeps us far below Android's per-app alarm limit).
        if (effectiveTrigger > now + AppConfig.ALARM_HORIZON_HOURS * 60 * 60 * 1000L) {
            scheduleMissedWorker(dose)
            return
        }

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_DOSE_REMINDER
            putExtra(ReminderReceiver.EXTRA_DOSE_ID, dose.id)
            putExtra(ReminderReceiver.EXTRA_MEDICINE_ID, dose.medicineId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NotificationChannels.getNotificationIdForDose(dose.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            NotificationChannels.getNotificationIdForDose(dose.id),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (alarmManager != null) {
                val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }

                if (canScheduleExact) {
                    val clockInfo = AlarmManager.AlarmClockInfo(effectiveTrigger, showPendingIntent)
                    alarmManager.setAlarmClock(clockInfo, pendingIntent)
                    Log.d(MediTrackApp.TAG, "Scheduled exact AlarmClock for dose ${dose.id} at $effectiveTrigger")
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveTrigger, pendingIntent)
                    Log.d(MediTrackApp.TAG, "Scheduled inexact fallback alarm for dose ${dose.id} at $effectiveTrigger")
                }
            }
        } catch (e: Exception) {
            Log.e(MediTrackApp.TAG, "Failed to schedule alarm for dose ${dose.id}: ${e.message}", e)
        }

        // Schedule one-time MissedDoseWorker at windowEndsAt
        scheduleMissedWorker(dose)
    }

    override fun scheduleMissedCheck(dose: DoseEvent) = scheduleMissedWorker(dose)

    private fun scheduleMissedWorker(dose: DoseEvent) {
        try {
            val now = System.currentTimeMillis()
            if (now >= dose.windowEndsAt) return
            val delayMillis = maxOf(1000L, dose.windowEndsAt - now)

            val missedRequest = OneTimeWorkRequestBuilder<MissedDoseWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(MissedDoseWorker.KEY_DOSE_ID to dose.id))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "missed_${dose.id}",
                ExistingWorkPolicy.REPLACE,
                missedRequest
            )
            Log.d(MediTrackApp.TAG, "Enqueued MissedDoseWorker for dose ${dose.id} in ${delayMillis / 1000}s")
        } catch (e: Exception) {
            Log.e(MediTrackApp.TAG, "Failed to schedule MissedDoseWorker for dose ${dose.id}: ${e.message}", e)
        }
    }

    override fun cancelDoseAlarm(doseId: String, cancelMissedWorker: Boolean) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_DOSE_REMINDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NotificationChannels.getNotificationIdForDose(doseId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null && alarmManager != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        if (cancelMissedWorker) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork("missed_$doseId")
            } catch (e: Exception) {
                Log.w(MediTrackApp.TAG, "Failed to cancel missed worker for dose $doseId: ${e.message}")
            }
        }

        // Cancel any active reminder or missed notification for this dose from the system drawer
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NotificationChannels.getNotificationIdForDose(doseId))
            notificationManager?.cancel(NotificationChannels.getNotificationIdForDose(doseId) + 100)
        } catch (e: Exception) {
            Log.w(MediTrackApp.TAG, "Failed to cancel notification for dose $doseId: ${e.message}")
        }

        Log.d(MediTrackApp.TAG, "Cancelled alarm and cleared notification (missedWorkerCancelled=$cancelMissedWorker) for dose $doseId")
    }

    override suspend fun rescheduleAllPendingAlarms() {
        val now = System.currentTimeMillis()
        val pendingDoses = doseRepository.getPendingDueDoses(now) + doseRepository.getFuturePendingDoses(now)
        val distinctDoses = pendingDoses.distinctBy { it.id }.filter { it.windowEndsAt > now }

        Log.d(MediTrackApp.TAG, "Rescheduling ${distinctDoses.size} pending dose alarms")

        val medicines = distinctDoses.map { it.medicineId }.distinct()
            .associateWith { medicineRepository.getById(it) }

        for (dose in distinctDoses.sortedBy { it.scheduledAt }) {
            val medicine = medicines[dose.medicineId]
            if (medicine == null || medicine.isDeleted || medicine.stoppedByDoctor) continue
            if (medicine.reminderEnabled) {
                // scheduleDoseAlarm honours nextReminderAt (snooze), fires in-window doses
                // immediately and applies the alarm horizon.
                scheduleDoseAlarm(dose, dose.nextReminderAt ?: dose.scheduledAt)
            } else {
                scheduleMissedWorker(dose)
            }
        }
    }
}
