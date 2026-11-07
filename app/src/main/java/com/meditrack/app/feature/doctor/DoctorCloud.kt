package com.meditrack.app.feature.doctor

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

object DoctorInviteCodeGenerator {
    // 8-character unguessable code, no 0/O/1/I to avoid transcription confusion
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 8

    fun generate(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)])
        }
        return sb.toString()
    }

    fun isValidFormat(code: String): Boolean {
        if (code.length != CODE_LENGTH) return false
        return code.all { it in CODE_ALPHABET }
    }
}

@Singleton
class DoctorCloud @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isAvailable: Boolean
        get() = try {
            FirebaseApp.getApps(context).isNotEmpty() && FirebaseAuth.getInstance().currentUser != null
        } catch (_: Exception) {
            false
        }

    /**
     * C1: Doctor creates invite -> writes to Firestore doctorInvitations with an unguessable 8-char code.
     */
    suspend fun createDoctorInvite(
        doctorUid: String,
        doctorName: String,
        doctorContact: String,
        patientContact: String? = null,
        now: Long = System.currentTimeMillis()
    ): Result<String> = try {
        val code = DoctorInviteCodeGenerator.generate()
        val doc = hashMapOf<String, Any?>(
            "code" to code,
            "doctorUid" to doctorUid,
            "doctorName" to doctorName,
            "doctorContact" to doctorContact,
            "patientContact" to patientContact,
            "status" to "PENDING",
            "used" to false,
            "createdAt" to now,
            "expiresAt" to now + (30L * 24 * 60 * 60 * 1000) // 30 days
        )
        FirebaseFirestore.getInstance().collection("doctorInvitations").document(code).set(doc).await()
        Result.success(code)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * C2: Patient redeems code via acceptDoctorInvite Cloud Function.
     */
    suspend fun redeemDoctorInvite(code: String): Result<DoctorLink> = try {
        val cleanCode = code.trim().uppercase()
        val result = FirebaseFunctions.getInstance()
            .getHttpsCallable("acceptDoctorInvite")
            .call(hashMapOf("code" to cleanCode))
            .await()
        val map = result.data as? Map<*, *> ?: emptyMap<Any, Any>()
        val linkId = map["linkId"]?.toString().orEmpty()
        val doctorUid = map["doctorUid"]?.toString().orEmpty()
        val doctorName = map["doctorName"]?.toString() ?: "Doctor"
        val patientId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        Result.success(
            DoctorLink(
                id = linkId,
                patientId = patientId,
                doctorUid = doctorUid,
                doctorName = doctorName,
                doctorContact = "",
                status = DoctorLinkStatus.ACCEPTED,
                invitationCode = cleanCode,
                createdAt = System.currentTimeMillis()
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * C2: Patient declines code via declineDoctorInvite Cloud Function.
     */
    suspend fun declineDoctorInvite(code: String): Result<Boolean> = try {
        val cleanCode = code.trim().uppercase()
        FirebaseFunctions.getInstance()
            .getHttpsCallable("declineDoctorInvite")
            .call(hashMapOf("code" to cleanCode))
            .await()
        Result.success(true)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * C3: Doctor creates proposal -> writes to Firestore collection medicineProposals/{id}.
     */
    suspend fun createProposal(proposal: MedicineProposal): Result<Unit> = try {
        val doc = hashMapOf<String, Any?>(
            "id" to proposal.id,
            "patientId" to proposal.patientId,
            "doctorUid" to proposal.doctorUid,
            "type" to proposal.type.name,
            "medicineId" to proposal.medicineId,
            "payloadJson" to proposal.payloadJson,
            "status" to proposal.status.name,
            "revision" to proposal.revision,
            "clarificationNote" to proposal.clarificationNote,
            "supersedesProposalId" to proposal.supersedesProposalId,
            "createdAt" to proposal.createdAt,
            "resolvedAt" to proposal.resolvedAt
        )
        FirebaseFirestore.getInstance().collection("medicineProposals").document(proposal.id).set(doc).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * C4: Patient views proposals -> fetches Firestore medicineProposals where patientId == patientId.
     */
    suspend fun fetchProposalsForPatient(patientId: String): List<MedicineProposal> = try {
        FirebaseFirestore.getInstance().collection("medicineProposals")
            .whereEqualTo("patientId", patientId)
            .get().await().documents.mapNotNull { d ->
                val typeStr = d.getString("type") ?: return@mapNotNull null
                val statusStr = d.getString("status") ?: ProposalStatus.PENDING.name
                val type = try { ProposalType.valueOf(typeStr) } catch (_: Exception) { return@mapNotNull null }
                val status = try { ProposalStatus.valueOf(statusStr) } catch (_: Exception) { ProposalStatus.PENDING }
                MedicineProposal(
                    id = d.id,
                    patientId = d.getString("patientId") ?: patientId,
                    doctorUid = d.getString("doctorUid") ?: "",
                    type = type,
                    medicineId = d.getString("medicineId"),
                    payloadJson = d.getString("payloadJson") ?: "{}",
                    status = status,
                    revision = d.getLong("revision")?.toInt() ?: 1,
                    clarificationNote = d.getString("clarificationNote"),
                    supersedesProposalId = d.getString("supersedesProposalId"),
                    createdAt = d.getLong("createdAt") ?: System.currentTimeMillis(),
                    resolvedAt = d.getLong("resolvedAt")
                )
            }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * C4: Update proposal status in Firestore.
     */
    suspend fun updateProposal(proposalId: String, updates: Map<String, Any?>): Result<Unit> = try {
        FirebaseFirestore.getInstance().collection("medicineProposals").document(proposalId).update(updates).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * C5: Doctor screen queries Firestore users/{patientId}/medicines and users/{patientId}/doseEvents (last 7 days).
     * Does NOT touch doctor's local Room.
     */
    suspend fun fetchPatientData(patientId: String, fromMillis: Long): Result<Pair<List<Medicine>, List<DoseEvent>>> = try {
        val patientRef = FirebaseFirestore.getInstance().collection("users").document(patientId)
        val medicines = patientRef.collection("medicines").get().await().documents.mapNotNull { d ->
            if (d.getBoolean("isDeleted") == true) return@mapNotNull null
            Medicine(
                id = d.getString("id") ?: d.id,
                patientId = patientId,
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
                prescribedByDoctorUid = d.getString("prescribedByDoctorUid"),
                prescribedByDoctorName = d.getString("prescribedByDoctorName"),
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
                    patientId = patientId,
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

    /**
     * C6: Revoke link -> updates Firestore doctorLinks/{id}.status = 'REVOKED'.
     */
    suspend fun revokeDoctorLink(linkId: String): Result<Unit> = try {
        val now = System.currentTimeMillis()
        FirebaseFirestore.getInstance().collection("doctorLinks").document(linkId).update(
            mapOf("status" to "REVOKED", "updatedAt" to now)
        ).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default
}
