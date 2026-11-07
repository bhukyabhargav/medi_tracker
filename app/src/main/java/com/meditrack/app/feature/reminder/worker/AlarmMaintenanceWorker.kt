package com.meditrack.app.feature.reminder.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meditrack.app.MediTrackApp
import com.meditrack.app.core.config.AppConfig
import androidx.work.workDataOf
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import kotlinx.coroutines.flow.first
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Keeps exact alarms armed for the next [AppConfig.ALARM_HORIZON_HOURS] hours.
 *
 * Only the near future is armed (Android caps an app at ~500 alarms), so something has to top the
 * horizon up: this worker runs periodically and once at every app start.
 */
@HiltWorker
class AlarmMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val alarmScheduler: AlarmScheduler,
    private val medicineRepository: MedicineRepository,
    private val saveMedicineUseCase: SaveMedicineUseCase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            if (inputData.getBoolean(KEY_REGENERATE, false)) {
                // Time zone changed: future PENDING doses were generated for the old zone's wall clock.
                // Regenerate them for the current zone (history and in-window doses are untouched).
                for (medicine in medicineRepository.observeActive().first()) {
                    if (!medicine.stoppedByDoctor) saveMedicineUseCase.updateMedicine(medicine)
                }
            }
            alarmScheduler.rescheduleAllPendingAlarms()
            Result.success()
        } catch (e: Exception) {
            Log.e(MediTrackApp.TAG, "AlarmMaintenanceWorker failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "AlarmMaintenancePeriodic"
        const val ONCE_NAME = "AlarmMaintenanceOnce"
        const val REGENERATE_NAME = "AlarmMaintenanceRegenerate"
        const val KEY_REGENERATE = "regenerate"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlarmMaintenanceWorker>(
                    AppConfig.ALARM_MAINTENANCE_INTERVAL_HOURS, TimeUnit.HOURS
                ).build()
            )
            runNow(context)
        }

        /** One-time run (app start, time/zone change). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AlarmMaintenanceWorker>().build()
            )
        }

        /** Time zone change: regenerate future doses in the new zone, then re-arm alarms. */
        fun regenerateForTimeZoneChange(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                REGENERATE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AlarmMaintenanceWorker>()
                    .setInputData(workDataOf(KEY_REGENERATE to true))
                    .build()
            )
        }
    }
}
