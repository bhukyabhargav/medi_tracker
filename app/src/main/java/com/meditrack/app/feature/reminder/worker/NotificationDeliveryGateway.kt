package com.meditrack.app.feature.reminder.worker

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.meditrack.app.domain.model.NotificationOutbox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

interface NotificationDeliveryGateway {
    suspend fun deliver(
        event: NotificationOutbox,
        patientUid: String,
        caregiverUid: String?,
        doctorUid: String? = null
    ): Boolean
}

@Singleton
class FirebaseNotificationDeliveryGateway @Inject constructor(
    @ApplicationContext private val context: Context
) : NotificationDeliveryGateway {

    override suspend fun deliver(
        event: NotificationOutbox,
        patientUid: String,
        caregiverUid: String?,
        doctorUid: String?
    ): Boolean {
        val isFirebaseAvailable = try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        if (!isFirebaseAvailable) {
            // Cannot deliver remotely if Firebase is not configured or available.
            // Preserves pending events in Room outbox.
            return false
        }

        return try {
            var medicineName: String? = null
            var doseId: String? = null
            var resolvedCaregiverUid = caregiverUid
            var resolvedDoctorUid = doctorUid

            try {
                val json = JSONObject(event.payloadJson)
                if (json.has("name")) medicineName = json.getString("name")
                if (json.has("medicineName")) medicineName = json.getString("medicineName")
                if (json.has("doseId")) doseId = json.getString("doseId")
                if (json.has("caregiverUid")) resolvedCaregiverUid = json.getString("caregiverUid")
                if (json.has("doctorUid")) resolvedDoctorUid = json.getString("doctorUid")
            } catch (_: Exception) {}

            val firestore = FirebaseFirestore.getInstance()
            val eventMap = hashMapOf<String, Any?>(
                "id" to event.id,
                "type" to event.type.name,
                "patientUid" to patientUid,
                "caregiverUid" to resolvedCaregiverUid,
                "doctorUid" to resolvedDoctorUid,
                "medicineName" to medicineName,
                "doseId" to doseId,
                "createdAt" to event.createdAt,
                "payloadJson" to event.payloadJson
            )
            firestore.collection("notificationEvents").document(event.id).set(eventMap).await()
            true
        } catch (_: Exception) {
            false
        }
    }
}
