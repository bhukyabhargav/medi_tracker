package com.meditrack.app.domain.repository

import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun observeCurrentUser(): Flow<User?>
    fun observeIsLoggedIn(): Flow<Boolean>
    fun observeIsOnboardingCompleted(): Flow<Boolean>
    fun observeIsBatteryGuidanceSeen(): Flow<Boolean>

    suspend fun getCurrentUser(): User?
    suspend fun getUserByContact(contact: String): User?
    suspend fun createAccount(
        name: String,
        emailOrPhone: String,
        password: String,
        role: UserRole = UserRole.PATIENT
    ): Result<String> // Returns userId for OTP verification

    suspend fun verifyOtp(emailOrPhone: String, otp: String): Result<User>
    suspend fun login(emailOrPhone: String, password: String): Result<User>
    suspend fun logout()
    suspend fun deleteAccount(userId: String): Result<Unit>

    suspend fun setOnboardingCompleted()
    suspend fun setBatteryGuidanceSeen()
}
