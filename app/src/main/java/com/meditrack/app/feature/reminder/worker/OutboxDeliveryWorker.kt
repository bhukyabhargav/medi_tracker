package com.meditrack.app.feature.reminder.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.LinkStatus
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.OutboxRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.tasks.await
import com.meditrack.app.core.util.JsonPayload
import java.util.concurrent.TimeUnit

@HiltWorker
class OutboxDeliveryWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxRepository: OutboxRepository,
    private val caregiverRepository: CaregiverRepository,
    private val userPreferences: UserPreferences
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val undelivered = outboxRepository.getUndelivered()
        if (undelivered.isEmpty()) return Result.success()

        val isFirebaseAvailable = try {
            FirebaseApp.getApps(appContext).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        val patientUid = userPreferences.currentUserId.firstOrNull() ?: "offline_patient"

        // Lookup connected caregivers to route the notification event
        val connectedLinks = try {
            caregiverRepository.observeByPatientId(patientUid).firstOrNull() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val acceptedCaregivers = connectedLinks.filter { it.status == LinkStatus.ACCEPTED && !it.caregiverUid.isNullOrBlank() }

        var anyFailed = false

        for (event in undelivered) {
            val success = deliverEvent(event, patientUid, acceptedCaregivers.mapNotNull { it.caregiverUid }, isFirebaseAvailable)
            if (success) {
                outboxRepository.markDelivered(event.id)
            } else {
                anyFailed = true
            }
        }

        return if (anyFailed) Result.retry() else Result.success()
    }

    private suspend fun deliverEvent(
        event: NotificationOutbox,
        patientUid: String,
        acceptedCaregiverUids: List<String>,
        isFirebaseAvailable: Boolean
    ): Boolean {
        // Extract fields from payloadJson safely
        val medicineName = JsonPayload.getString(event.payloadJson, "name")
            ?: JsonPayload.getString(event.payloadJson, "medicineName")
        val doseId = JsonPayload.getString(event.payloadJson, "doseId")
        val specificCaregiverUid = JsonPayload.getString(event.payloadJson, "caregiverUid")

        if (!isFirebaseAvailable) {
            // In offline/test/fake mode without Firebase configuration:
            // Mark as delivered deterministically
            return true
        }

        // Determine destination caregiver UIDs (deliver to all connected caregivers or specified one)
        val targetCaregivers = if (!specificCaregiverUid.isNullOrBlank()) {
            listOf(specificCaregiverUid)
        } else if (acceptedCaregiverUids.isNotEmpty()) {
            acceptedCaregiverUids
        } else {
            // No caregivers connected yet. Mark delivered locally to prevent infinite poison loop
            return true
        }

        return try {
            val firestore = FirebaseFirestore.getInstance()

            for (targetCg in targetCaregivers) {
                val eventDocId = if (targetCaregivers.size == 1) event.id else "${event.id}_${targetCg}"
                val eventMap = hashMapOf<String, Any?>(
                    "id" to eventDocId,
                    "type" to event.type.name,
                    "patientUid" to patientUid,
                    "caregiverUid" to targetCg,
                    "medicineName" to medicineName,
                    "doseId" to doseId,
                    "createdAt" to event.createdAt,
                    "payloadJson" to event.payloadJson
                )

                try {
                    firestore.collection("notificationEvents").document(eventDocId).set(eventMap).await()
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                        // Document already exists, consider delivered
                    } else if ((e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
                        msg.contains("PERMISSION_DENIED")
                    ) {
                        val doc = try {
                            firestore.collection("notificationEvents").document(eventDocId).get().await()
                        } catch (_: Exception) { null }
                        if (doc != null && doc.exists()) {
                            // Already created previously
                        } else {
                            throw e
                        }
                    } else {
                        throw e
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
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

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<OutboxDeliveryWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME + "_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }
}
