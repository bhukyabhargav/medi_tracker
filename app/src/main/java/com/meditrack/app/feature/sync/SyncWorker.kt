package com.meditrack.app.feature.sync

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
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
            // Offline/Fake mode: Room database is the local source of truth
            return Result.success()
        }

        return try {
            val db = FirebaseFirestore.getInstance()
            val storage = FirebaseStorage.getInstance()

            // 1. PUSH local changes to Firestore
            pushLocalMedicines(db, currentUserId)
            pushLocalDosesAndProofs(db, storage, currentUserId)
            pushLocalCaregiverLinks(db, currentUserId)

            // 2. PULL remote changes from Firestore and apply conflict resolution to Room
            pullRemoteMedicines(db, currentUserId)
            pullRemoteDoses(db, currentUserId)
            pullRemoteCaregiverLinks(db, currentUserId)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun pushLocalMedicines(db: FirebaseFirestore, currentUserId: String) {
        val localMedicines = medicineRepository.observeAll().firstOrNull() ?: emptyList()
        for (med in localMedicines) {
            val medMap = hashMapOf<String, Any?>(
                "id" to med.id,
                "name" to med.name,
                "dosageText" to med.dosageText,
                "pillsPerDose" to med.pillsPerDose,
                "frequencyType" to med.frequencyType.name,
                "startDate" to med.startDate,
                "endDate" to med.endDate,
                "initialStock" to med.initialStock,
                "currentStock" to med.currentStock,
                "isDeleted" to med.isDeleted,
                "stoppedByDoctor" to med.stoppedByDoctor,
                "createdAt" to med.createdAt
            )
            db.collection("users").document(currentUserId)
                .collection("medicines").document(med.id)
                .set(medMap).await()
        }
    }

    private suspend fun pushLocalDosesAndProofs(
        db: FirebaseFirestore,
        storage: FirebaseStorage,
        currentUserId: String
    ) {
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30L * 24 * 3600 * 1000)
        val thirtyDaysAhead = now + (30L * 24 * 3600 * 1000)
        val localDoses = doseRepository.observeBetween(thirtyDaysAgo, thirtyDaysAhead).firstOrNull() ?: emptyList()

        for (dose in localDoses) {
            val doseMap = hashMapOf<String, Any?>(
                "id" to dose.id,
                "medicineId" to dose.medicineId,
                "scheduledAt" to dose.scheduledAt,
                "windowEndsAt" to dose.windowEndsAt,
                "status" to dose.status.name,
                "takenAt" to dose.takenAt,
                "emptyCellsAfter" to dose.emptyCellsAfter,
                "lateEntry" to dose.lateEntry,
                "manualUnverified" to dose.manualUnverified,
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
    }

    private suspend fun pushLocalCaregiverLinks(db: FirebaseFirestore, currentUserId: String) {
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
    }

    private suspend fun pullRemoteMedicines(db: FirebaseFirestore, currentUserId: String) {
        val remoteDocs = db.collection("users").document(currentUserId)
            .collection("medicines").get().await()

        for (doc in remoteDocs.documents) {
            val remoteMed = parseMedicine(doc) ?: continue
            val localMed = medicineRepository.getById(remoteMed.id)
            if (localMed == null) {
                medicineRepository.insert(remoteMed)
            } else {
                val resolved = SyncConflictResolver.resolveMedicineConflict(localMed, remoteMed)
                if (resolved != localMed) {
                    medicineRepository.update(resolved)
                }
            }
        }
    }

    private suspend fun pullRemoteDoses(db: FirebaseFirestore, currentUserId: String) {
        val remoteDocs = db.collection("users").document(currentUserId)
            .collection("doseEvents").get().await()

        for (doc in remoteDocs.documents) {
            val remoteDose = parseDoseEvent(doc) ?: continue
            val localDose = doseRepository.getById(remoteDose.id)
            if (localDose == null) {
                doseRepository.insert(remoteDose)
            } else {
                val resolved = SyncConflictResolver.resolveDoseConflict(localDose, remoteDose)
                if (resolved != localDose) {
                    doseRepository.update(resolved)
                }
            }
        }
    }

    private suspend fun pullRemoteCaregiverLinks(db: FirebaseFirestore, currentUserId: String) {
        val remoteDocs = db.collection("caregiverLinks")
            .whereEqualTo("patientId", currentUserId)
            .get().await()

        for (doc in remoteDocs.documents) {
            val remoteLink = parseCaregiverLink(doc) ?: continue
            val localLink = caregiverRepository.getById(remoteLink.id)
            if (localLink == null) {
                caregiverRepository.insert(remoteLink)
            } else if (remoteLink != localLink) {
                caregiverRepository.update(remoteLink)
            }
        }
    }

    private fun parseMedicine(doc: DocumentSnapshot): Medicine? {
        val data = doc.data ?: return null
        return try {
            Medicine(
                id = doc.id,
                name = data["name"] as? String ?: "",
                dosageText = data["dosageText"] as? String ?: "",
                pillsPerDose = (data["pillsPerDose"] as? Number)?.toInt() ?: 1,
                frequencyType = FrequencyType.valueOf(data["frequencyType"] as? String ?: "DAILY"),
                timesOfDay = emptyList(),
                startDate = (data["startDate"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                endDate = (data["endDate"] as? Number)?.toLong(),
                initialStock = (data["initialStock"] as? Number)?.toInt() ?: 0,
                currentStock = (data["currentStock"] as? Number)?.toInt() ?: 0,
                isDeleted = data["isDeleted"] as? Boolean ?: false,
                stoppedByDoctor = data["stoppedByDoctor"] as? Boolean ?: false,
                createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDoseEvent(doc: DocumentSnapshot): DoseEvent? {
        val data = doc.data ?: return null
        return try {
            DoseEvent(
                id = doc.id,
                medicineId = data["medicineId"] as? String ?: "",
                scheduledAt = (data["scheduledAt"] as? Number)?.toLong() ?: 0L,
                windowEndsAt = (data["windowEndsAt"] as? Number)?.toLong() ?: 0L,
                status = DoseStatus.valueOf(data["status"] as? String ?: "PENDING"),
                takenAt = (data["takenAt"] as? Number)?.toLong(),
                emptyCellsAfter = (data["emptyCellsAfter"] as? Number)?.toInt(),
                lateEntry = data["lateEntry"] as? Boolean ?: false,
                manualUnverified = data["manualUnverified"] as? Boolean ?: false,
                updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCaregiverLink(doc: DocumentSnapshot): CaregiverLink? {
        val data = doc.data ?: return null
        return try {
            CaregiverLink(
                id = doc.id,
                patientId = data["patientId"] as? String ?: "",
                caregiverName = data["caregiverName"] as? String ?: "",
                caregiverContact = data["caregiverContact"] as? String ?: "",
                relationship = data["relationship"] as? String ?: "",
                status = LinkStatus.valueOf(data["status"] as? String ?: "PENDING"),
                caregiverUid = data["caregiverUid"] as? String,
                createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
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
    }
}
