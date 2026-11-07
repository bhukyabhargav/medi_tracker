package com.meditrack.app.domain.usecase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoctorLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.db.dao.MedicineProposalDao
import com.meditrack.app.data.db.dao.NotificationOutboxDao
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.db.dao.VerificationAttemptDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeleteAccountUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences,
    private val userDao: UserDao,
    private val medicineDao: MedicineDao,
    private val doseEventDao: DoseEventDao,
    private val caregiverLinkDao: CaregiverLinkDao,
    private val doctorLinkDao: DoctorLinkDao,
    private val medicineProposalDao: MedicineProposalDao,
    private val verificationAttemptDao: VerificationAttemptDao,
    private val notificationOutboxDao: NotificationOutboxDao
) {

    suspend operator fun invoke(targetUserId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentUser = authRepository.getCurrentUser()
            val userId = targetUserId ?: currentUser?.id ?: "unknown_user"

            // 1. Purge local database records for this user
            doseEventDao.deleteAllByPatientId(userId)
            verificationAttemptDao.deleteAllByPatientId(userId)
            medicineDao.deleteAllByPatientId(userId)
            caregiverLinkDao.deleteAllByPatientId(userId)
            doctorLinkDao.deleteAllByPatientId(userId)
            medicineProposalDao.deleteAllByPatientId(userId)
            notificationOutboxDao.clearAll()
            userDao.clearUsers()

            // 2. Purge local app-private files (proofs, baselines, cache exports)
            deleteDirectoryContents(File(context.filesDir, "proofs"))
            deleteDirectoryContents(File(context.filesDir, "enrollment"))
            deleteDirectoryContents(File(context.filesDir, "dataset"))
            deleteDirectoryContents(File(context.cacheDir, "exports"))

            // 3. Cloud Deletion (if Firebase is active and user is signed in)
            deleteCloudDataIfAvailable(userId)

            // 4. Wipe all local preferences, session flags, and log out
            userPreferences.resetAll()
            authRepository.logout()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun deleteCloudDataIfAvailable(userId: String) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) return
            val fbUser = FirebaseAuth.getInstance().currentUser ?: return
            val db = FirebaseFirestore.getInstance()

            // Delete patient user document and subcollections
            val userDocRef = db.collection("users").document(userId)
            try {
                val medDocs = userDocRef.collection("medicines").get().await()
                for (doc in medDocs.documents) {
                    doc.reference.delete().await()
                }
                val doseDocs = userDocRef.collection("doseEvents").get().await()
                for (doc in doseDocs.documents) {
                    doc.reference.delete().await()
                }
                userDocRef.delete().await()
            } catch (_: Exception) {}

            // Delete caregiver links
            try {
                val cgDocs = db.collection("caregiverLinks").whereEqualTo("patientId", userId).get().await()
                for (doc in cgDocs.documents) {
                    doc.reference.delete().await()
                }
            } catch (_: Exception) {}

            // Delete doctor links
            try {
                val docLinks = db.collection("doctorLinks").whereEqualTo("patientId", userId).get().await()
                for (doc in docLinks.documents) {
                    doc.reference.delete().await()
                }
                val docLinksAsDoctor = db.collection("doctorLinks").whereEqualTo("doctorUid", userId).get().await()
                for (doc in docLinksAsDoctor.documents) {
                    doc.reference.delete().await()
                }
            } catch (_: Exception) {}

            // Delete doctor invitations
            try {
                val invites = db.collection("doctorInvitations").whereEqualTo("doctorUid", userId).get().await()
                for (doc in invites.documents) {
                    doc.reference.delete().await()
                }
            } catch (_: Exception) {}

            // Delete medicine proposals
            try {
                val props = db.collection("medicineProposals").whereEqualTo("patientId", userId).get().await()
                for (doc in props.documents) {
                    doc.reference.delete().await()
                }
                val propsAsDoc = db.collection("medicineProposals").whereEqualTo("doctorUid", userId).get().await()
                for (doc in propsAsDoc.documents) {
                    doc.reference.delete().await()
                }
            } catch (_: Exception) {}

            // Delete Firebase Storage proof photos
            try {
                val storageRef = FirebaseStorage.getInstance().reference.child("proofs/$userId")
                val files = storageRef.listAll().await()
                for (item in files.items) {
                    item.delete().await()
                }
            } catch (_: Exception) {}

            // Finally, delete Firebase Auth user account
            try {
                fbUser.delete().await()
            } catch (_: Exception) {}

        } catch (_: Exception) {
            // Best-effort cloud deletion; does not block local deletion completion
        }
    }

    private fun deleteDirectoryContents(directory: File) {
        if (directory.exists() && directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    deleteDirectoryContents(file)
                }
                file.delete()
            }
        }
    }
}
