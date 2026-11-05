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
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class DailyDoseExtensionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val alarmScheduler: AlarmScheduler
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val horizon = now + (7 * 24 * 60 * 60 * 1000L) // Rolling 7 days ahead
            val activeMedicines = medicineRepository.observeActive().first()

            for (medicine in activeMedicines) {
                if (medicine.stoppedByDoctor) continue

                // Fast query for the latest scheduled timestamp instead of loading all doses
                val latestScheduled = doseRepository.getLatestScheduledAt(medicine.id)
                val fromMillis = if (latestScheduled != null) {
                    maxOf(now, latestScheduled + 1000L)
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
                        val rawDoses = DoseScheduler.generateDoses(
                            medicine = medicine,
                            fromEpochMillis = fromMillis,
                            toEpochMillis = boundedHorizon
                        )
                        val newDoses = rawDoses.map { it.copy(patientId = medicine.patientId) }
                        if (newDoses.isNotEmpty()) {
                            doseRepository.insertAll(newDoses)
                            val maxAlarmHorizon = now + (48 * 60 * 60 * 1000L)
                            for (dose in newDoses) {
                                if (medicine.reminderEnabled && dose.scheduledAt <= maxAlarmHorizon) {
                                    alarmScheduler.scheduleDoseAlarm(dose)
                                } else {
                                    alarmScheduler.scheduleMissedCheck(dose)
                                }
                            }
                            Log.d(MediTrackApp.TAG, "Extended and scheduled ${newDoses.size} doses for medicine ${medicine.name}")
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
