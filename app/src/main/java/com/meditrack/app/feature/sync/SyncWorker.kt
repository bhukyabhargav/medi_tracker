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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.LinkStatus
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

        // Only sync for the account that is really signed in to Firebase. The local repositories are
        // already scoped to currentUserId, so another account's rows can never be uploaded here.
        val authUid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
        if (authUid == null || authUid != currentUserId) return Result.success()

        val startedAt = System.currentTimeMillis()
        val syncPrefs = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val lastSync = syncPrefs.getLong("last_sync_$currentUserId", 0L)

        return try {
            val db = FirebaseFirestore.getInstance()
            val storage = FirebaseStorage.getInstance()

            // 1. PULL + RESOLVE first. Pushing first would let a stale device overwrite a remote
            //    TAKEN dose before the conflict rule (never downgrade a verified dose) is applied.
            pullMedicines(db, currentUserId)
            pullDoses(db, currentUserId, startedAt)
            pullCaregiverLinks(db, currentUserId)

            // 2. PUSH only what changed since the last successful sync, in batched writes.
            pushMedicines(db, currentUserId, lastSync)
            pushDoses(db, storage, currentUserId, lastSync, startedAt)

            syncPrefs.edit().putLong("last_sync_$currentUserId", startedAt).apply()

            // Restored/extended doses need their alarms armed.
            try {
                com.meditrack.app.feature.reminder.worker.AlarmMaintenanceWorker.runNow(appContext)
            } catch (_: Exception) {
            }
            Result.success()
        } catch (e: FirebaseFirestoreException) {
            // Rules rejected us: retrying forever cannot help.
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    // ---------------------------------------------------------------- pull

    private suspend fun pullMedicines(db: FirebaseFirestore, currentUserId: String) {
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
                patientId = currentUserId,
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

            val localMed = medicineRepository.getById(id)
            if (localMed != null) {
                val resolved = SyncConflictResolver.resolveMedicineConflict(localMed, remoteMed)
                if (resolved != localMed) {
                    medicineRepository.update(resolved)
                }
            } else {
                medicineRepository.insert(remoteMed)
            }
        }
    }

    private suspend fun pullDoses(db: FirebaseFirestore, currentUserId: String, now: Long) {
        val remoteSnap = db.collection("users").document(currentUserId).collection("doseEvents").get().await()
        for (doc in remoteSnap.documents) {
            val id = doc.getString("id") ?: doc.id
            val medId = doc.getString("medicineId") ?: continue
            val scheduledAt = doc.getLong("scheduledAt") ?: continue
            val statusStr = doc.getString("status") ?: DoseStatus.PENDING.name
            val status = try { DoseStatus.valueOf(statusStr) } catch (_: Exception) { DoseStatus.PENDING }

            // Foreign key dose_events.medicineId is RESTRICT: never insert a dose whose medicine is unknown.
            if (medicineRepository.getById(medId) == null) continue

            val windowEndsAt = doc.getLong("windowEndsAt") ?: (scheduledAt + 3600_000L)
            val remoteDose = DoseEvent(
                id = id,
                medicineId = medId,
                patientId = currentUserId,
                scheduledAt = scheduledAt,
                windowEndsAt = windowEndsAt,
                status = status,
                takenAt = doc.getLong("takenAt"),
                emptyCellsAfter = doc.getLong("emptyCellsAfter")?.toInt(),
                verificationAttempts = doc.getLong("verificationAttempts")?.toInt() ?: 0,
                snoozeCount = doc.getLong("snoozeCount")?.toInt() ?: 0,
                lateEntry = doc.getBoolean("lateEntry") ?: false,
                manualUnverified = doc.getBoolean("manualUnverified") ?: false,
                nextReminderAt = doc.getLong("nextReminderAt"),
                updatedAt = doc.getLong("updatedAt") ?: now
            )

            val localDose = doseRepository.getById(id)
            if (localDose != null) {
                val resolved = SyncConflictResolver.resolveDoseConflict(localDose, remoteDose)
                if (resolved != localDose) {
                    doseRepository.update(resolved)
                }
            } else {
                // A restored dose whose window already ended must not trigger fresh MISSED alerts to the
                // caregiver: store it as MISSED silently so history stays accurate.
                val restored = if (remoteDose.status == DoseStatus.PENDING && remoteDose.windowEndsAt <= now) {
                    remoteDose.copy(status = DoseStatus.MISSED, updatedAt = now)
                } else {
                    remoteDose
                }
                doseRepository.insert(restored)
            }
        }
    }

    private suspend fun pullCaregiverLinks(db: FirebaseFirestore, currentUserId: String) {
        // Links are created/accepted server-side (invitation code flow); the device only mirrors them.
        val snap = db.collection("caregiverLinks").whereEqualTo("patientId", currentUserId).get().await()
        for (doc in snap.documents) {
            val status = try {
                LinkStatus.valueOf(doc.getString("status") ?: LinkStatus.PENDING.name)
            } catch (_: Exception) { LinkStatus.PENDING }
            val caregiverUid = doc.getString("caregiverUid")
            val local = caregiverRepository.getById(doc.id)
            if (local == null) {
                caregiverRepository.insert(
                    CaregiverLink(
                        id = doc.id,
                        patientId = currentUserId,
                        caregiverName = doc.getString("caregiverName") ?: "Caregiver",
                        caregiverContact = doc.getString("caregiverContact") ?: "",
                        relationship = doc.getString("relationship") ?: "",
                        status = status,
                        caregiverUid = caregiverUid,
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                    )
                )
            } else if (local.status != status || local.caregiverUid != caregiverUid) {
                caregiverRepository.update(local.copy(status = status, caregiverUid = caregiverUid))
            }
        }
    }

    // ---------------------------------------------------------------- push

    private suspend fun pushMedicines(db: FirebaseFirestore, currentUserId: String, lastSync: Long) {
        val changed = (medicineRepository.observeAll().firstOrNull() ?: emptyList())
            .filter { it.patientId == currentUserId && it.updatedAt >= lastSync }

        val writes = changed.map { med ->
            val ref = db.collection("users").document(currentUserId).collection("medicines").document(med.id)
            ref to hashMapOf<String, Any?>(
                "id" to med.id,
                "patientId" to currentUserId,
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
                "prescribedByDoctorUid" to med.prescribedByDoctorUid,
                "prescribedByDoctorName" to med.prescribedByDoctorName,
                "stoppedByDoctor" to med.stoppedByDoctor,
                "createdAt" to med.createdAt,
                "updatedAt" to med.updatedAt
            )
        }
        commitInBatches(db, writes)
    }

    private suspend fun pushDoses(
        db: FirebaseFirestore,
        storage: FirebaseStorage,
        currentUserId: String,
        lastSync: Long,
        now: Long
    ) {
        val thirtyDaysAgo = now - (30L * 24 * 3600 * 1000)
        val thirtyDaysAhead = now + (30L * 24 * 3600 * 1000)
        val localDoses = (doseRepository.observeBetween(thirtyDaysAgo, thirtyDaysAhead).firstOrNull() ?: emptyList())
            .filter { it.patientId == currentUserId && it.updatedAt >= lastSync }

        val writes = localDoses.map { dose ->
            val ref = db.collection("users").document(currentUserId).collection("doseEvents").document(dose.id)
            ref to hashMapOf<String, Any?>(
                "id" to dose.id,
                "medicineId" to dose.medicineId,
                "patientId" to currentUserId,
                "scheduledAt" to dose.scheduledAt,
                "windowEndsAt" to dose.windowEndsAt,
                "status" to dose.status.name,
                "takenAt" to dose.takenAt,
                "emptyCellsAfter" to dose.emptyCellsAfter,
                "verificationAttempts" to dose.verificationAttempts,
                "snoozeCount" to dose.snoozeCount,
                "lateEntry" to dose.lateEntry,
                "manualUnverified" to dose.manualUnverified,
                "nextReminderAt" to dose.nextReminderAt,
                "updatedAt" to dose.updatedAt
            )
        }
        commitInBatches(db, writes)

        // Proof photos: upload once per dose (Firebase Storage proofs/{patientUid}/{doseId}.jpg)
        val syncPrefs = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val uploaded = syncPrefs.getStringSet("uploaded_proofs_$currentUserId", emptySet())!!.toMutableSet()
        for (dose in localDoses) {
            val photo = dose.verifiedPhotoPath
            val verified = dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.TAKEN_LATE
            if (verified && photo != null && dose.id !in uploaded) {
                if (uploadProofPhoto(storage, currentUserId, dose.id, photo)) uploaded.add(dose.id)
            }
        }
        syncPrefs.edit().putStringSet("uploaded_proofs_$currentUserId", uploaded).apply()
    }

    private suspend fun commitInBatches(
        db: FirebaseFirestore,
        writes: List<Pair<com.google.firebase.firestore.DocumentReference, HashMap<String, Any?>>>
    ) {
        for (chunk in writes.chunked(MAX_BATCH_WRITES)) {
            val batch = db.batch()
            for ((ref, data) in chunk) batch.set(ref, data)
            batch.commit().await()
        }
    }

    private suspend fun uploadProofPhoto(
        storage: FirebaseStorage,
        patientUid: String,
        doseId: String,
        localPath: String
    ): Boolean {
        return try {
            val file = File(localPath)
            if (!file.exists()) return false

            val storageRef = storage.reference.child("proofs/$patientUid/$doseId.jpg")
            storageRef.putFile(Uri.fromFile(file)).await()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val WORK_NAME = "SyncWorker"
        private const val SYNC_PREFS = "meditrack_sync"
        private const val MAX_BATCH_WRITES = 400

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME + "_once",
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
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
