package com.meditrack.app.feature.auth

import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private lateinit var viewModel: AuthViewModel

    private val sampleUser = User(
        id = "user_1",
        name = "Test User",
        email = "test@example.com",
        phone = null,
        role = UserRole.PATIENT
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { authRepository.observeCurrentUser() } returns flowOf(null)
        coEvery { authRepository.observeIsLoggedIn() } returns flowOf(false)
        viewModel = AuthViewModel(authRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `login fails when contact is blank`() = runTest(testDispatcher) {
        var successCalled = false
        viewModel.onContactChange("   ")
        viewModel.onPasswordChange("validpass123")

        viewModel.login(onSuccess = { successCalled = true })

        assertFalse(successCalled)
        assertEquals("Please enter your email or phone number", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `login fails when password is blank`() = runTest(testDispatcher) {
        var successCalled = false
        viewModel.onContactChange("test@example.com")
        viewModel.onPasswordChange("   ")

        viewModel.login(onSuccess = { successCalled = true })

        assertFalse(successCalled)
        assertEquals("Please enter your password", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `login succeeds with valid credentials`() = runTest(testDispatcher) {
        var successCalled = false
        viewModel.onContactChange("test@example.com")
        viewModel.onPasswordChange("password123")

        coEvery { authRepository.login("test@example.com", "password123") } returns Result.success(sampleUser)

        viewModel.login(onSuccess = { successCalled = true })
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(successCalled)
        assertTrue(viewModel.uiState.value.isSuccess)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `login displays error on repository failure`() = runTest(testDispatcher) {
        var successCalled = false
        viewModel.onContactChange("test@example.com")
        viewModel.onPasswordChange("wrongpassword")

        coEvery {
            authRepository.login("test@example.com", "wrongpassword")
        } returns Result.failure(IllegalArgumentException("Invalid contact or password"))

        viewModel.login(onSuccess = { successCalled = true })
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(successCalled)
        assertFalse(viewModel.uiState.value.isSuccess)
        assertEquals("Invalid contact or password", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `registration fails when name is blank`() = runTest(testDispatcher) {
        var otpRouteContact: String? = null
        viewModel.onNameChange("")
        viewModel.onContactChange("patient@test.com")
        viewModel.onPasswordChange("password123")

        viewModel.createAccount(onNavigateToOtp = { otpRouteContact = it })

        assertNull(otpRouteContact)
        assertEquals("Please enter your name", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `registration fails when password length is less than 6`() = runTest(testDispatcher) {
        var otpRouteContact: String? = null
        viewModel.onNameChange("Valid Name")
        viewModel.onContactChange("patient@test.com")
        viewModel.onPasswordChange("12345")

        viewModel.createAccount(onNavigateToOtp = { otpRouteContact = it })

        assertNull(otpRouteContact)
        assertEquals("Password must be at least 6 characters", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `registration fails for doctor without registration number`() = runTest(testDispatcher) {
        var otpRouteContact: String? = null
        viewModel.onNameChange("Dr. Smith")
        viewModel.onContactChange("doctor@test.com")
        viewModel.onPasswordChange("doctorpass")
        viewModel.onRoleChange(UserRole.DOCTOR)
        viewModel.onDoctorRegNoChange("")

        viewModel.createAccount(onNavigateToOtp = { otpRouteContact = it })

        assertNull(otpRouteContact)
        assertEquals("Please enter your Doctor Registration Number", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `registration succeeds and invokes onNavigateToOtp`() = runTest(testDispatcher) {
        var otpRouteContact: String? = null
        viewModel.onNameChange("Alice Caregiver")
        viewModel.onContactChange("caregiver@test.com")
        viewModel.onPasswordChange("securepassword")
        viewModel.onRoleChange(UserRole.CAREGIVER)

        coEvery {
            authRepository.createAccount(
                name = "Alice Caregiver",
                emailOrPhone = "caregiver@test.com",
                password = "securepassword",
                role = UserRole.CAREGIVER,
                doctorRegistrationNo = null
            )
        } returns Result.success("user_1")

        viewModel.createAccount(onNavigateToOtp = { otpRouteContact = it })
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("caregiver@test.com", otpRouteContact)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `verifyOtp fails when otp length is not 6`() = runTest(testDispatcher) {
        var successCalled = false
        viewModel.onOtpChange("123")

        viewModel.verifyOtp("caregiver@test.com", onSuccess = { successCalled = true })

        assertFalse(successCalled)
        assertEquals("Please enter the 6-digit verification code", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `verifyOtp succeeds when repository accepts code`() = runTest(testDispatcher) {
        var successCalled = false
        viewModel.onOtpChange("123456")

        coEvery {
            authRepository.verifyOtp("caregiver@test.com", "123456")
        } returns Result.success(sampleUser)

        viewModel.verifyOtp("caregiver@test.com", onSuccess = { successCalled = true })
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(successCalled)
        assertTrue(viewModel.uiState.value.isSuccess)
    }

    @Test
    fun `verifyOtp shows error when code is rejected`() = runTest(testDispatcher) {
        var successCalled = false
        viewModel.onOtpChange("000000")

        coEvery {
            authRepository.verifyOtp("caregiver@test.com", "000000")
        } returns Result.failure(IllegalArgumentException("Invalid verification code"))

        viewModel.verifyOtp("caregiver@test.com", onSuccess = { successCalled = true })
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(successCalled)
        assertEquals("Invalid verification code", viewModel.uiState.value.errorMessage)
    }
}
