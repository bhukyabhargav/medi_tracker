package com.meditrack.app.feature.reminder.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.OutboxRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.TimeUnit

@HiltWorker
class OutboxDeliveryWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxRepository: OutboxRepository,
    private val caregiverRepository: CaregiverRepository,
    private val userPreferences: UserPreferences,
    private val deliveryGateway: NotificationDeliveryGateway
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val undelivered = outboxRepository.getUndelivered()
        if (undelivered.isEmpty()) return Result.success()

        val patientUid = userPreferences.currentUserId.firstOrNull() ?: "offline_patient"

        // Lookup connected caregivers to route the notification event
        val connectedLinks = try {
            caregiverRepository.observeByPatientId(patientUid).firstOrNull() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val defaultCaregiverUid = connectedLinks.firstOrNull { it.status.name == "ACCEPTED" }?.caregiverUid

        for (event in undelivered) {
            val success = deliveryGateway.deliver(event, patientUid, defaultCaregiverUid)
            if (success) {
                outboxRepository.markDelivered(event.id)
            } else {
                // Do not mark delivered before successful Firestore delivery!
                // Preserve pending events during offline operation and retry.
                return Result.retry()
            }
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "OutboxDeliveryWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OutboxDeliveryWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
