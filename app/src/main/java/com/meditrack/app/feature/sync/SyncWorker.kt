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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
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
            // Offline/Fake mode: local Room database is already up to date
            return Result.success()
        }

        return try {
            val db = FirebaseFirestore.getInstance()
            val storage = FirebaseStorage.getInstance()

            // 1. Sync Medicines to Firestore
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
                    "createdAt" to med.createdAt
                )
                db.collection("users").document(currentUserId)
                    .collection("medicines").document(med.id)
                    .set(medMap).await()
            }

            // 2. Sync Dose Events to Firestore and upload proof photos
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
