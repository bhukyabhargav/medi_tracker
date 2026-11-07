package com.meditrack.app.feature.caregiver

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

data class CloudInvite(val code: String, val label: String, val relationship: String, val expiresAt: Long)

data class CloudLinkedPatient(val linkId: String, val patientUid: String, val patientName: String)

/**
 * Firebase access for caregiver mode (invitation codes, linked patients, read-only patient data).
 * Links are created ONLY by the acceptCaregiverInvite Cloud Function; the device never writes a link.
 */
@Singleton
class CaregiverCloud @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isAvailable: Boolean
        get() = try {
            FirebaseApp.getApps(context).isNotEmpty() && FirebaseAuth.getInstance().currentUser != null
        } catch (_: Exception) {
            false
        }

    suspend fun createInvite(
        patientUid: String,
        patientName: String,
        label: String,
        relationship: String,
        now: Long = System.currentTimeMillis()
    ): Result<String> = try {
        val code = generateCode()
        val doc = hashMapOf<String, Any?>(
            "patientUid" to patientUid,
            "patientName" to patientName,
            "label" to label,
            "relationship" to relationship,
            "status" to "PENDING",
            "createdAt" to now,
            "expiresAt" to now + INVITE_VALID_MILLIS
        )
        FirebaseFirestore.getInstance().collection("caregiverInvites").document(code).set(doc).await()
        Result.success(code)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun listPendingInvites(patientUid: String, now: Long = System.currentTimeMillis()): List<CloudInvite> = try {
        FirebaseFirestore.getInstance().collection("caregiverInvites")
            .whereEqualTo("patientUid", patientUid)
            .whereEqualTo("status", "PENDING")
            .get().await().documents.mapNotNull { d ->
                val expires = d.getLong("expiresAt") ?: return@mapNotNull null
                if (expires <= now) return@mapNotNull null
                CloudInvite(d.id, d.getString("label") ?: "Caregiver", d.getString("relationship") ?: "", expires)
            }
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun redeemInvite(code: String, caregiverName: String): Result<CloudLinkedPatient> = try {
        val result = FirebaseFunctions.getInstance()
            .getHttpsCallable("acceptCaregiverInvite")
            .call(hashMapOf("code" to code.trim().uppercase(), "caregiverName" to caregiverName))
            .await()
        val map = result.data as? Map<*, *> ?: emptyMap<Any, Any>()
        Result.success(
            CloudLinkedPatient(
                linkId = map["linkId"]?.toString().orEmpty(),
                patientUid = map["patientUid"]?.toString().orEmpty(),
                patientName = map["patientName"]?.toString() ?: "Patient"
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun listLinkedPatients(caregiverUid: String): List<CloudLinkedPatient> = try {
        val db = FirebaseFirestore.getInstance()
        db.collection("caregiverLinks")
            .whereEqualTo("caregiverUid", caregiverUid)
            .whereEqualTo("status", "ACCEPTED")
            .get().await().documents.mapNotNull { d ->
                val patientUid = d.getString("patientId") ?: return@mapNotNull null
                val name = try {
                    db.collection("users").document(patientUid).get().await().getString("name")
                } catch (_: Exception) {
                    null
                }
                CloudLinkedPatient(d.id, patientUid, name ?: "Patient")
            }
    } catch (_: Exception) {
        emptyList()
    }

    /** Read-only snapshot of a linked patient's medicines and doses since [fromMillis]. */
    suspend fun fetchPatientData(patientUid: String, fromMillis: Long): Result<Pair<List<Medicine>, List<DoseEvent>>> = try {
        val patientRef = FirebaseFirestore.getInstance().collection("users").document(patientUid)
        val medicines = patientRef.collection("medicines").get().await().documents.mapNotNull { d ->
            if (d.getBoolean("isDeleted") == true) return@mapNotNull null
            Medicine(
                id = d.getString("id") ?: d.id,
                patientId = patientUid,
                name = d.getString("name") ?: return@mapNotNull null,
                dosageText = d.getString("dosageText") ?: "",
                pillsPerDose = d.getLong("pillsPerDose")?.toInt() ?: 1,
                frequencyType = enumOrDefault(d.getString("frequencyType"), FrequencyType.DAILY),
                intervalHours = d.getLong("intervalHours")?.toInt(),
                timesOfDay = (d.get("timesOfDay") as? List<*>)?.mapNotNull {
                    try { LocalTime.parse(it.toString()) } catch (_: Exception) { null }
                } ?: emptyList(),
                daysOfWeek = (d.get("daysOfWeek") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() },
                startDate = d.getLong("startDate") ?: 0L,
                endDate = d.getLong("endDate"),
                foodRelation = enumOrDefault(d.getString("foodRelation"), FoodRelation.ANY),
                instructions = d.getString("instructions") ?: "",
                initialStock = d.getLong("initialStock")?.toInt() ?: 0,
                currentStock = d.getLong("currentStock")?.toInt() ?: 0,
                stoppedByDoctor = d.getBoolean("stoppedByDoctor") ?: false
            )
        }
        val doses = patientRef.collection("doseEvents")
            .whereGreaterThanOrEqualTo("scheduledAt", fromMillis)
            .get().await().documents.mapNotNull { d ->
                val scheduledAt = d.getLong("scheduledAt") ?: return@mapNotNull null
                DoseEvent(
                    id = d.getString("id") ?: d.id,
                    medicineId = d.getString("medicineId") ?: return@mapNotNull null,
                    patientId = patientUid,
                    scheduledAt = scheduledAt,
                    windowEndsAt = d.getLong("windowEndsAt") ?: (scheduledAt + 3_600_000L),
                    status = enumOrDefault(d.getString("status"), DoseStatus.PENDING),
                    takenAt = d.getLong("takenAt"),
                    emptyCellsAfter = d.getLong("emptyCellsAfter")?.toInt(),
                    lateEntry = d.getBoolean("lateEntry") ?: false,
                    manualUnverified = d.getBoolean("manualUnverified") ?: false,
                    updatedAt = d.getLong("updatedAt") ?: scheduledAt
                )
            }
        Result.success(medicines to doses.sortedBy { it.scheduledAt })
    } catch (e: Exception) {
        Result.failure(e)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default

    private fun generateCode(): String {
        val random = SecureRandom()
        return buildString {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        }
    }

    companion object {
        // No 0/O/1/I to avoid transcription mistakes. Matches the function's /^[A-Z0-9]{8,12}$/ check.
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 10
        const val INVITE_VALID_MILLIS = 72L * 60 * 60 * 1000
    }
}
