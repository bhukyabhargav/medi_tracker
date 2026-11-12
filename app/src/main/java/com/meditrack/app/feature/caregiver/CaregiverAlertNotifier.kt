package com.meditrack.app.feature.caregiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.meditrack.app.MainActivity
import com.meditrack.app.R
import com.meditrack.app.feature.reminder.NotificationChannels

object CaregiverAlertNotifier {

    fun postMissedAlert(context: Context, patientName: String, medicineName: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            7777,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationChannels.createChannels(context)

        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_MISSED_DOSES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Caregiver Alert: Missed Dose by $patientName")
            .setContentText("$patientName missed their scheduled dose of $medicineName. Immediate follow-up recommended.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        notificationManager.notify(7777, notification)
    }
}
