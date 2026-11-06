package com.meditrack.app.feature.sync

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.SyncConflictResolver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.tasks.await
import java.io.File
import java.time.LocalTime
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val caregiverRepository: CaregiverRepository,
    private val doctorRepository: com.meditrack.app.domain.repository.DoctorRepository,
    private val userPreferences: UserPreferences
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val currentUserId = userPreferences.currentUserId.firstOrNull() ?: return Result.success()

        val isFirebaseAvailable = try {
            FirebaseApp.getApps(appContext).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        if (!isFirebaseAvailable) {
            // Offline/Fake mode: local Room database is already up to date
            return Result.success()
        }

        return try {
            val db = FirebaseFirestore.getInstance()
            val storage = FirebaseStorage.getInstance()

            // 1. Sync Medicines: Pull remote first & resolve, then push local batch
            syncMedicines(db, currentUserId)

            // 2. Sync Dose Events: Pull remote first & resolve, then push local batch + Upload photos
            syncDoseEvents(db, storage, currentUserId)

            // 3. Sync Doctor Links & Proposals (Stage 10B)
            syncDoctorLinksAndProposals(db, currentUserId)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun syncMedicines(db: FirebaseFirestore, currentUserId: String) {
        val localMedicines = medicineRepository.observeAll().firstOrNull() ?: emptyList()
        val localMedMap = localMedicines.associateBy { it.id }.toMutableMap()

        // 1. PULL REMOTE FIRST & RESOLVE CONFLICTS
        try {
            val remoteSnap = db.collection("users").document(currentUserId).collection("medicines").get().await()
            for (doc in remoteSnap.documents) {
                val id = doc.getString("id") ?: doc.id
                val name = doc.getString("name") ?: continue
                val timesOfDayList = (doc.get("timesOfDay") as? List<*>)?.mapNotNull {
                    try { LocalTime.parse(it.toString()) } catch (_: Exception) { null }
                } ?: emptyList()
                val daysOfWeekList = (doc.get("daysOfWeek") as? List<*>)?.mapNotNull {
                    (it as? Number)?.toInt()
                }
                val freqType = try {
                    FrequencyType.valueOf(doc.getString("frequencyType") ?: FrequencyType.DAILY.name)
                } catch (_: Exception) { FrequencyType.DAILY }
                val foodRel = try {
                    FoodRelation.valueOf(doc.getString("foodRelation") ?: FoodRelation.ANY.name)
                } catch (_: Exception) { FoodRelation.ANY }

                val remoteMed = Medicine(
                    id = id,
                    patientId = doc.getString("patientId") ?: currentUserId,
                    name = name,
                    dosageText = doc.getString("dosageText") ?: "",
                    pillsPerDose = doc.getLong("pillsPerDose")?.toInt() ?: 1,
                    frequencyType = freqType,
                    intervalHours = doc.getLong("intervalHours")?.toInt(),
                    timesOfDay = timesOfDayList,
                    daysOfWeek = daysOfWeekList,
                    startDate = doc.getLong("startDate") ?: System.currentTimeMillis(),
                    endDate = doc.getLong("endDate"),
                    foodRelation = foodRel,
                    instructions = doc.getString("instructions") ?: "",
                    reminderEnabled = doc.getBoolean("reminderEnabled") ?: true,
                    initialStock = doc.getLong("initialStock")?.toInt() ?: 10,
                    currentStock = doc.getLong("currentStock")?.toInt() ?: 10,
                    baselineEmptyCells = doc.getLong("baselineEmptyCells")?.toInt() ?: 0,
                    baselinePhotoPath = doc.getString("baselinePhotoPath"),
                    lowStockNotified = doc.getBoolean("lowStockNotified") ?: false,
                    lastRefillAt = doc.getLong("lastRefillAt"),
                    isDeleted = doc.getBoolean("isDeleted") ?: false,
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
                )

                val localMed = localMedMap[id]
                if (localMed != null) {
                    val resolved = SyncConflictResolver.resolveMedicineConflict(localMed, remoteMed)
                    if (resolved != localMed) {
                        medicineRepository.update(resolved)
                        localMedMap[id] = resolved
                    }
                } else {
                    medicineRepository.insert(remoteMed)
                    localMedMap[id] = remoteMed
                }
            }
        } catch (_: Exception) {}

        // 2. THEN PUSH IN BATCH: upload strictly rows matching auth patientId
        val validLocalMedicines = localMedMap.values.filter { it.patientId == currentUserId && it.patientId.isNotBlank() }
        if (validLocalMedicines.isNotEmpty()) {
            val batch = db.batch()
            for (med in validLocalMedicines) {
                val medMap = hashMapOf<String, Any?>(
                    "id" to med.id,
                    "patientId" to med.patientId,
                    "name" to med.name,
                    "dosageText" to med.dosageText,
                    "pillsPerDose" to med.pillsPerDose,
                    "frequencyType" to med.frequencyType.name,
                    "intervalHours" to med.intervalHours,
                    "timesOfDay" to med.timesOfDay.map { it.toString() },
                    "daysOfWeek" to med.daysOfWeek,
                    "startDate" to med.startDate,
                    "endDate" to med.endDate,
                    "foodRelation" to med.foodRelation.name,
                    "instructions" to med.instructions,
                    "reminderEnabled" to med.reminderEnabled,
                    "initialStock" to med.initialStock,
                    "currentStock" to med.currentStock,
                    "baselineEmptyCells" to med.baselineEmptyCells,
                    "baselinePhotoPath" to med.baselinePhotoPath,
                    "lowStockNotified" to med.lowStockNotified,
                    "lastRefillAt" to med.lastRefillAt,
                    "isDeleted" to med.isDeleted,
                    "createdAt" to med.createdAt,
                    "updatedAt" to med.updatedAt
                )
                val docRef = db.collection("users").document(currentUserId)
                    .collection("medicines").document(med.id)
                batch.set(docRef, medMap)
            }
            batch.commit().await()
        }
    }

    private suspend fun syncDoseEvents(db: FirebaseFirestore, storage: FirebaseStorage, currentUserId: String) {
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30L * 24 * 3600 * 1000)
        val thirtyDaysAhead = now + (30L * 24 * 3600 * 1000)
        val localDoses = doseRepository.observeBetween(thirtyDaysAgo, thirtyDaysAhead).firstOrNull() ?: emptyList()
        val localDoseMap = localDoses.associateBy { it.id }.toMutableMap()

        // 1. PULL REMOTE FIRST & RESOLVE CONFLICTS
        try {
            val remoteSnap = db.collection("users").document(currentUserId).collection("doseEvents").get().await()
            for (doc in remoteSnap.documents) {
                val id = doc.getString("id") ?: doc.id
                val medId = doc.getString("medicineId") ?: continue
                val statusStr = doc.getString("status") ?: DoseStatus.PENDING.name
                val status = try { DoseStatus.valueOf(statusStr) } catch (_: Exception) { DoseStatus.PENDING }

                val remoteDose = DoseEvent(
                    id = id,
                    medicineId = medId,
                    patientId = doc.getString("patientId") ?: currentUserId,
                    scheduledAt = doc.getLong("scheduledAt") ?: continue,
                    windowEndsAt = doc.getLong("windowEndsAt") ?: (doc.getLong("scheduledAt")!! + 3600_000L),
                    status = status,
                    takenAt = doc.getLong("takenAt"),
                    emptyCellsAfter = doc.getLong("emptyCellsAfter")?.toInt(),
                    lateEntry = doc.getBoolean("lateEntry") ?: false,
                    manualUnverified = doc.getBoolean("manualUnverified") ?: false,
                    nextReminderAt = doc.getLong("nextReminderAt"),
                    updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
                )

                val localDose = localDoseMap[id]
                if (localDose != null) {
                    val resolved = SyncConflictResolver.resolveDoseConflict(localDose, remoteDose)
                    if (resolved != localDose) {
                        doseRepository.update(resolved)
                        localDoseMap[id] = resolved
                    }
                } else {
                    doseRepository.insert(remoteDose)
                    localDoseMap[id] = remoteDose
                }
            }
        } catch (_: Exception) {}

        // 2. THEN PUSH IN BATCH: upload strictly rows matching auth patientId
        val validLocalDoses = localDoseMap.values.filter { it.patientId == currentUserId && it.patientId.isNotBlank() }
        if (validLocalDoses.isNotEmpty()) {
            val batch = db.batch()
            for (dose in validLocalDoses) {
                val doseMap = hashMapOf<String, Any?>(
                    "id" to dose.id,
                    "medicineId" to dose.medicineId,
                    "patientId" to dose.patientId,
                    "scheduledAt" to dose.scheduledAt,
                    "windowEndsAt" to dose.windowEndsAt,
                    "status" to dose.status.name,
                    "takenAt" to dose.takenAt,
                    "emptyCellsAfter" to dose.emptyCellsAfter,
                    "lateEntry" to dose.lateEntry,
                    "manualUnverified" to dose.manualUnverified,
                    "nextReminderAt" to dose.nextReminderAt,
                    "updatedAt" to dose.updatedAt
                )
                val docRef = db.collection("users").document(currentUserId)
                    .collection("doseEvents").document(dose.id)
                batch.set(docRef, doseMap)

                // Upload proof photo to Firebase Storage
                if ((dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.TAKEN_LATE) && dose.verifiedPhotoPath != null) {
                    uploadProofPhoto(storage, currentUserId, dose.id, dose.verifiedPhotoPath)
                }
            }
            batch.commit().await()
        }
    }

    private suspend fun uploadProofPhoto(
        storage: FirebaseStorage,
        patientUid: String,
        doseId: String,
        localPath: String
    ) {
        try {
            val file = File(localPath)
            if (!file.exists()) return

            val storageRef = storage.reference.child("proofs/$patientUid/$doseId.jpg")
            storageRef.putFile(Uri.fromFile(file)).await()
        } catch (_: Exception) {}
    }

    private suspend fun syncDoctorLinksAndProposals(db: FirebaseFirestore, currentUserId: String) {
        try {
            // Patient links
            val patientLinksSnap = db.collection("doctorLinks")
                .whereEqualTo("patientId", currentUserId)
                .get().await()
            for (doc in patientLinksSnap.documents) {
                val statusStr = doc.getString("status") ?: "PENDING"
                val status = runCatching { com.meditrack.app.domain.model.DoctorLinkStatus.valueOf(statusStr) }
                    .getOrDefault(com.meditrack.app.domain.model.DoctorLinkStatus.PENDING)
                val link = com.meditrack.app.domain.model.DoctorLink(
                    id = doc.id,
                    patientId = currentUserId,
                    doctorUid = doc.getString("doctorUid") ?: "",
                    doctorName = doc.getString("doctorName") ?: "Doctor",
                    doctorContact = doc.getString("doctorContact") ?: "",
                    status = status,
                    invitationCode = doc.getString("invitationCode"),
                    expiresAt = doc.getLong("expiresAt"),
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
                )
                doctorRepository.insertDoctorLink(link)
            }

            // Doctor links
            val docLinksSnap = db.collection("doctorLinks")
                .whereEqualTo("doctorUid", currentUserId)
                .get().await()
            for (doc in docLinksSnap.documents) {
                val statusStr = doc.getString("status") ?: "PENDING"
                val status = runCatching { com.meditrack.app.domain.model.DoctorLinkStatus.valueOf(statusStr) }
                    .getOrDefault(com.meditrack.app.domain.model.DoctorLinkStatus.PENDING)
                val link = com.meditrack.app.domain.model.DoctorLink(
                    id = doc.id,
                    patientId = doc.getString("patientId") ?: "",
                    doctorUid = currentUserId,
                    doctorName = doc.getString("doctorName") ?: "Doctor",
                    doctorContact = doc.getString("doctorContact") ?: "",
                    status = status,
                    invitationCode = doc.getString("invitationCode"),
                    expiresAt = doc.getLong("expiresAt"),
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
                )
                doctorRepository.insertDoctorLink(link)
            }

            // Proposals as Patient
            val patientPropsSnap = db.collection("medicineProposals")
                .whereEqualTo("patientId", currentUserId)
                .get().await()
            for (doc in patientPropsSnap.documents) {
                val id = doc.getString("id") ?: doc.id
                val docUid = doc.getString("doctorUid") ?: continue
                val typeStr = doc.getString("type") ?: com.meditrack.app.domain.model.ProposalType.ADD.name
                val type = runCatching { com.meditrack.app.domain.model.ProposalType.valueOf(typeStr) }
                    .getOrDefault(com.meditrack.app.domain.model.ProposalType.ADD)
                val statusStr = doc.getString("status") ?: com.meditrack.app.domain.model.ProposalStatus.PENDING.name
                val status = runCatching { com.meditrack.app.domain.model.ProposalStatus.valueOf(statusStr) }
                    .getOrDefault(com.meditrack.app.domain.model.ProposalStatus.PENDING)
                val payloadJson = doc.getString("payloadJson") ?: ""

                val proposal = com.meditrack.app.domain.model.MedicineProposal(
                    id = id,
                    patientId = currentUserId,
                    doctorUid = docUid,
                    type = type,
                    medicineId = doc.getString("medicineId"),
                    payloadJson = payloadJson,
                    status = status,
                    revision = doc.getLong("revision")?.toInt() ?: 1,
                    clarificationNote = doc.getString("clarificationNote"),
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                    resolvedAt = doc.getLong("resolvedAt")
                )
                doctorRepository.insertProposal(proposal)
            }

            // Proposals as Doctor
            val doctorPropsSnap = db.collection("medicineProposals")
                .whereEqualTo("doctorUid", currentUserId)
                .get().await()
            for (doc in doctorPropsSnap.documents) {
                val id = doc.getString("id") ?: doc.id
                val patId = doc.getString("patientId") ?: continue
                val typeStr = doc.getString("type") ?: com.meditrack.app.domain.model.ProposalType.ADD.name
                val type = runCatching { com.meditrack.app.domain.model.ProposalType.valueOf(typeStr) }
                    .getOrDefault(com.meditrack.app.domain.model.ProposalType.ADD)
                val statusStr = doc.getString("status") ?: com.meditrack.app.domain.model.ProposalStatus.PENDING.name
                val status = runCatching { com.meditrack.app.domain.model.ProposalStatus.valueOf(statusStr) }
                    .getOrDefault(com.meditrack.app.domain.model.ProposalStatus.PENDING)
                val payloadJson = doc.getString("payloadJson") ?: ""

                val proposal = com.meditrack.app.domain.model.MedicineProposal(
                    id = id,
                    patientId = patId,
                    doctorUid = currentUserId,
                    type = type,
                    medicineId = doc.getString("medicineId"),
                    payloadJson = payloadJson,
                    status = status,
                    revision = doc.getLong("revision")?.toInt() ?: 1,
                    clarificationNote = doc.getString("clarificationNote"),
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                    resolvedAt = doc.getLong("resolvedAt")
                )
                doctorRepository.insertProposal(proposal)
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val WORK_NAME = "SyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                1, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME + "_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }
}
