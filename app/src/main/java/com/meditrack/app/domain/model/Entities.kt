package com.meditrack.app.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalTime

@Entity(tableName = "users")
data class User(
    @PrimaryKey val id: String,
    val name: String,
    val email: String? = null,
    val phone: String? = null,
    val role: UserRole = UserRole.PATIENT,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "medicines",
    indices = [Index("patientId")]
)
data class Medicine(
    @PrimaryKey val id: String,
    val patientId: String = "",
    val name: String,
    val dosageText: String,
    val pillsPerDose: Int,
    val frequencyType: FrequencyType,
    val timesOfDay: List<LocalTime>,
    val intervalHours: Int? = null,
    val daysOfWeek: List<Int>? = null, // 1 = Monday .. 7 = Sunday
    val startDate: Long,
    val endDate: Long? = null,
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val instructions: String = "",
    val reminderEnabled: Boolean = true,
    val initialStock: Int,
    val currentStock: Int,
    val baselineEmptyCells: Int = 0,
    val baselinePhotoPath: String? = null,
    val lastRefillAt: Long? = null,
    val prescribedByDoctorUid: String? = null,
    val prescribedByDoctorName: String? = null,
    val stoppedByDoctor: Boolean = false,
    val isDeleted: Boolean = false,
    val lowStockNotified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

@Entity(
    tableName = "dose_events",
    foreignKeys = [
        ForeignKey(
            entity = Medicine::class,
            parentColumns = ["id"],
            childColumns = ["medicineId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("patientId"),
        Index("medicineId"),
        Index("scheduledAt"),
        Index("status")
    ]
)
data class DoseEvent(
    @PrimaryKey val id: String,
    val patientId: String = "",
    val medicineId: String,
    val scheduledAt: Long,
    val windowEndsAt: Long,
    val status: DoseStatus = DoseStatus.PENDING,
    val takenAt: Long? = null,
    val verifiedPhotoPath: String? = null,
    val verificationAttempts: Int = 0,
    val emptyCellsAfter: Int? = null,
    val snoozeCount: Int = 0,
    val lateEntry: Boolean = false,
    val manualUnverified: Boolean = false,
    val nextReminderAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "verification_attempts",
    foreignKeys = [
        ForeignKey(
            entity = DoseEvent::class,
            parentColumns = ["id"],
            childColumns = ["doseEventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("doseEventId")]
)
data class VerificationAttempt(
    @PrimaryKey val id: String,
    val doseEventId: String,
    val photoPath: String,
    val result: VerificationResultType,
    val rejectReason: RejectReason? = null,
    val perceptualHash: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "caregiver_links",
    indices = [
        Index("patientId"),
        Index("caregiverUid")
    ]
)
data class CaregiverLink(
    @PrimaryKey val id: String,
    val patientId: String,
    val caregiverName: String,
    val caregiverContact: String,
    val relationship: String,
    val status: LinkStatus = LinkStatus.PENDING,
    val caregiverUid: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "doctor_links",
    indices = [
        Index("patientId"),
        Index("doctorUid")
    ]
)
data class DoctorLink(
    @PrimaryKey val id: String,
    val patientId: String,
    val doctorUid: String? = null,
    val doctorName: String,
    val doctorContact: String,
    val status: DoctorLinkStatus = DoctorLinkStatus.PENDING,
    val invitationCode: String? = null,
    val expiresAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

@Entity(
    tableName = "medicine_proposals",
    indices = [Index("patientId"), Index("doctorUid")]
)
data class MedicineProposal(
    @PrimaryKey val id: String,
    val patientId: String,
    val doctorUid: String,
    val type: ProposalType,
    val medicineId: String? = null,
    val payloadJson: String,
    val status: ProposalStatus = ProposalStatus.PENDING,
    val revision: Int = 1,
    val clarificationNote: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null
)

@Entity(
    tableName = "notification_outbox",
    indices = [Index("delivered")]
)
data class NotificationOutbox(
    @PrimaryKey val id: String,
    val type: NotifType,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val delivered: Boolean = false
)
