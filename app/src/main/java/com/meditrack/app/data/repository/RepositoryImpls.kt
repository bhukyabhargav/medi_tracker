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
import com.meditrack.app.data.prefs.UserPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicineRepositoryImpl @Inject constructor(
    private val medicineDao: MedicineDao,
    private val userPreferences: UserPreferences
) : MedicineRepository {
    // Account isolation: every list read is scoped to the signed-in user (patientId).
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<Medicine>> =
        userPreferences.currentUserId.flatMapLatest { uid -> medicineDao.observeAllByPatient(uid ?: NO_USER) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeActive(): Flow<List<Medicine>> =
        userPreferences.currentUserId.flatMapLatest { uid -> medicineDao.observeActiveByPatient(uid ?: NO_USER) }

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
    private val doseEventDao: DoseEventDao,
    private val userPreferences: UserPreferences
) : DoseRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeBetween(startTime: Long, endTime: Long): Flow<List<DoseEvent>> =
        userPreferences.currentUserId.flatMapLatest { uid ->
            doseEventDao.observeBetweenByPatient(uid ?: NO_USER, startTime, endTime)
        }

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
    private val doctorLinkDao: com.meditrack.app.data.db.dao.DoctorLinkDao
) : com.meditrack.app.domain.repository.DoctorRepository {
    override fun observeByPatientId(patientId: String): Flow<List<com.meditrack.app.domain.model.DoctorLink>> =
        doctorLinkDao.observeByPatientId(patientId)

    override fun observeByDoctorUid(doctorUid: String): Flow<List<com.meditrack.app.domain.model.DoctorLink>> =
        doctorLinkDao.observeByDoctorUid(doctorUid)

    override fun observeAcceptedForDoctor(doctorUid: String): Flow<List<com.meditrack.app.domain.model.DoctorLink>> =
        doctorLinkDao.observeAcceptedForDoctor(doctorUid)

    override fun observeAll(): Flow<List<com.meditrack.app.domain.model.DoctorLink>> =
        doctorLinkDao.observeAll()

    override suspend fun getById(id: String): com.meditrack.app.domain.model.DoctorLink? =
        doctorLinkDao.getById(id)

    override suspend fun insert(link: com.meditrack.app.domain.model.DoctorLink) =
        doctorLinkDao.insert(link)

    override suspend fun update(link: com.meditrack.app.domain.model.DoctorLink) =
        doctorLinkDao.update(link)

    override suspend fun delete(link: com.meditrack.app.domain.model.DoctorLink) =
        doctorLinkDao.delete(link)

    override suspend fun hasAcceptedDoctor(patientId: String): Boolean =
        doctorLinkDao.getByStatus(patientId, com.meditrack.app.domain.model.DoctorLinkStatus.ACCEPTED).isNotEmpty()

    override suspend fun deleteAllByPatientId(patientId: String) =
        doctorLinkDao.deleteAllByPatientId(patientId)
}

@Singleton
class ProposalRepositoryImpl @Inject constructor(
    private val medicineProposalDao: com.meditrack.app.data.db.dao.MedicineProposalDao
) : com.meditrack.app.domain.repository.ProposalRepository {
    override fun observeByPatientId(patientId: String): Flow<List<com.meditrack.app.domain.model.MedicineProposal>> =
        medicineProposalDao.observeByPatientId(patientId)

    override fun observeByDoctorUid(doctorUid: String): Flow<List<com.meditrack.app.domain.model.MedicineProposal>> =
        medicineProposalDao.observeByDoctorUid(doctorUid)

    override fun observeAll(): Flow<List<com.meditrack.app.domain.model.MedicineProposal>> =
        medicineProposalDao.observeAll()

    override suspend fun getById(id: String): com.meditrack.app.domain.model.MedicineProposal? =
        medicineProposalDao.getById(id)

    override suspend fun insert(proposal: com.meditrack.app.domain.model.MedicineProposal) =
        medicineProposalDao.insert(proposal)

    override suspend fun update(proposal: com.meditrack.app.domain.model.MedicineProposal) =
        medicineProposalDao.update(proposal)

    override suspend fun delete(proposal: com.meditrack.app.domain.model.MedicineProposal) =
        medicineProposalDao.delete(proposal)

    override suspend fun deleteAllByPatientId(patientId: String) =
        medicineProposalDao.deleteAllByPatientId(patientId)
}

/** patientId that matches nothing: used when nobody is signed in. */
private const val NO_USER = "__no_user__"
