package com.meditrack.app.domain.repository

import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.VerificationAttempt
import kotlinx.coroutines.flow.Flow

interface MedicineRepository {
    fun observeAll(): Flow<List<Medicine>>
    fun observeActive(): Flow<List<Medicine>>
    fun observeById(id: String): Flow<Medicine?>
    suspend fun getById(id: String): Medicine?
    suspend fun insert(medicine: Medicine)
    suspend fun update(medicine: Medicine)
    suspend fun delete(medicine: Medicine)
    suspend fun updateStock(medicineId: String, newStock: Int)
    suspend fun decrementStockAtomic(medicineId: String, count: Int, now: Long): Int
    suspend fun deleteAllByPatientId(patientId: String)
}

interface DoseRepository {
    fun observeBetween(startTime: Long, endTime: Long): Flow<List<DoseEvent>>
    fun observeByMedicineId(medicineId: String): Flow<List<DoseEvent>>
    fun observeById(id: String): Flow<DoseEvent?>
    suspend fun getById(id: String): DoseEvent?
    suspend fun insert(doseEvent: DoseEvent)
    suspend fun insertAll(doseEvents: List<DoseEvent>)
    suspend fun update(doseEvent: DoseEvent)
    suspend fun getPendingDueDoses(now: Long): List<DoseEvent>
    suspend fun getFuturePendingDoses(now: Long): List<DoseEvent>
    suspend fun getOverduePendingDoses(now: Long): List<DoseEvent>
    suspend fun getLatestVerifiedDose(medicineId: String): DoseEvent?
    suspend fun getLatestVerifiedDoseAfter(medicineId: String, afterMillis: Long): DoseEvent?
    suspend fun getLatestScheduledAt(medicineId: String): Long?
    suspend fun getNextScheduledDose(medicineId: String, scheduledAt: Long): DoseEvent?
    suspend fun deleteAllPendingForMedicine(medicineId: String)
    suspend fun deleteAllByPatientId(patientId: String)
    suspend fun deleteFuturePendingDoses(medicineId: String, afterTimestamp: Long)
}

interface VerificationRepository {
    suspend fun recordAttempt(attempt: VerificationAttempt)
    suspend fun getAttemptsForDose(doseEventId: String): List<VerificationAttempt>
    suspend fun getRecentAttemptsForMedicine(medicineId: String, limit: Int = 10): List<VerificationAttempt>
}

interface CaregiverRepository {
    fun observeByPatientId(patientId: String): Flow<List<CaregiverLink>>
    fun observeAll(): Flow<List<CaregiverLink>>
    suspend fun getById(id: String): CaregiverLink?
    suspend fun insert(link: CaregiverLink)
    suspend fun update(link: CaregiverLink)
    suspend fun hasConnectedCaregiver(patientId: String): Boolean
    suspend fun deleteAllByPatientId(patientId: String)
}

interface OutboxRepository {
    suspend fun enqueue(outbox: NotificationOutbox)
    suspend fun getUndelivered(): List<NotificationOutbox>
    suspend fun markDelivered(id: String)
}

interface DoctorRepository {
    fun observeByPatientId(patientId: String): Flow<List<com.meditrack.app.domain.model.DoctorLink>>
    fun observeByDoctorUid(doctorUid: String): Flow<List<com.meditrack.app.domain.model.DoctorLink>>
    fun observeAcceptedForDoctor(doctorUid: String): Flow<List<com.meditrack.app.domain.model.DoctorLink>>
    fun observeAll(): Flow<List<com.meditrack.app.domain.model.DoctorLink>>
    suspend fun getById(id: String): com.meditrack.app.domain.model.DoctorLink?
    suspend fun insert(link: com.meditrack.app.domain.model.DoctorLink)
    suspend fun update(link: com.meditrack.app.domain.model.DoctorLink)
    suspend fun delete(link: com.meditrack.app.domain.model.DoctorLink)
    suspend fun hasAcceptedDoctor(patientId: String): Boolean
    suspend fun deleteAllByPatientId(patientId: String)
}

interface ProposalRepository {
    fun observeByPatientId(patientId: String): Flow<List<com.meditrack.app.domain.model.MedicineProposal>>
    fun observeByDoctorUid(doctorUid: String): Flow<List<com.meditrack.app.domain.model.MedicineProposal>>
    fun observeAll(): Flow<List<com.meditrack.app.domain.model.MedicineProposal>>
    suspend fun getById(id: String): com.meditrack.app.domain.model.MedicineProposal?
    suspend fun insert(proposal: com.meditrack.app.domain.model.MedicineProposal)
    suspend fun update(proposal: com.meditrack.app.domain.model.MedicineProposal)
    suspend fun delete(proposal: com.meditrack.app.domain.model.MedicineProposal)
    suspend fun deleteAllByPatientId(patientId: String)
}
