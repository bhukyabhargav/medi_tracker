package com.meditrack.app.feature.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {

    const val CHANNEL_DOSE_REMINDERS = "channel_dose_reminders"
    const val CHANNEL_MISSED_DOSES = "channel_missed_doses"
    const val CHANNEL_GENERAL = "channel_general"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val remindersChannel = NotificationChannel(
                CHANNEL_DOSE_REMINDERS,
                "Dose reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority alarms and notifications when it is time to take your scheduled medications"
                enableVibration(true)
                setShowBadge(true)
            }

            val missedChannel = NotificationChannel(
                CHANNEL_MISSED_DOSES,
                "Missed doses",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a medication intake window expires without verified intake"
                enableVibration(true)
                setShowBadge(true)
            }

            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General medication, stock, and account notifications"
            }

            notificationManager.createNotificationChannels(
                listOf(remindersChannel, missedChannel, generalChannel)
            )
        }
    }

    fun getNotificationIdForDose(doseId: String): Int {
        return doseId.hashCode()
    }
}
