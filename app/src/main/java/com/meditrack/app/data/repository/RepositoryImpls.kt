package com.meditrack.app.data.repository

import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.db.dao.NotificationOutboxDao
import com.meditrack.app.data.db.dao.VerificationAttemptDao
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.LinkStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.VerificationAttempt
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.VerificationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicineRepositoryImpl @Inject constructor(
    private val medicineDao: MedicineDao
) : MedicineRepository {
    override fun observeAll(): Flow<List<Medicine>> = medicineDao.observeAll()
    override fun observeActive(): Flow<List<Medicine>> = medicineDao.observeActive()
    override fun observeById(id: String): Flow<Medicine?> = medicineDao.observeById(id)
    override suspend fun getById(id: String): Medicine? = medicineDao.getById(id)
    override suspend fun insert(medicine: Medicine) = medicineDao.insert(medicine)
    override suspend fun update(medicine: Medicine) = medicineDao.update(medicine)
    override suspend fun delete(medicine: Medicine) = medicineDao.delete(medicine)
    override suspend fun updateStock(medicineId: String, newStock: Int) =
        medicineDao.updateStock(medicineId, newStock)
    override suspend fun decrementStockAtomic(medicineId: String, count: Int, now: Long): Int =
        medicineDao.decrementStockAtomic(medicineId, count, now)
    override suspend fun deleteAllByPatientId(patientId: String) =
        medicineDao.deleteAllByPatientId(patientId)
}

@Singleton
class DoseRepositoryImpl @Inject constructor(
    private val doseEventDao: DoseEventDao
) : DoseRepository {
    override fun observeBetween(startTime: Long, endTime: Long): Flow<List<DoseEvent>> =
        doseEventDao.observeBetween(startTime, endTime)

    override fun observeByMedicineId(medicineId: String): Flow<List<DoseEvent>> =
        doseEventDao.observeByMedicineId(medicineId)

    override fun observeById(id: String): Flow<DoseEvent?> = doseEventDao.observeById(id)
    override suspend fun getById(id: String): DoseEvent? = doseEventDao.getById(id)
    override suspend fun insert(doseEvent: DoseEvent) = doseEventDao.insert(doseEvent)
    override suspend fun insertAll(doseEvents: List<DoseEvent>) = doseEventDao.insertAll(doseEvents)
    override suspend fun update(doseEvent: DoseEvent) = doseEventDao.update(doseEvent)
    override suspend fun getPendingDueDoses(now: Long): List<DoseEvent> =
        doseEventDao.getPendingDueDoses(now)

    override suspend fun getFuturePendingDoses(now: Long): List<DoseEvent> =
        doseEventDao.getFuturePendingDoses(now)

    override suspend fun getOverduePendingDoses(now: Long): List<DoseEvent> =
        doseEventDao.getOverduePendingDoses(now)

    override suspend fun getLatestVerifiedDose(medicineId: String): DoseEvent? =
        doseEventDao.getLatestVerifiedDose(medicineId)

    override suspend fun getLatestVerifiedDoseAfter(medicineId: String, afterMillis: Long): DoseEvent? =
        doseEventDao.getLatestVerifiedDoseAfter(medicineId, afterMillis)

    override suspend fun getLatestScheduledAt(medicineId: String): Long? =
        doseEventDao.getLatestScheduledAt(medicineId)

    override suspend fun getNextScheduledDose(medicineId: String, scheduledAt: Long): DoseEvent? =
        doseEventDao.getNextScheduledDose(medicineId, scheduledAt)

    override suspend fun deleteFuturePendingDoses(medicineId: String, afterTimestamp: Long) =
        doseEventDao.deleteFuturePendingDoses(medicineId, afterTimestamp)

    override suspend fun deleteAllPendingForMedicine(medicineId: String) =
        doseEventDao.deleteAllPendingForMedicine(medicineId)

    override suspend fun deleteAllByPatientId(patientId: String) =
        doseEventDao.deleteAllByPatientId(patientId)
}

@Singleton
class VerificationRepositoryImpl @Inject constructor(
    private val verificationAttemptDao: VerificationAttemptDao
) : VerificationRepository {
    override suspend fun recordAttempt(attempt: VerificationAttempt) =
        verificationAttemptDao.insert(attempt)

    override suspend fun getAttemptsForDose(doseEventId: String): List<VerificationAttempt> =
        verificationAttemptDao.getAttemptsForDose(doseEventId)

    override suspend fun getRecentAttemptsForMedicine(medicineId: String, limit: Int): List<VerificationAttempt> =
        verificationAttemptDao.getRecentAttemptsForMedicine(medicineId, limit)
}

@Singleton
class CaregiverRepositoryImpl @Inject constructor(
    private val caregiverLinkDao: CaregiverLinkDao
) : CaregiverRepository {
    override fun observeByPatientId(patientId: String): Flow<List<CaregiverLink>> =
        caregiverLinkDao.observeByPatientId(patientId)

    override fun observeAll(): Flow<List<CaregiverLink>> =
        caregiverLinkDao.observeAll()

    override suspend fun getById(id: String): CaregiverLink? =
        caregiverLinkDao.getById(id)

    override suspend fun insert(link: CaregiverLink) = caregiverLinkDao.insert(link)
    override suspend fun update(link: CaregiverLink) = caregiverLinkDao.update(link)
    override suspend fun hasConnectedCaregiver(patientId: String): Boolean =
        caregiverLinkDao.getByStatus(patientId, LinkStatus.ACCEPTED).isNotEmpty()

    override suspend fun deleteAllByPatientId(patientId: String) =
        caregiverLinkDao.deleteAllByPatientId(patientId)
}

