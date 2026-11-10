package com.meditrack.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.LinkStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.VerificationAttempt
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getUserById(id: String): User?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    fun observeUserById(id: String): Flow<User?>

    @Query("SELECT * FROM users WHERE (email IS NOT NULL AND LOWER(email) = LOWER(:contact)) OR phone = :contact LIMIT 1")
    suspend fun getUserByContact(contact: String): User?

    @Query("SELECT * FROM users LIMIT 1")
    fun observeCurrentUser(): Flow<User?>

    @Query("DELETE FROM users")
    suspend fun clearUsers()
}

@Dao
interface MedicineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medicine: Medicine)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medicines: List<Medicine>)

    @Update
    suspend fun update(medicine: Medicine)

    @Delete
    suspend fun delete(medicine: Medicine)

    @Query("SELECT * FROM medicines WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Medicine?

    @Query("SELECT * FROM medicines WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<Medicine?>

    @Query("SELECT * FROM medicines ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Medicine>>

    @Query("SELECT * FROM medicines WHERE stoppedByDoctor = 0 AND isDeleted = 0 ORDER BY name ASC")
    fun observeActive(): Flow<List<Medicine>>

    @Query("SELECT * FROM medicines WHERE patientId = :patientId ORDER BY createdAt DESC")
    fun observeAllByPatient(patientId: String): Flow<List<Medicine>>

    @Query("SELECT * FROM medicines WHERE patientId = :patientId AND stoppedByDoctor = 0 AND isDeleted = 0 ORDER BY name ASC")
    fun observeActiveByPatient(patientId: String): Flow<List<Medicine>>

    @Query("UPDATE medicines SET currentStock = :newStock, updatedAt = :now WHERE id = :medicineId")
    suspend fun updateStock(medicineId: String, newStock: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE medicines SET currentStock = MAX(0, currentStock - :count), updatedAt = :now WHERE id = :medicineId")
    suspend fun decrementStockAtomic(medicineId: String, count: Int, now: Long): Int

    @Query("DELETE FROM medicines WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM medicines WHERE patientId = :patientId")
    suspend fun deleteAllByPatientId(patientId: String)

    @Query("SELECT * FROM medicines WHERE patientId = :patientId")
    suspend fun getAllByPatientId(patientId: String): List<Medicine>

    @Query("UPDATE medicines SET patientId = :userId WHERE patientId = '' OR patientId IS NULL")
    suspend fun claimLegacyMedicines(userId: String): Int

    @Query("SELECT COUNT(*) FROM medicines WHERE patientId = '' OR patientId IS NULL")
    suspend fun countLegacyMedicines(): Int
}

@Dao
interface DoseEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doseEvent: DoseEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(doseEvents: List<DoseEvent>)

    @Update
    suspend fun update(doseEvent: DoseEvent)

    @Query("SELECT * FROM dose_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DoseEvent?

    @Query("SELECT * FROM dose_events WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<DoseEvent?>

    @Query("SELECT * FROM dose_events WHERE scheduledAt >= :startTime AND scheduledAt <= :endTime ORDER BY scheduledAt ASC")
    fun observeBetween(startTime: Long, endTime: Long): Flow<List<DoseEvent>>

    @Query("SELECT * FROM dose_events WHERE patientId = :patientId AND scheduledAt >= :startTime AND scheduledAt <= :endTime ORDER BY scheduledAt ASC")
    fun observeBetweenByPatient(patientId: String, startTime: Long, endTime: Long): Flow<List<DoseEvent>>

    @Query("SELECT * FROM dose_events WHERE medicineId = :medicineId ORDER BY scheduledAt ASC")
    fun observeByMedicineId(medicineId: String): Flow<List<DoseEvent>>

    @Query("SELECT * FROM dose_events WHERE status = 'PENDING' AND scheduledAt <= :now ORDER BY scheduledAt ASC")
    suspend fun getPendingDueDoses(now: Long): List<DoseEvent>

    @Query("SELECT * FROM dose_events WHERE status = 'PENDING' AND scheduledAt >= :now ORDER BY scheduledAt ASC")
    suspend fun getFuturePendingDoses(now: Long): List<DoseEvent>

    @Query("SELECT * FROM dose_events WHERE status IN ('PENDING', 'UNVERIFIED') AND windowEndsAt <= :now ORDER BY windowEndsAt ASC")
    suspend fun getOverduePendingDoses(now: Long): List<DoseEvent>

    @Query("SELECT * FROM dose_events WHERE medicineId = :medicineId AND status IN ('TAKEN', 'TAKEN_LATE') ORDER BY CASE WHEN takenAt IS NOT NULL THEN takenAt ELSE scheduledAt END DESC LIMIT 1")
    suspend fun getLatestVerifiedDose(medicineId: String): DoseEvent?

    @Query("SELECT * FROM dose_events WHERE medicineId = :medicineId AND status IN ('TAKEN', 'TAKEN_LATE') AND (CASE WHEN takenAt IS NOT NULL THEN takenAt ELSE updatedAt END) >= :afterMillis ORDER BY CASE WHEN takenAt IS NOT NULL THEN takenAt ELSE scheduledAt END DESC LIMIT 1")
    suspend fun getLatestVerifiedDoseAfter(medicineId: String, afterMillis: Long): DoseEvent?

    @Query("SELECT MAX(scheduledAt) FROM dose_events WHERE medicineId = :medicineId")
    suspend fun getLatestScheduledAt(medicineId: String): Long?

    @Query("SELECT * FROM dose_events WHERE medicineId = :medicineId AND scheduledAt > :scheduledAt ORDER BY scheduledAt ASC LIMIT 1")
    suspend fun getNextScheduledDose(medicineId: String, scheduledAt: Long): DoseEvent?

    @Query("DELETE FROM dose_events WHERE medicineId = :medicineId AND status = 'PENDING' AND scheduledAt > :afterTimestamp")
    suspend fun deleteFuturePendingDoses(medicineId: String, afterTimestamp: Long)

    @Query("DELETE FROM dose_events WHERE medicineId = :medicineId AND status = 'PENDING'")
    suspend fun deleteAllPendingForMedicine(medicineId: String)

    @Query("DELETE FROM dose_events WHERE patientId = :patientId")
    suspend fun deleteAllByPatientId(patientId: String)

    @Query("SELECT * FROM dose_events WHERE patientId = :patientId")
    suspend fun getAllByPatientId(patientId: String): List<DoseEvent>

    @Query("UPDATE dose_events SET patientId = :userId WHERE patientId = '' OR patientId IS NULL")
    suspend fun claimLegacyDoseEvents(userId: String): Int

    @Query("SELECT COUNT(*) FROM dose_events WHERE patientId = '' OR patientId IS NULL")
    suspend fun countLegacyDoseEvents(): Int
}

@Dao
interface VerificationAttemptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attempt: VerificationAttempt)

    @Query("SELECT * FROM verification_attempts WHERE doseEventId = :doseEventId ORDER BY createdAt ASC")
    suspend fun getAttemptsForDose(doseEventId: String): List<VerificationAttempt>

    @Query("SELECT va.* FROM verification_attempts va INNER JOIN dose_events de ON va.doseEventId = de.id WHERE de.medicineId = :medicineId ORDER BY va.createdAt DESC LIMIT :limit")
    suspend fun getRecentAttemptsForMedicine(medicineId: String, limit: Int = 10): List<VerificationAttempt>

    @Query("SELECT * FROM verification_attempts ORDER BY createdAt DESC")
    suspend fun getAll(): List<VerificationAttempt>

    @Query("DELETE FROM verification_attempts WHERE doseEventId IN (SELECT id FROM dose_events WHERE patientId = :patientId)")
    suspend fun deleteAllByPatientId(patientId: String)
}

@Dao
interface CaregiverLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: CaregiverLink)

    @Update
    suspend fun update(link: CaregiverLink)

    @Query("SELECT * FROM caregiver_links WHERE patientId = :patientId")
    fun observeByPatientId(patientId: String): Flow<List<CaregiverLink>>

    @Query("SELECT * FROM caregiver_links WHERE patientId = :patientId AND status = :status")
    suspend fun getByStatus(patientId: String, status: LinkStatus = LinkStatus.ACCEPTED): List<CaregiverLink>

    @Query("SELECT * FROM caregiver_links WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CaregiverLink?

    @Query("SELECT * FROM caregiver_links")
    fun observeAll(): Flow<List<CaregiverLink>>

    @Query("DELETE FROM caregiver_links WHERE patientId = :patientId")
    suspend fun deleteAllByPatientId(patientId: String)

    @Query("SELECT * FROM caregiver_links WHERE patientId = :patientId")
    suspend fun getAllByPatientId(patientId: String): List<CaregiverLink>
}

@Dao
interface DoctorLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: DoctorLink)

    @Update
    suspend fun update(link: DoctorLink)

    @Delete
    suspend fun delete(link: DoctorLink)

    @Query("SELECT * FROM doctor_links WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DoctorLink?

    @Query("SELECT * FROM doctor_links WHERE patientId = :patientId")
    fun observeByPatientId(patientId: String): Flow<List<DoctorLink>>

    @Query("SELECT * FROM doctor_links WHERE doctorUid = :doctorUid")
    fun observeByDoctorUid(doctorUid: String): Flow<List<DoctorLink>>

    @Query("SELECT * FROM doctor_links WHERE doctorUid = :doctorUid AND status = 'ACCEPTED'")
    fun observeAcceptedForDoctor(doctorUid: String): Flow<List<DoctorLink>>

    @Query("SELECT * FROM doctor_links WHERE patientId = :patientId AND status = :status")
    suspend fun getByStatus(patientId: String, status: DoctorLinkStatus = DoctorLinkStatus.ACCEPTED): List<DoctorLink>

    @Query("SELECT * FROM doctor_links")
    fun observeAll(): Flow<List<DoctorLink>>

    @Query("DELETE FROM doctor_links WHERE patientId = :patientId")
    suspend fun deleteAllByPatientId(patientId: String)

    @Query("SELECT * FROM doctor_links WHERE patientId = :patientId")
    suspend fun getAllByPatientId(patientId: String): List<DoctorLink>
}

