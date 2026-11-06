package com.meditrack.app.domain.usecase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.OutboxRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoctorLinkUseCase @Inject constructor(
    private val doctorRepository: DoctorRepository,
    private val outboxRepository: OutboxRepository,
    @ApplicationContext private val context: Context? = null
) {
    private val random = SecureRandom()
    private val codeChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Excludes similar chars O, 0, 1, I

    private val isFirebaseAvailable: Boolean
        get() = try {
            if (context != null) FirebaseApp.getApps(context).isNotEmpty() else false
        } catch (_: Throwable) { false }

    suspend fun createDoctorInvitation(
        doctorUid: String,
        doctorName: String,
        doctorContact: String,
        validityDays: Long = 7L
    ): Result<DoctorLink> {
        val codeBuilder = StringBuilder()
        repeat(6) {
            codeBuilder.append(codeChars[random.nextInt(codeChars.length)])
        }
        val code = codeBuilder.toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + (validityDays * 24 * 60 * 60 * 1000L)

        val linkId = "dlink_${UUID.randomUUID()}"
        val link = DoctorLink(
            id = linkId,
            patientId = "",
            doctorUid = doctorUid,
            doctorName = doctorName,
            doctorContact = doctorContact,
            status = DoctorLinkStatus.PENDING,
            invitationCode = code,
            expiresAt = expiresAt,
            createdAt = now,
            updatedAt = now
        )

        doctorRepository.insertDoctorLink(link)

        if (isFirebaseAvailable) {
            try {
                val db = FirebaseFirestore.getInstance()
                val inviteMap = hashMapOf<String, Any?>(
                    "id" to linkId,
                    "doctorUid" to doctorUid,
                    "doctorName" to doctorName,
                    "doctorContact" to doctorContact,
                    "inviteCode" to code,
                    "status" to "PENDING",
                    "expiresAt" to expiresAt,
                    "createdAt" to now,
                    "updatedAt" to now
                )
                db.collection("doctorInvitations").document(linkId).set(inviteMap).await()
            } catch (_: Exception) {}
        }

        return Result.success(link)
    }

    suspend fun redeemDoctorInvitation(
        patientId: String,
        inviteCode: String,
        patientName: String = "Patient"
    ): Result<DoctorLink> {
        val trimmedCode = inviteCode.trim().uppercase()
        val now = System.currentTimeMillis()

        if (isFirebaseAvailable) {
            try {
                val functions = FirebaseFunctions.getInstance()
                val callResult = functions.getHttpsCallable("acceptDoctorInvite")
                    .call(mapOf("code" to trimmedCode)).await()
                val resMap = callResult.data as? Map<*, *>
                val linkId = resMap?.get("linkId") as? String ?: "${patientId}_${resMap?.get("doctorUid")}"
                val db = FirebaseFirestore.getInstance()
                val linkDoc = db.collection("doctorLinks").document(linkId).get().await()
                if (linkDoc.exists()) {
                    val docUid = linkDoc.getString("doctorUid") ?: (resMap?.get("doctorUid") as? String) ?: ""
                    val docName = linkDoc.getString("doctorName") ?: "Doctor"
                    val docContact = linkDoc.getString("doctorContact") ?: ""

                    val acceptedLink = DoctorLink(
                        id = linkId,
                        patientId = patientId,
                        doctorUid = docUid,
                        doctorName = docName,
                        doctorContact = docContact,
                        status = DoctorLinkStatus.ACCEPTED,
                        invitationCode = trimmedCode,
                        createdAt = linkDoc.getLong("createdAt") ?: now,
                        updatedAt = linkDoc.getLong("updatedAt") ?: now
                    )
                    doctorRepository.insertDoctorLink(acceptedLink)

                    val outboxPayload = JsonPayload.build(
                        "type" to NotifType.DOCTOR_INVITE_ACCEPTED.name,
                        "patientUid" to patientId,
                        "patientName" to patientName,
                        "doctorUid" to docUid,
                        "doctorName" to docName,
                        "createdAt" to now
                    )
                    outboxRepository.enqueue(
                        NotificationOutbox(
                            id = "out_${UUID.randomUUID()}",
                            type = NotifType.DOCTOR_INVITE_ACCEPTED,
                            payloadJson = outboxPayload,
                            createdAt = now
                        )
                    )
                    return Result.success(acceptedLink)
                }
            } catch (e: Exception) {
                // If remote failed and local link not found, return failure
                if (doctorRepository.getDoctorLinkByInviteCode(trimmedCode) == null) {
                    return Result.failure(e)
                }
            }
        }

        // Local simulation / fallback
        val link = doctorRepository.getDoctorLinkByInviteCode(trimmedCode)
            ?: return Result.failure(IllegalArgumentException("Invalid doctor invitation code."))

        if (link.expiresAt != null && link.expiresAt < now) {
            return Result.failure(IllegalStateException("Doctor invitation code has expired."))
        }

        if (link.status == DoctorLinkStatus.ACCEPTED && link.patientId.isNotBlank() && link.patientId != patientId) {
            return Result.failure(IllegalStateException("Invitation code has already been redeemed."))
        }

        val updatedLink = link.copy(
            patientId = patientId,
            status = DoctorLinkStatus.ACCEPTED,
            updatedAt = now
        )
        doctorRepository.updateDoctorLink(updatedLink)

        val outboxPayload = JsonPayload.build(
            "type" to NotifType.DOCTOR_INVITE_ACCEPTED.name,
            "patientUid" to patientId,
            "patientName" to patientName,
            "doctorUid" to (link.doctorUid ?: ""),
            "doctorName" to link.doctorName,
            "createdAt" to now
        )

        outboxRepository.enqueue(
            NotificationOutbox(
                id = "out_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_ACCEPTED,
                payloadJson = outboxPayload,
                createdAt = now
            )
        )

        return Result.success(updatedLink)
    }

    suspend fun declineDoctorInvitation(
        linkId: String,
        patientId: String,
        patientName: String = "Patient"
    ): Result<DoctorLink> {
        val link = doctorRepository.getDoctorLinkById(linkId)
            ?: return Result.failure(IllegalArgumentException("Doctor link not found."))

        val now = System.currentTimeMillis()
        val updatedLink = link.copy(
            status = DoctorLinkStatus.DECLINED,
            updatedAt = now
        )
        doctorRepository.updateDoctorLink(updatedLink)

        val outboxPayload = JsonPayload.build(
            "type" to NotifType.DOCTOR_INVITE_DECLINED.name,
            "patientUid" to patientId,
            "patientName" to patientName,
            "doctorUid" to (link.doctorUid ?: ""),
            "doctorName" to link.doctorName,
            "createdAt" to now
        )

        outboxRepository.enqueue(
            NotificationOutbox(
                id = "out_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_DECLINED,
                payloadJson = outboxPayload,
                createdAt = now
            )
        )

        return Result.success(updatedLink)
    }

    suspend fun revokeDoctorAccess(
        patientId: String,
        doctorUid: String
    ): Result<DoctorLink> {
        val link = doctorRepository.getDoctorLinkByDoctorAndPatient(doctorUid, patientId)
            ?: return Result.failure(IllegalArgumentException("Active doctor connection not found."))

        val now = System.currentTimeMillis()
        val revokedLink = link.copy(
            status = DoctorLinkStatus.REVOKED,
            updatedAt = now
        )
        doctorRepository.updateDoctorLink(revokedLink)

        if (isFirebaseAvailable) {
            try {
                val db = FirebaseFirestore.getInstance()
                db.collection("doctorLinks").document("${patientId}_${doctorUid}")
                    .update(mapOf("status" to "REVOKED", "updatedAt" to now)).await()
            } catch (_: Exception) {}
        }

        val outboxPayload = JsonPayload.build(
            "type" to NotifType.DOCTOR_INVITE_REVOKED.name,
            "patientUid" to patientId,
            "doctorUid" to doctorUid,
            "doctorName" to link.doctorName,
            "createdAt" to now
        )

        outboxRepository.enqueue(
            NotificationOutbox(
                id = "out_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_REVOKED,
                payloadJson = outboxPayload,
                createdAt = now
            )
        )

        return Result.success(revokedLink)
    }
}
