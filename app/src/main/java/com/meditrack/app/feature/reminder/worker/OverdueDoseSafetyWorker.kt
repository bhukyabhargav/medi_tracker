package com.meditrack.app.feature.reminder.worker

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meditrack.app.MediTrackApp
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.ProcessMissedDoseUseCase
import com.meditrack.app.feature.reminder.MissedDoseNotifier
import com.meditrack.app.feature.reminder.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class OverdueDoseSafetyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val doseRepository: DoseRepository,
    private val processMissedDoseUseCase: ProcessMissedDoseUseCase,
    private val medicineRepository: MedicineRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val overdueDoses = doseRepository.getOverduePendingDoses(now)
            Log.d(MediTrackApp.TAG, "OverdueDoseSafetyWorker checking: found ${overdueDoses.size} overdue doses")

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

            for (dose in overdueDoses) {
                // Cancel active reminder heads-up
                notificationManager?.cancel(NotificationChannels.getNotificationIdForDose(dose.id))

                val result = processMissedDoseUseCase(dose.id, now)
                result.onSuccess { transitioned ->
                    if (transitioned) {
                        Log.d(MediTrackApp.TAG, "OverdueDoseSafetyWorker marked dose ${dose.id} as MISSED")
                        val name = medicineRepository.getById(dose.medicineId)?.name ?: "Medication"
                        MissedDoseNotifier.post(applicationContext, dose.id, name)
                    }
                }.onFailure { error ->
                    Log.w(MediTrackApp.TAG, "OverdueDoseSafetyWorker failed for dose ${dose.id}: ${error.message}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(MediTrackApp.TAG, "OverdueDoseSafetyWorker error: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "OverdueDoseSafetyWork"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OverdueDoseSafetyWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