@Dao
interface MedicineProposalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(proposal: MedicineProposal)

    @Update
    suspend fun update(proposal: MedicineProposal)

    @Delete
    suspend fun delete(proposal: MedicineProposal)

    @Query("SELECT * FROM medicine_proposals WHERE patientId = :patientId ORDER BY createdAt DESC")
    fun observeByPatientId(patientId: String): Flow<List<MedicineProposal>>

    @Query("SELECT * FROM medicine_proposals WHERE doctorUid = :doctorUid ORDER BY createdAt DESC")
    fun observeByDoctorUid(doctorUid: String): Flow<List<MedicineProposal>>

    @Query("SELECT * FROM medicine_proposals WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MedicineProposal?

    @Query("SELECT * FROM medicine_proposals ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MedicineProposal>>

    @Query("DELETE FROM medicine_proposals WHERE patientId = :patientId")
    suspend fun deleteAllByPatientId(patientId: String)

    @Query("SELECT * FROM medicine_proposals WHERE patientId = :patientId")
    suspend fun getAllByPatientId(patientId: String): List<MedicineProposal>
}

@Dao
interface NotificationOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(outbox: NotificationOutbox)

    @Query("SELECT * FROM notification_outbox WHERE delivered = 0 ORDER BY createdAt ASC")
    suspend fun getUndelivered(): List<NotificationOutbox>

    @Query("UPDATE notification_outbox SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: String)

    @Query("DELETE FROM notification_outbox")
    suspend fun clearAll()
}
