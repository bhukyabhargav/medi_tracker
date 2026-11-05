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

            // 1. Sync Medicines: Push local & Pull remote with conflict resolution
            syncMedicines(db, currentUserId)

            // 2. Sync Dose Events: Push local & Pull remote with conflict resolution + Upload photos
            syncDoseEvents(db, storage, currentUserId)

            // 3. Sync Caregiver Links
            val localLinks = caregiverRepository.observeByPatientId(currentUserId).firstOrNull() ?: emptyList()
            for (link in localLinks) {
                val linkMap = hashMapOf<String, Any?>(
                    "id" to link.id,
                    "patientId" to link.patientId,
                    "caregiverName" to link.caregiverName,
                    "caregiverContact" to link.caregiverContact,
                    "relationship" to link.relationship,
                    "status" to link.status.name,
                    "caregiverUid" to link.caregiverUid,
                    "createdAt" to link.createdAt
                )
                db.collection("caregiverLinks").document(link.id).set(linkMap).await()
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun syncMedicines(db: FirebaseFirestore, currentUserId: String) {
        val localMedicines = medicineRepository.observeAll().firstOrNull() ?: emptyList()
        val localMedMap = localMedicines.associateBy { it.id }.toMutableMap()

        // Push local medicines to Firestore with complete schedule properties
        for (med in localMedicines) {
            val medMap = hashMapOf<String, Any?>(
                "id" to med.id,
                "patientId" to (med.patientId ?: currentUserId),
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
            db.collection("users").document(currentUserId)
                .collection("medicines").document(med.id)
                .set(medMap).await()
        }

        // Pull remote medicines from Firestore and apply conflict resolution
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
                FoodRelation.valueOf(doc.getString("foodRelation") ?: FoodRelation.NONE.name)
            } catch (_: Exception) { FoodRelation.NONE }

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
                }
            } else {
                medicineRepository.insert(remoteMed)
            }
        }
    }

    private suspend fun syncDoseEvents(db: FirebaseFirestore, storage: FirebaseStorage, currentUserId: String) {
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30L * 24 * 3600 * 1000)
        val thirtyDaysAhead = now + (30L * 24 * 3600 * 1000)
        val localDoses = doseRepository.observeBetween(thirtyDaysAgo, thirtyDaysAhead).firstOrNull() ?: emptyList()
        val localDoseMap = localDoses.associateBy { it.id }.toMutableMap()

        // Push local doses to Firestore
        for (dose in localDoses) {
            val doseMap = hashMapOf<String, Any?>(
                "id" to dose.id,
                "medicineId" to dose.medicineId,
                "patientId" to (dose.patientId ?: currentUserId),
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
            db.collection("users").document(currentUserId)
                .collection("doseEvents").document(dose.id)
                .set(doseMap).await()

            // Upload proof photo to Firebase Storage at proofs/{patientUid}/{doseId}.jpg
            if ((dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.TAKEN_LATE) && dose.verifiedPhotoPath != null) {
                uploadProofPhoto(storage, currentUserId, dose.id, dose.verifiedPhotoPath)
            }
        }

        // Pull remote doses from Firestore and apply conflict resolution
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
                }
            } else {
                doseRepository.insert(remoteDose)
            }
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
