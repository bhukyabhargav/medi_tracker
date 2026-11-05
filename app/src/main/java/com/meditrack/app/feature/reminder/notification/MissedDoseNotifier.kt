package com.meditrack.app.feature.reminder.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meditrack.app.MainActivity
import com.meditrack.app.MediTrackApp
import com.meditrack.app.R
import com.meditrack.app.feature.reminder.NotificationChannels

object MissedDoseNotifier {

    fun notify(context: Context, doseId: String, medicineName: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // 1. Cancel the active heads-up reminder for this dose
        notificationManager.cancel(NotificationChannels.getNotificationIdForDose(doseId))

        // 2. Ensure channels exist
        NotificationChannels.createChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            NotificationChannels.getNotificationIdForDose(doseId) + 10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val missedNotification = NotificationCompat.Builder(
            context,
            NotificationChannels.CHANNEL_MISSED_DOSES
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Missed Dose: $medicineName")
            .setContentText("The intake window for $medicineName has passed without verified intake.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        notificationManager.notify(
            NotificationChannels.getNotificationIdForDose(doseId) + 100,
            missedNotification
        )
        Log.d(MediTrackApp.TAG, "Posted missed dose notification for $medicineName ($doseId)")
    }
}
