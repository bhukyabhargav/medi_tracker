package com.meditrack.app.domain.usecase

import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.OutboxRepository
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoctorLinkUseCase @Inject constructor(
    private val doctorRepository: DoctorRepository,
    private val outboxRepository: OutboxRepository
) {
    private val random = SecureRandom()
    private val codeChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Excludes similar chars O, 0, 1, I

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
        return Result.success(link)
    }

    suspend fun redeemDoctorInvitation(
        patientId: String,
        inviteCode: String,
        patientName: String = "Patient"
    ): Result<DoctorLink> {
        val trimmedCode = inviteCode.trim().uppercase()
        val link = doctorRepository.getDoctorLinkByInviteCode(trimmedCode)
            ?: return Result.failure(IllegalArgumentException("Invalid doctor invitation code."))

        val now = System.currentTimeMillis()
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
