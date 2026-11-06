package com.meditrack.app.feature.reminder.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meditrack.app.MainActivity
import com.meditrack.app.MediTrackApp
import com.meditrack.app.R
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.feature.reminder.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var doseRepository: DoseRepository

    @Inject
    lateinit var medicineRepository: MedicineRepository

    override fun onReceive(context: Context, intent: Intent) {
        val doseId = intent.getStringExtra(EXTRA_DOSE_ID) ?: return
        Log.d(MediTrackApp.TAG, "ReminderReceiver triggered for dose $doseId")

        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationChannels.createChannels(context)

                val dose = doseRepository.getById(doseId)
                if (dose == null || dose.status != DoseStatus.PENDING) {
                    Log.d(MediTrackApp.TAG, "Dose $doseId is not pending (status: ${dose?.status}), skipping notification")
                    return@launch
                }

                val medicine = medicineRepository.getById(dose.medicineId)
                if (medicine != null && (medicine.isDeleted || medicine.stoppedByDoctor)) {
                    Log.d(MediTrackApp.TAG, "Medicine ${medicine.name} is deleted or stopped, skipping notification")
                    return@launch
                }
                if (medicine?.reminderEnabled == false) {
                    Log.d(MediTrackApp.TAG, "Reminder disabled for medicine ${medicine.name}, skipping notification")
                    return@launch
                }
                val medicineName = medicine?.name ?: "Medicine"
                val dosage = medicine?.dosageText ?: ""
                val pills = medicine?.pillsPerDose ?: 1
                val food = medicine?.foodRelation?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""

                // 1. Action: Taken -> Launches Verification Stub
                val takenIntent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_TAKEN_VERIFY
                    putExtra(EXTRA_DOSE_ID, doseId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val takenPendingIntent = PendingIntent.getActivity(
                    context,
                    NotificationChannels.getNotificationIdForDose(doseId) + 1,
                    takenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // 2. Action: Snooze (15m) -> Handled by DoseActionReceiver
                val snoozeIntent = Intent(context, DoseActionReceiver::class.java).apply {
                    action = DoseActionReceiver.ACTION_SNOOZE
                    putExtra(EXTRA_DOSE_ID, doseId)
                    putExtra(DoseActionReceiver.EXTRA_SNOOZE_MINUTES, 15)
                }
                val snoozePendingIntent = PendingIntent.getBroadcast(
                    context,
                    NotificationChannels.getNotificationIdForDose(doseId) + 2,
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // 3. Action: Skip -> Handled by DoseActionReceiver
                val skipIntent = Intent(context, DoseActionReceiver::class.java).apply {
                    action = DoseActionReceiver.ACTION_SKIP
                    putExtra(EXTRA_DOSE_ID, doseId)
                }
                val skipPendingIntent = PendingIntent.getBroadcast(
                    context,
                    NotificationChannels.getNotificationIdForDose(doseId) + 3,
                    skipIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val contentText = buildString {
                    if (dosage.isNotBlank()) append(dosage)
                    append(" • $pills pill(s)")
                    if (food.isNotBlank()) append(" • $food")
                }

                val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_DOSE_REMINDERS)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Time to take $medicineName")
                    .setContentText(contentText)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(false)
                    .setOngoing(false)
                    .setContentIntent(takenPendingIntent)
                    .addAction(R.drawable.ic_launcher_foreground, "Taken", takenPendingIntent)
                    .addAction(R.drawable.ic_launcher_foreground, "Snooze 15m", snoozePendingIntent)
                    .addAction(R.drawable.ic_launcher_foreground, "Skip", skipPendingIntent)
                    .build()

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NotificationChannels.getNotificationIdForDose(doseId), notification)
                Log.d(MediTrackApp.TAG, "Posted dose reminder notification for $medicineName ($doseId)")
            } catch (e: Exception) {
                Log.e(MediTrackApp.TAG, "Error displaying reminder notification for dose $doseId: ${e.message}", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_DOSE_REMINDER = "com.meditrack.app.ACTION_DOSE_REMINDER"
        const val ACTION_TAKEN_VERIFY = "com.meditrack.app.ACTION_TAKEN_VERIFY"
        const val EXTRA_DOSE_ID = "extra_dose_id"
        const val EXTRA_MEDICINE_ID = "extra_medicine_id"
    }
}
