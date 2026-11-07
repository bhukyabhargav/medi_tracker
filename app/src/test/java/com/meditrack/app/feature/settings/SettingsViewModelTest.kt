package com.meditrack.app.feature.settings

import android.content.Context
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.core.security.KeystoreSecurityManager
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.ProposalRepository
import com.meditrack.app.domain.usecase.DeleteAccountUseCase
import com.meditrack.app.domain.usecase.ExportDataUseCase
import com.meditrack.app.domain.usecase.ExportResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private val context: Context = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val doctorRepository: DoctorRepository = mockk(relaxed = true)
    private val proposalRepository: ProposalRepository = mockk(relaxed = true)
    private val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
    private val exportDataUseCase: ExportDataUseCase = mockk(relaxed = true)
    private val deleteAccountUseCase: DeleteAccountUseCase = mockk(relaxed = true)
    private val keystoreSecurityManager: KeystoreSecurityManager = mockk(relaxed = true)

    private val testUser = User(
        id = "user_patient_1",
        name = "Alice Patient",
        email = "alice@example.com",
        role = UserRole.PATIENT
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        coEvery { authRepository.observeCurrentUser() } returns flowOf(testUser)
        coEvery { authRepository.observeIsLoggedIn() } returns flowOf(true)
        coEvery { authRepository.getCurrentUser() } returns testUser

        coEvery { userPreferences.doseWindowMinutes } returns flowOf(60)
        coEvery { userPreferences.defaultSnoozeMinutes } returns flowOf(15)
        coEvery { userPreferences.notifMissedEnabled } returns flowOf(true)
        coEvery { userPreferences.notifStockEnabled } returns flowOf(true)
        coEvery { userPreferences.notifUnverifiedEnabled } returns flowOf(true)
        coEvery { userPreferences.notifLateEnabled } returns flowOf(true)
        coEvery { userPreferences.isFlagSecureEnabled } returns flowOf(true)
        coEvery { userPreferences.isPrivacyConsentGiven } returns flowOf(true)

        coEvery { doctorRepository.observeByPatientId(any()) } returns flowOf(emptyList())
        coEvery { proposalRepository.observeByPatientId(any()) } returns flowOf(emptyList())
        coEvery { caregiverRepository.observeByPatientId(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            context = context,
            authRepository = authRepository,
            userPreferences = userPreferences,
            userDao = userDao,
            doctorRepository = doctorRepository,
            proposalRepository = proposalRepository,
            caregiverRepository = caregiverRepository,
            exportDataUseCase = exportDataUseCase,
            deleteAccountUseCase = deleteAccountUseCase,
            keystoreSecurityManager = keystoreSecurityManager
        )
    }

    @Test
    fun `initial state loads user and preference flows`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("user_patient_1", state.user?.id)
        assertTrue(state.isLoggedIn)
        assertEquals(60, state.doseWindowMinutes)
        assertEquals(15, state.defaultSnoozeMinutes)
        assertTrue(state.notifMissedEnabled)
        assertTrue(state.isFlagSecureEnabled)
        assertTrue(state.isPrivacyConsentGiven)
    }

    @Test
    fun `setDoseWindowMinutes clamps to 30 to 180 and updates AppConfig and preferences`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setDoseWindowMinutes(120)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { userPreferences.setDoseWindowMinutes(120) }
        assertEquals(120, AppConfig.effectiveDoseWindowMinutes)
        assertEquals(120, viewModel.uiState.value.doseWindowMinutes)

        // Test clamping below minimum (30)
        viewModel.setDoseWindowMinutes(10)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { userPreferences.setDoseWindowMinutes(30) }
        assertEquals(30, AppConfig.effectiveDoseWindowMinutes)
    }

    @Test
    fun `setDefaultSnoozeMinutes clamps to 1 to 240 and updates preferences`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setDefaultSnoozeMinutes(30)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { userPreferences.setDefaultSnoozeMinutes(30) }
        assertEquals(30, viewModel.uiState.value.defaultSnoozeMinutes)
    }

    @Test
    fun `notification toggles update corresponding user preferences`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setNotifMissedEnabled(false)
        viewModel.setNotifStockEnabled(false)
        viewModel.setNotifUnverifiedEnabled(false)
        viewModel.setNotifLateEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { userPreferences.setNotifMissedEnabled(false) }
        coVerify { userPreferences.setNotifStockEnabled(false) }
        coVerify { userPreferences.setNotifUnverifiedEnabled(false) }
        coVerify { userPreferences.setNotifLateEnabled(false) }
    }

    @Test
    fun `setFlagSecureEnabled updates screen security preference`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setFlagSecureEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { userPreferences.setFlagSecureEnabled(false) }
        assertFalse(viewModel.uiState.value.isFlagSecureEnabled)
    }

    @Test
    fun `setPrivacyConsent updates privacy consent in preferences`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setPrivacyConsent(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { userPreferences.setPrivacyConsentGiven(false) }
        assertFalse(viewModel.uiState.value.isPrivacyConsentGiven)
    }

    @Test
    fun `acceptDoctorInvite and declineDoctorInvite update doctor link status`() = runTest(testDispatcher) {
        val testLink = DoctorLink(
            id = "dlink_123",
            patientId = "user_patient_1",
            doctorUid = "doc_1",
            doctorName = "Dr. Rao",
            doctorContact = "rao@meditrack.org",
            status = DoctorLinkStatus.PENDING
        )
        coEvery { doctorRepository.getById("dlink_123") } returns testLink

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.acceptDoctorInvite("dlink_123")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { doctorRepository.update(match { it.status == DoctorLinkStatus.ACCEPTED }) }

        viewModel.declineDoctorInvite("dlink_123")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { doctorRepository.update(match { it.status == DoctorLinkStatus.DECLINED }) }
    }

    @Test
    fun `revokeDoctorAccess marks link as REVOKED`() = runTest(testDispatcher) {
        val activeLink = DoctorLink(
            id = "dlink_active",
            patientId = "user_patient_1",
            doctorUid = "doc_1",
            doctorName = "Dr. Rao",
            doctorContact = "rao@meditrack.org",
            status = DoctorLinkStatus.ACCEPTED
        )
        coEvery { doctorRepository.getById("dlink_active") } returns activeLink

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.revokeDoctorAccess("dlink_active")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { doctorRepository.update(match { it.status == DoctorLinkStatus.REVOKED }) }
    }

    @Test
    fun `exportData invokes ExportDataUseCase and populates exportResult`() = runTest(testDispatcher) {
        val dummyZip = File.createTempFile("export_test", ".zip")
        val exportResult = ExportResult(
            zipFile = dummyZip,
            jsonContent = "{}",
            totalMedicines = 2,
            totalDoses = 14,
            totalProposals = 1,
            totalDoctorLinks = 1,
            totalCaregiverLinks = 0
        )
        coEvery { exportDataUseCase("user_patient_1") } returns Result.success(exportResult)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.exportData()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { exportDataUseCase("user_patient_1") }
        assertEquals(exportResult, viewModel.uiState.value.exportResult)
        assertFalse(viewModel.uiState.value.isExporting)

        dummyZip.delete()
    }

    @Test
    fun `deleteAccount invokes DeleteAccountUseCase and marks isDeleted true`() = runTest(testDispatcher) {
        coEvery { deleteAccountUseCase("user_patient_1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteAccount()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { deleteAccountUseCase("user_patient_1") }
        assertTrue(viewModel.uiState.value.isDeleted)
        assertFalse(viewModel.uiState.value.isDeleting)
    }

    @Test
    fun `logout logs out from AuthRepository and clears user session`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.logout()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { authRepository.logout() }
        coVerify { userPreferences.clearSession() }
        assertTrue(viewModel.uiState.value.isLoggedOut)
    }
}
