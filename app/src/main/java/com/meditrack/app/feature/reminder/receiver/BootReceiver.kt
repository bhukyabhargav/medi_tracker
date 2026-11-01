package com.meditrack.app.feature.reminder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meditrack.app.MediTrackApp
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(MediTrackApp.TAG, "BootReceiver triggered with action: $action")
            val pendingResult = try { goAsync() } catch (_: Exception) { null }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    alarmScheduler.rescheduleAllPendingAlarms()
                    Log.d(MediTrackApp.TAG, "Successfully rescheduled pending alarms after boot/update")
                } catch (e: Exception) {
                    Log.e(MediTrackApp.TAG, "Error rescheduling alarms after boot: ${e.message}", e)
                } finally {
                    pendingResult?.finish()
                }
            }
        }
    }

    fun processAction(
        action: String?,
        scheduler: AlarmScheduler = alarmScheduler,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ) {
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            scope.launch {
                try {
                    scheduler.rescheduleAllPendingAlarms()
                } catch (e: Exception) {
                    Log.e(MediTrackApp.TAG, "Error rescheduling alarms: ${e.message}", e)
                }
            }
        }
    }
}
