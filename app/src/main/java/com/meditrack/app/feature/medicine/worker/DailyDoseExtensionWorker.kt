package com.meditrack.app.feature.medicine.worker

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
import com.meditrack.app.domain.usecase.DoseScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class DailyDoseExtensionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val horizon = now + (7 * 24 * 60 * 60 * 1000L) // Rolling 7 days ahead
            val activeMedicines = medicineRepository.observeActive().first()

            for (medicine in activeMedicines) {
                if (medicine.stoppedByDoctor) continue

                val existingDoses = doseRepository.observeByMedicineId(medicine.id).first()
                val latestScheduled = existingDoses.maxByOrNull { it.scheduledAt }?.scheduledAt
                val fromMillis = if (latestScheduled != null) {
                    latestScheduled + 1000L
                } else {
                    maxOf(now, medicine.startDate)
                }

                if (fromMillis < horizon) {
                    val boundedHorizon = if (medicine.endDate != null) {
                        minOf(horizon, medicine.endDate)
                    } else {
                        horizon
                    }

                    if (fromMillis <= boundedHorizon) {
                        val newDoses = DoseScheduler.generateDoses(
                            medicine = medicine,
                            fromEpochMillis = fromMillis,
                            toEpochMillis = boundedHorizon
                        )
                        if (newDoses.isNotEmpty()) {
                            doseRepository.insertAll(newDoses)
                            Log.d(MediTrackApp.TAG, "Extended ${newDoses.size} doses for medicine ${medicine.name}")
                        }
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(MediTrackApp.TAG, "DailyDoseExtensionWorker encountered error: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "DailyDoseExtensionWork"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyDoseExtensionWorker>(
                1, TimeUnit.DAYS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
