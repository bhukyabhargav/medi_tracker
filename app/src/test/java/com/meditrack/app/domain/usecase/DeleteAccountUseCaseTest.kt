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
import com.meditrack.app.data.service.CloudDeletionResult
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountUseCaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val medicineDao: MedicineDao = mockk(relaxed = true)
    private val doseEventDao: DoseEventDao = mockk(relaxed = true)
    private val caregiverLinkDao: CaregiverLinkDao = mockk(relaxed = true)
    private val doctorLinkDao: DoctorLinkDao = mockk(relaxed = true)
    private val medicineProposalDao: MedicineProposalDao = mockk(relaxed = true)
    private val verificationAttemptDao: VerificationAttemptDao = mockk(relaxed = true)
    private val notificationOutboxDao: NotificationOutboxDao = mockk(relaxed = true)
    private val cloudDeleter: AccountCloudDeleter = mockk()

    private lateinit var useCase: DeleteAccountUseCase
    private lateinit var testFilesDir: File
    private lateinit var testCacheDir: File

    private val testUser = User(
        id = "patient_del_1",
        name = "Charlie Patient",
        role = UserRole.PATIENT
    )

    @Before
    fun setUp() {
        testFilesDir = tempFolder.newFolder("files")
        testCacheDir = tempFolder.newFolder("cache")

        coEvery { context.filesDir } returns testFilesDir
        coEvery { context.cacheDir } returns testCacheDir
        coEvery { authRepository.getCurrentUser() } returns testUser

        // Default successful cloud deletion
        coEvery { cloudDeleter.deleteAccountData() } returns CloudDeletionResult(
            success = true,
            deletedItems = listOf("users/patient_del_1", "caregiverLinks", "doctorLinks")
        )

        useCase = DeleteAccountUseCase(
            context = context,
            authRepository = authRepository,
            userPreferences = userPreferences,
            userDao = userDao,
            medicineDao = medicineDao,
            doseEventDao = doseEventDao,
            caregiverLinkDao = caregiverLinkDao,
            doctorLinkDao = doctorLinkDao,
            medicineProposalDao = medicineProposalDao,
            verificationAttemptDao = verificationAttemptDao,
            notificationOutboxDao = notificationOutboxDao,
            cloudDeleter = cloudDeleter
        )
    }

    @Test
    fun `invoke deletes all local records for the patient across all DAOs on cloud success`() = runTest {
        val result = useCase("patient_del_1")

        assertTrue(result.isSuccess)
        coVerify { cloudDeleter.deleteAccountData() }
        coVerify { doseEventDao.deleteAllByPatientId("patient_del_1") }
        coVerify { verificationAttemptDao.deleteAllByPatientId("patient_del_1") }
        coVerify { medicineDao.deleteAllByPatientId("patient_del_1") }
        coVerify { caregiverLinkDao.deleteAllByPatientId("patient_del_1") }
        coVerify { doctorLinkDao.deleteAllByPatientId("patient_del_1") }
        coVerify { medicineProposalDao.deleteAllByPatientId("patient_del_1") }
        coVerify { notificationOutboxDao.clearAll() }
        coVerify { userDao.clearUsers() }
    }

    @Test
    fun `invoke deletes local app-private directories and files on cloud success`() = runTest {
        val proofsDir = File(testFilesDir, "proofs").apply { mkdirs() }
        val dummyProof = File(proofsDir, "proof1.jpg").apply { writeText("dummy proof") }

        val enrollmentDir = File(testFilesDir, "enrollment").apply { mkdirs() }
        val dummyBaseline = File(enrollmentDir, "base1.jpg").apply { writeText("dummy baseline") }

        val scanTmpDir = File(testFilesDir, "scan_tmp").apply { mkdirs() }
        val dummyScan = File(scanTmpDir, "scan_prescription.jpg").apply { writeText("dummy scan rx") }

        val exportsDir = File(testCacheDir, "exports").apply { mkdirs() }
        val dummyExport = File(exportsDir, "archive.zip").apply { writeText("dummy export") }

        assertTrue(dummyProof.exists())
        assertTrue(dummyBaseline.exists())
        assertTrue(dummyScan.exists())
        assertTrue(dummyExport.exists())

        val result = useCase("patient_del_1")
        assertTrue(result.isSuccess)

        assertFalse(dummyProof.exists())
        assertFalse(dummyBaseline.exists())
        assertFalse(dummyScan.exists())
        assertFalse(dummyExport.exists())
    }

    @Test
    fun `invoke resets preferences and logs out the user`() = runTest {
        val result = useCase("patient_del_1")

        assertTrue(result.isSuccess)
        coVerify { userPreferences.resetAll() }
        coVerify { authRepository.logout() }
    }

    @Test
    fun `cloud partial failure halts deletion without purging local data`() = runTest {
        coEvery { cloudDeleter.deleteAccountData() } returns CloudDeletionResult(
            success = false,
            deletedItems = listOf("caregiverLinks"),
            errors = listOf("Permission denied deleting doctorLinks")
        )

        val result = useCase("patient_del_1")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is CloudDeletionException)
        val cde = ex as CloudDeletionException
        assertEquals(1, cde.remainingErrors.size)
        assertTrue(cde.remainingErrors.first().contains("doctorLinks"))

        // Local data MUST NOT be wiped when remote cloud deletion has failed!
        coVerify(exactly = 0) { userDao.clearUsers() }
        coVerify(exactly = 0) { userPreferences.resetAll() }
        coVerify(exactly = 0) { authRepository.logout() }
    }

    @Test
    fun `cloud reauth required halts deletion with RecentLoginRequiredException without wiping local data`() = runTest {
        coEvery { cloudDeleter.deleteAccountData() } returns CloudDeletionResult(
            success = false,
            requiresReauth = true,
            errors = listOf("Recent login required to delete Firebase Auth user")
        )

        val result = useCase("patient_del_1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RecentLoginRequiredException)

        coVerify(exactly = 0) { userDao.clearUsers() }
        coVerify(exactly = 0) { userPreferences.resetAll() }
    }

    @Test
    fun `cloud offline error allows user to choose forceLocalOnly deletion`() = runTest {
        coEvery { cloudDeleter.deleteAccountData() } returns CloudDeletionResult(
            success = false,
            errors = listOf("Network timeout connecting to Cloud Functions")
        )

        // Without forceLocalOnly -> fails, leaves local data intact
        val failResult = useCase("patient_del_1", forceLocalOnly = false)
        assertTrue(failResult.isFailure)
        coVerify(exactly = 0) { userDao.clearUsers() }

        // With forceLocalOnly -> succeeds and purges local data
        val successResult = useCase("patient_del_1", forceLocalOnly = true)
        assertTrue(successResult.isSuccess)
        coVerify(exactly = 1) { userDao.clearUsers() }
        coVerify(exactly = 1) { userPreferences.resetAll() }
    }
}
