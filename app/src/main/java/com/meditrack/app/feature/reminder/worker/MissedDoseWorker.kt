package com.meditrack.app.feature.reminder.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meditrack.app.feature.reminder.notification.MissedDoseNotifier
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meditrack.app.MainActivity
import com.meditrack.app.MediTrackApp
import com.meditrack.app.R
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.ProcessMissedDoseUseCase
import com.meditrack.app.feature.reminder.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MissedDoseWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val processMissedDoseUseCase: ProcessMissedDoseUseCase,
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val doseId = inputData.getString(KEY_DOSE_ID) ?: return Result.failure()
        Log.d(MediTrackApp.TAG, "MissedDoseWorker running for dose $doseId")

        return try {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Dismiss the active reminder notification if still present
            notificationManager.cancel(NotificationChannels.getNotificationIdForDose(doseId))

            val result = processMissedDoseUseCase(doseId)
            result.onSuccess { transitioned ->
                if (transitioned) {
                    val dose = doseRepository.getById(doseId)
                    val medicineName = if (dose != null) {
                        medicineRepository.getById(dose.medicineId)?.name ?: "Medication"
                    } else {
                        "Medication"
                    }
                    MissedDoseNotifier.notify(applicationContext, doseId, medicineName)
                }
            }.onFailure { error ->
                Log.w(MediTrackApp.TAG, "Failed to process missed dose $doseId: ${error.message}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(MediTrackApp.TAG, "MissedDoseWorker error for dose $doseId: ${e.message}", e)
            Result.failure()
        }
    }

    companion object {
        const val KEY_DOSE_ID = "key_dose_id"
    }
}
