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
import com.google.firebase.auth.FirebaseAuth
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
import org.json.JSONObject
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

        // Demo accounts never sign in to Firebase and all live on this one device, so their alerts are
        // shown as local notifications here instead of waiting forever for a cloud sign-in.
        val localUserId = try {
            userPreferences.currentUserId.firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (localUserId != null && localUserId.startsWith("demo_")) {
            for (event in undelivered) {
                postLocalDemoNotification(event)
                outboxRepository.markDelivered(event.id)
            }
            return Result.success()
        }

        val isFirebaseAvailable = try {
            FirebaseApp.getApps(appContext).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        // Part C2: When Firebase is unavailable, retain pending events for future delivery
        if (!isFirebaseAvailable) {
            return Result.retry()
        }

        // Firestore rules compare patientUid with request.auth.uid, so use the Firebase uid.
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (authUid == null) {
            // Not signed in to Firebase: retain pending events until authenticated
            return Result.retry()
        }
        val patientUid = authUid

        // Lookup connected caregivers to route the notification event
        val connectedLinks = try {
            caregiverRepository.observeByPatientId(patientUid).firstOrNull() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        // Strictly filter to ACCEPTED caregivers only; REVOKED or PENDING caregivers receive NO notifications
        val acceptedCaregivers = connectedLinks.filter { it.status == LinkStatus.ACCEPTED && !it.caregiverUid.isNullOrBlank() }

        var anyFailed = false

        for (event in undelivered) {
            val success = if (isDoctorEvent(event)) {
                // Doctor/prescription events go between patient and doctor; they never depend on a caregiver.
                deliverDoctorEvent(event, authUid)
            } else if (acceptedCaregivers.isEmpty()) {
                // No eligible recipient connected: retain the event until a caregiver connects, do not claim delivery
                false
            } else {
                deliverEvent(event, patientUid, acceptedCaregivers.mapNotNull { it.caregiverUid })
            }
            if (success) {
                outboxRepository.markDelivered(event.id)
            } else {
                anyFailed = true
            }
        }

        return if (anyFailed) Result.retry() else Result.success()
    }

    private fun isDoctorEvent(event: NotificationOutbox): Boolean =
        event.type.name.startsWith("DOCTOR_INVITE_") || event.type.name.startsWith("PRESCRIPTION_")

    /**
     * Writes a doctor/prescription event with top-level patientId and doctorUid, which the Cloud Function
     * needs to check the patient-doctor link and pick the recipient.
     * The sender is the signed-in user: a payload carrying another person's patientId was written by the doctor,
     * a payload carrying doctorUid was written by the patient.
     */
    private suspend fun deliverDoctorEvent(event: NotificationOutbox, authUid: String): Boolean {
        var payloadPatientId: String? = null
        var payloadDoctorUid: String? = null
        var medicineName: String? = null
        var doctorName: String? = null
        var patientName: String? = null
        try {
            val json = JSONObject(event.payloadJson)
            if (json.has("patientId") && !json.isNull("patientId")) payloadPatientId = json.getString("patientId")
            if (json.has("doctorUid") && !json.isNull("doctorUid")) payloadDoctorUid = json.getString("doctorUid")
            if (json.has("medicineName") && !json.isNull("medicineName")) medicineName = json.getString("medicineName")
            if (json.has("doctorName") && !json.isNull("doctorName")) doctorName = json.getString("doctorName")
            if (json.has("patientName") && !json.isNull("patientName")) patientName = json.getString("patientName")
        } catch (_: Exception) {}

        val patientId: String
        val doctorUid: String
        if (!payloadDoctorUid.isNullOrBlank() && payloadDoctorUid != authUid) {
            patientId = authUid
            doctorUid = payloadDoctorUid
        } else if (!payloadPatientId.isNullOrBlank() && payloadPatientId != authUid) {
            patientId = payloadPatientId
            doctorUid = authUid
        } else {
            // Neither side can be identified, so this event can never be routed. Drop it instead of retrying forever.
            return true
        }

        return try {
            val eventMap = hashMapOf<String, Any?>(
                "id" to event.id,
                "type" to event.type.name,
                "patientId" to patientId,
                "patientUid" to patientId,
                "doctorUid" to doctorUid,
                "medicineName" to medicineName,
                "doctorName" to doctorName,
                "patientName" to patientName,
                "createdAt" to event.createdAt,
                "payloadJson" to event.payloadJson
            )
            val firestore = FirebaseFirestore.getInstance()
            try {
                firestore.collection("notificationEvents").document(event.id).set(eventMap).await()
            } catch (e: Exception) {
                // Same rule as caregiver events: an already-written immutable event counts as delivered.
                val rejected = e is FirebaseFirestoreException &&
                    (e.code == FirebaseFirestoreException.Code.ALREADY_EXISTS ||
                        e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                if (!rejected) throw e
                val exists = try {
                    firestore.collection("notificationEvents").document(event.id).get().await().exists()
                } catch (_: Exception) {
                    false
                }
                if (!exists) throw e
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Shows a demo account's alert on this device (demo patient, doctor and caregiver share one phone). */
    private fun postLocalDemoNotification(event: NotificationOutbox) {
        try {
            var medicineName: String? = null
            var patientName: String? = null
            var doctorName: String? = null
            try {
                val json = JSONObject(event.payloadJson)
                if (json.has("medicineName") && !json.isNull("medicineName")) medicineName = json.getString("medicineName")
                if (json.has("patientName") && !json.isNull("patientName")) patientName = json.getString("patientName")
                if (json.has("doctorName") && !json.isNull("doctorName")) doctorName = json.getString("doctorName")
            } catch (_: Exception) {}

            val (title, body) = com.meditrack.app.domain.model.NotificationText.buildText(
                type = event.type,
                medicineName = medicineName,
                patientName = patientName ?: "Demo Patient",
                doctorName = doctorName
            )
            val audience = if (isDoctorEvent(event)) "Doctor/patient alert (demo)" else "Caregiver alert (demo)"
            val urgent = event.type == com.meditrack.app.domain.model.NotifType.MISSED_DOSE ||
                event.type == com.meditrack.app.domain.model.NotifType.DOSE_SKIPPED
            val channel = if (urgent) {
                com.meditrack.app.feature.reminder.NotificationChannels.CHANNEL_MISSED_DOSES
            } else {
                com.meditrack.app.feature.reminder.NotificationChannels.CHANNEL_GENERAL
            }

            com.meditrack.app.feature.reminder.NotificationChannels.createChannels(appContext)
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                ?: return
            val notification = androidx.core.app.NotificationCompat.Builder(appContext, channel)
                .setSmallIcon(com.meditrack.app.R.mipmap.ic_launcher)
                .setContentTitle("$audience: $title")
                .setContentText(body)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            manager.notify(event.id.hashCode(), notification)
        } catch (_: Throwable) {
            // A demo alert that cannot be shown (for example notifications are switched off) must not block the outbox.
        }
    }

    private suspend fun deliverEvent(
        event: NotificationOutbox,
        patientUid: String,
        acceptedCaregiverUids: List<String>
    ): Boolean {
        // Extract fields from payloadJson safely
        var medicineName: String? = null
        var doseId: String? = null
        var specificCaregiverUid: String? = null

        try {
            val json = JSONObject(event.payloadJson)
            if (json.has("name")) medicineName = json.getString("name")
            if (json.has("medicineName")) medicineName = json.getString("medicineName")
            if (json.has("doseId")) doseId = json.getString("doseId")
            if (json.has("caregiverUid")) specificCaregiverUid = json.getString("caregiverUid")
        } catch (_: Exception) {}

        // Determine destination caregiver UIDs (deliver only to accepted caregivers)
        val targetCaregivers = if (!specificCaregiverUid.isNullOrBlank()) {
            if (specificCaregiverUid in acceptedCaregiverUids) listOf(specificCaregiverUid) else emptyList()
        } else {
            acceptedCaregiverUids
        }

        if (targetCaregivers.isEmpty()) {
            return false
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
                    // Events are immutable (rules deny update), so re-sending an event that was already
                    // written (crash before markDelivered) is rejected. That counts as delivered ONLY if
                    // the document really exists; any other permission failure must stay a failure.
                    val rejected = e is FirebaseFirestoreException &&
                        (e.code == FirebaseFirestoreException.Code.ALREADY_EXISTS ||
                            e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                    if (!rejected) throw e
                    val exists = try {
                        firestore.collection("notificationEvents").document(eventDocId).get().await().exists()
                    } catch (_: Exception) {
                        false
                    }
                    if (!exists) throw e
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

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME + "_once",
                androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
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
