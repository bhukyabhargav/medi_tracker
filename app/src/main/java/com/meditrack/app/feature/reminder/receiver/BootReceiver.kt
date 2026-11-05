package com.meditrack.app.feature.reminder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meditrack.app.MediTrackApp
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    @Inject
    lateinit var medicineRepository: MedicineRepository

    @Inject
    lateinit var saveMedicineUseCase: SaveMedicineUseCase

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED
        ) {
            Log.d(MediTrackApp.TAG, "BootReceiver triggered with action: $action")
            val pendingResult = try { goAsync() } catch (_: Exception) { null }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    handleAction(action)
                } catch (e: Exception) {
                    Log.e(MediTrackApp.TAG, "Error handling $action: ${e.message}", e)
                } finally {
                    pendingResult?.finish()
                }
            }
        }
    }

    private suspend fun handleAction(action: String?) {
        if (action == Intent.ACTION_TIMEZONE_CHANGED) {
            val activeMedicines = medicineRepository.observeActive().firstOrNull() ?: emptyList()
            val zone = ZoneId.systemDefault()
            for (med in activeMedicines) {
                saveMedicineUseCase.updateMedicine(med, zoneId = zone)
            }
            Log.d(MediTrackApp.TAG, "Regenerated future doses for ${activeMedicines.size} medicines in zone $zone")
        } else {
            alarmScheduler.rescheduleAllPendingAlarms()
            Log.d(MediTrackApp.TAG, "Successfully rescheduled pending alarms after $action")
        }
    }

    fun processAction(
        action: String?,
        scheduler: AlarmScheduler = alarmScheduler,
        medRepo: MedicineRepository? = if (::medicineRepository.isInitialized) medicineRepository else null,
        saveUseCase: SaveMedicineUseCase? = if (::saveMedicineUseCase.isInitialized) saveMedicineUseCase else null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ) {
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED
        ) {
            scope.launch {
                try {
                    if (action == Intent.ACTION_TIMEZONE_CHANGED && medRepo != null && saveUseCase != null) {
                        val activeMedicines = medRepo.observeActive().firstOrNull() ?: emptyList()
                        val zone = ZoneId.systemDefault()
                        for (med in activeMedicines) {
                            saveUseCase.updateMedicine(med, zoneId = zone)
                        }
                    } else {
                        scheduler.rescheduleAllPendingAlarms()
                    }
                } catch (e: Exception) {
                    Log.e(MediTrackApp.TAG, "Error processing $action: ${e.message}", e)
                }
            }
        }
    }
}
