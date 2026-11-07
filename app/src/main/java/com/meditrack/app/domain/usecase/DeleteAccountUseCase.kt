package com.meditrack.app.domain.usecase

import android.content.Context
import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoctorLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.db.dao.MedicineProposalDao
import com.meditrack.app.data.db.dao.NotificationOutboxDao
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.db.dao.VerificationAttemptDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.data.service.AccountCloudDeleter
import com.meditrack.app.domain.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

class RecentLoginRequiredException(
    message: String = "Re-authentication is required before deleting your account for security."
) : Exception(message)

class CloudDeletionException(
    message: String,
    val deletedItems: List<String> = emptyList(),
    val remainingErrors: List<String> = emptyList()
) : Exception(message)

/**
 * Stage 12: Account & Clinical Data Deletion Use Case
 *
 * Implements cascading data deletion across remote Firebase (via callable Cloud Function
 * deleteAccountData with Admin SDK privileges) and local storage (Room, app-private files,
 * DataStore preferences).
 *
 * Rules:
 * - If cloud deletion fails or requires re-authentication, local data is NOT wiped unless
 *   forceLocalOnly is explicitly requested.
 * - When cloud deletion succeeds, local DAOs and private directories are completely purged,
 *   preferences are reset, and user session is terminated.
 */
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
    private val notificationOutboxDao: NotificationOutboxDao,
    private val cloudDeleter: AccountCloudDeleter
) {

    suspend operator fun invoke(
        targetUserId: String? = null,
        forceLocalOnly: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentUser = authRepository.getCurrentUser()
            val userId = targetUserId ?: currentUser?.id ?: "unknown_user"

            // 1. Unless forceLocalOnly is requested, execute remote cloud deletion first
            if (!forceLocalOnly) {
                val cloudResult = cloudDeleter.deleteAccountData()

                if (cloudResult.requiresReauth) {
                    return@withContext Result.failure(RecentLoginRequiredException())
                }

                if (!cloudResult.success || cloudResult.errors.isNotEmpty()) {
                    val errorSummary = "Cloud deletion failed: ${cloudResult.errors.joinToString("; ")}"
                    return@withContext Result.failure(
                        CloudDeletionException(
                            message = errorSummary,
                            deletedItems = cloudResult.deletedItems,
                            remainingErrors = cloudResult.errors
                        )
                    )
                }
            }

            // 2. Cloud deletion succeeded or forceLocalOnly requested: purge local database records
            doseEventDao.deleteAllByPatientId(userId)
            verificationAttemptDao.deleteAllByPatientId(userId)
            medicineDao.deleteAllByPatientId(userId)
            caregiverLinkDao.deleteAllByPatientId(userId)
            doctorLinkDao.deleteAllByPatientId(userId)
            medicineProposalDao.deleteAllByPatientId(userId)
            notificationOutboxDao.clearAll()
            userDao.clearUsers()

            // 3. Purge local app-private files (proofs, baselines, cache exports)
            deleteDirectoryContents(File(context.filesDir, "proofs"))
            deleteDirectoryContents(File(context.filesDir, "enrollment"))
            deleteDirectoryContents(File(context.filesDir, "dataset"))
            deleteDirectoryContents(File(context.cacheDir, "exports"))

            // 4. Wipe all local preferences, session flags, and log out
            userPreferences.resetAll()
            authRepository.logout()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
