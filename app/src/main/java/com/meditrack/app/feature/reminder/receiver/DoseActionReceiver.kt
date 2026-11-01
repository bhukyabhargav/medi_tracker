package com.meditrack.app.feature.reminder.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meditrack.app.MediTrackApp
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.usecase.SkipDoseUseCase
import com.meditrack.app.domain.usecase.SnoozeDoseUseCase
import com.meditrack.app.feature.reminder.NotificationChannels
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DoseActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var snoozeDoseUseCase: SnoozeDoseUseCase

    @Inject
    lateinit var skipDoseUseCase: SkipDoseUseCase

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    @Inject
    lateinit var doseRepository: DoseRepository

    override fun onReceive(context: Context, intent: Intent) {
        val doseId = intent.getStringExtra(ReminderReceiver.EXTRA_DOSE_ID) ?: return
        val action = intent.action ?: return
        Log.d(MediTrackApp.TAG, "DoseActionReceiver received action $action for dose $doseId")

        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                when (action) {
                    ACTION_SNOOZE -> {
                        val snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 15)
                        val snoozeResult = snoozeDoseUseCase(doseId, snoozeMinutes = snoozeMinutes)

                        snoozeResult.onSuccess { targetTriggerTime ->
                            val updatedDose = doseRepository.getById(doseId)
                            if (updatedDose != null) {
                                alarmScheduler.scheduleDoseAlarm(updatedDose, targetTriggerTime)
                                notificationManager.cancel(NotificationChannels.getNotificationIdForDose(doseId))
                                Log.d(MediTrackApp.TAG, "Snoozed dose $doseId for $snoozeMinutes min to $targetTriggerTime")
                            }
                        }.onFailure { error ->
                            Log.w(MediTrackApp.TAG, "Failed to snooze dose $doseId: ${error.message}")
                        }
                    }

                    ACTION_SKIP -> {
                        val skipResult = skipDoseUseCase(doseId)
                        skipResult.onSuccess {
                            alarmScheduler.cancelDoseAlarm(doseId)
                            notificationManager.cancel(NotificationChannels.getNotificationIdForDose(doseId))
                            Log.d(MediTrackApp.TAG, "Marked dose $doseId as SKIPPED")
                        }.onFailure { error ->
                            Log.w(MediTrackApp.TAG, "Failed to skip dose $doseId: ${error.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(MediTrackApp.TAG, "Error processing dose action $action for dose $doseId: ${e.message}", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.meditrack.app.ACTION_SNOOZE"
        const val ACTION_SKIP = "com.meditrack.app.ACTION_SKIP"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
    }
}