@Singleton
class OutboxRepositoryImpl @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val notificationOutboxDao: NotificationOutboxDao
) : OutboxRepository {
    override suspend fun enqueue(outbox: NotificationOutbox) {
        notificationOutboxDao.insert(outbox)
        try {
            com.meditrack.app.feature.reminder.worker.OutboxDeliveryWorker.enqueue(context)
        } catch (_: Exception) {}
    }
    override suspend fun getUndelivered(): List<NotificationOutbox> = notificationOutboxDao.getUndelivered()
    override suspend fun markDelivered(id: String) = notificationOutboxDao.markDelivered(id)
}

@Singleton
class DoctorRepositoryImpl @Inject constructor(
    private val doctorLinkDao: com.meditrack.app.data.db.dao.DoctorLinkDao,
    private val medicineProposalDao: com.meditrack.app.data.db.dao.MedicineProposalDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context? = null
) : com.meditrack.app.domain.repository.DoctorRepository {

    private val isFirebaseAvailable: Boolean
        get() = try {
            if (context != null) com.google.firebase.FirebaseApp.getApps(context).isNotEmpty() else false
        } catch (_: Throwable) { false }

    override fun observeDoctorLinksByPatientId(patientId: String): Flow<List<com.meditrack.app.domain.model.DoctorLink>> =
        doctorLinkDao.observeByPatientId(patientId)

    override fun observeAcceptedLinksByDoctorUid(doctorUid: String): Flow<List<com.meditrack.app.domain.model.DoctorLink>> =
        doctorLinkDao.observeAcceptedByDoctorUid(doctorUid)

    override suspend fun getDoctorLinkById(id: String): com.meditrack.app.domain.model.DoctorLink? =
        doctorLinkDao.getById(id)

    override suspend fun getDoctorLinkByDoctorAndPatient(doctorUid: String, patientId: String): com.meditrack.app.domain.model.DoctorLink? =
        doctorLinkDao.getByDoctorAndPatient(doctorUid, patientId)

    override suspend fun getDoctorLinkByInviteCode(code: String): com.meditrack.app.domain.model.DoctorLink? =
        doctorLinkDao.getByInvitationCode(code)

    override suspend fun insertDoctorLink(link: com.meditrack.app.domain.model.DoctorLink) =
        doctorLinkDao.insert(link)

    override suspend fun updateDoctorLink(link: com.meditrack.app.domain.model.DoctorLink) =
        doctorLinkDao.update(link)

    override suspend fun deleteDoctorLink(id: String) =
        doctorLinkDao.deleteById(id)

    override fun observeProposalsByPatientId(patientId: String): Flow<List<com.meditrack.app.domain.model.MedicineProposal>> =
        medicineProposalDao.observeByPatientId(patientId)

    override fun observeProposalsByDoctorUid(doctorUid: String): Flow<List<com.meditrack.app.domain.model.MedicineProposal>> =
        medicineProposalDao.observeByDoctorUid(doctorUid)

    override suspend fun getProposalById(id: String): com.meditrack.app.domain.model.MedicineProposal? =
        medicineProposalDao.getById(id)

    override suspend fun insertProposal(proposal: com.meditrack.app.domain.model.MedicineProposal) {
        medicineProposalDao.insert(proposal)
        if (isFirebaseAvailable) {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val propMap = hashMapOf<String, Any?>(
                    "id" to proposal.id,
                    "patientId" to proposal.patientId,
                    "doctorUid" to proposal.doctorUid,
                    "type" to proposal.type.name,
                    "medicineId" to proposal.medicineId,
                    "payloadJson" to proposal.payloadJson,
                    "status" to proposal.status.name,
                    "revision" to proposal.revision,
                    "clarificationNote" to proposal.clarificationNote,
                    "createdAt" to proposal.createdAt,
                    "resolvedAt" to proposal.resolvedAt
                )
                db.collection("medicineProposals").document(proposal.id).set(propMap)
            } catch (_: Exception) {}
        }
    }

    override suspend fun updateProposal(proposal: com.meditrack.app.domain.model.MedicineProposal) {
        medicineProposalDao.update(proposal)
        if (isFirebaseAvailable) {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val updates = mutableMapOf<String, Any?>(
                    "status" to proposal.status.name,
                    "resolvedAt" to proposal.resolvedAt
                )
                if (proposal.clarificationNote != null) {
                    updates["clarificationNote"] = proposal.clarificationNote
                }
                db.collection("medicineProposals").document(proposal.id).update(updates)
            } catch (_: Exception) {}
        }
    }

    override suspend fun deleteProposal(id: String) =
        medicineProposalDao.deleteById(id)
}
