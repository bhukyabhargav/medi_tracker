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
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
            notificationOutboxDao = notificationOutboxDao
        )
    }

    @Test
    fun `invoke deletes all local records for the patient across all DAOs`() = runTest {
        val result = useCase("patient_del_1")

        assertTrue(result.isSuccess)
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
    fun `invoke deletes local app-private directories and files`() = runTest {
        val proofsDir = File(testFilesDir, "proofs").apply { mkdirs() }
        val dummyProof = File(proofsDir, "proof1.jpg").apply { writeText("dummy proof") }

        val enrollmentDir = File(testFilesDir, "enrollment").apply { mkdirs() }
        val dummyBaseline = File(enrollmentDir, "base1.jpg").apply { writeText("dummy baseline") }

        val exportsDir = File(testCacheDir, "exports").apply { mkdirs() }
        val dummyExport = File(exportsDir, "archive.zip").apply { writeText("dummy export") }

        assertTrue(dummyProof.exists())
        assertTrue(dummyBaseline.exists())
        assertTrue(dummyExport.exists())

        val result = useCase("patient_del_1")
        assertTrue(result.isSuccess)

        assertFalse(dummyProof.exists())
        assertFalse(dummyBaseline.exists())
        assertFalse(dummyExport.exists())
    }

    @Test
    fun `invoke resets preferences and logs out the user`() = runTest {
        val result = useCase("patient_del_1")

        assertTrue(result.isSuccess)
        coVerify { userPreferences.resetAll() }
        coVerify { authRepository.logout() }
    }
}
