package com.meditrack.app.feature.onboarding

import com.meditrack.app.core.navigation.Screen
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.repository.AuthRepository
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Default consent given
        coEvery { userPreferences.isPrivacyConsentGiven } returns flowOf(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `navigates to PrivacyConsent when consent has not been given on first run`() = runTest(testDispatcher) {
        coEvery { userPreferences.isPrivacyConsentGiven } returns flowOf(false)

        val viewModel = SplashViewModel(authRepository, userPreferences)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SplashNavigationTarget.Navigate(Screen.PrivacyConsent.route),
            viewModel.navigationTarget.value
        )
    }

    @Test
    fun `navigates to Login when consent given but user is not logged in`() = runTest(testDispatcher) {
        coEvery { userPreferences.isPrivacyConsentGiven } returns flowOf(true)
        coEvery { authRepository.observeIsLoggedIn() } returns flowOf(false)

        val viewModel = SplashViewModel(authRepository, userPreferences)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SplashNavigationTarget.Navigate(Screen.Login.route),
            viewModel.navigationTarget.value
        )
    }

    @Test
    fun `navigates to PermissionSetup when logged in but onboarding incomplete`() = runTest(testDispatcher) {
        coEvery { userPreferences.isPrivacyConsentGiven } returns flowOf(true)
        coEvery { authRepository.observeIsLoggedIn() } returns flowOf(true)
        coEvery { authRepository.observeIsOnboardingCompleted() } returns flowOf(false)

        val viewModel = SplashViewModel(authRepository, userPreferences)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SplashNavigationTarget.Navigate(Screen.PermissionSetup.route),
            viewModel.navigationTarget.value
        )
    }

    @Test
    fun `navigates to BatteryGuidance when logged in and onboarded but battery unseen`() = runTest(testDispatcher) {
        coEvery { userPreferences.isPrivacyConsentGiven } returns flowOf(true)
        coEvery { authRepository.observeIsLoggedIn() } returns flowOf(true)
        coEvery { authRepository.observeIsOnboardingCompleted() } returns flowOf(true)
        coEvery { authRepository.observeIsBatteryGuidanceSeen() } returns flowOf(false)

        val viewModel = SplashViewModel(authRepository, userPreferences)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SplashNavigationTarget.Navigate(Screen.BatteryGuidance.route),
            viewModel.navigationTarget.value
        )
    }

    @Test
    fun `navigates to Home when logged in and all onboarding and battery steps completed`() = runTest(testDispatcher) {
        coEvery { userPreferences.isPrivacyConsentGiven } returns flowOf(true)
        coEvery { authRepository.observeIsLoggedIn() } returns flowOf(true)
        coEvery { authRepository.observeIsOnboardingCompleted() } returns flowOf(true)
        coEvery { authRepository.observeIsBatteryGuidanceSeen() } returns flowOf(true)

        val viewModel = SplashViewModel(authRepository, userPreferences)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SplashNavigationTarget.Navigate(Screen.Home.route),
            viewModel.navigationTarget.value
        )
    }
}
