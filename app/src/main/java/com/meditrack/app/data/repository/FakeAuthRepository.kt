package com.meditrack.app.data.repository

import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeAuthRepository @Inject constructor(
    private val userDao: UserDao,
    private val userPreferences: UserPreferences
) : AuthRepository {

    private data class PendingAccount(
        val user: User,
        val passwordHash: String,
        val otpCode: String = DEBUG_OTP
    )

    private val pendingAccounts = ConcurrentHashMap<String, PendingAccount>()

    companion object {
        const val DEBUG_OTP = "123456"
    }

    override fun observeCurrentUser(): Flow<User?> = userDao.observeCurrentUser()

    override fun observeIsLoggedIn(): Flow<Boolean> = userPreferences.isLoggedIn

    override fun observeIsOnboardingCompleted(): Flow<Boolean> = userPreferences.isOnboardingCompleted

    override fun observeIsBatteryGuidanceSeen(): Flow<Boolean> = userPreferences.isBatteryGuidanceSeen

    override suspend fun getCurrentUser(): User? {
        val userId = userPreferences.currentUserId.firstOrNull() ?: return null
        return userDao.getUserById(userId)
    }

    override suspend fun createAccount(
        name: String,
        emailOrPhone: String,
        password: String,
        role: UserRole,
        doctorRegistrationNo: String?
    ): Result<String> {
        val cleanContact = emailOrPhone.trim()
        if (cleanContact.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email or phone number cannot be empty"))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters"))
        }

        val userId = UUID.randomUUID().toString()
        val isEmail = cleanContact.contains("@")
        val user = User(
            id = userId,
            name = name.trim(),
            email = if (isEmail) cleanContact else null,
            phone = if (!isEmail) cleanContact else null,
            role = role,
            doctorRegistrationNo = if (role == UserRole.DOCTOR) doctorRegistrationNo else null
        )

        val pending = PendingAccount(user = user, passwordHash = password)
        pendingAccounts[cleanContact] = pending

        return Result.success(userId)
    }

    override suspend fun verifyOtp(emailOrPhone: String, otp: String): Result<User> {
        val cleanContact = emailOrPhone.trim()
        val pending = pendingAccounts[cleanContact]
            ?: return Result.failure(IllegalStateException("No pending registration found for $cleanContact"))

        if (otp != DEBUG_OTP && otp != pending.otpCode) {
            return Result.failure(IllegalArgumentException("Invalid verification code. Enter $DEBUG_OTP"))
        }

        userDao.insertUser(pending.user)
        userPreferences.setLoggedIn(pending.user.id)
        pendingAccounts.remove(cleanContact)

        return Result.success(pending.user)
    }

    override suspend fun login(emailOrPhone: String, password: String): Result<User> {
        val cleanContact = emailOrPhone.trim()
        if (cleanContact.isEmpty() || password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Contact and password are required"))
        }

        // Check if user exists in Room database
        val existingUser = userDao.observeCurrentUser().firstOrNull()
        if (existingUser != null) {
            val matches = (existingUser.email?.equals(cleanContact, ignoreCase = true) == true) ||
                    (existingUser.phone == cleanContact)
            if (matches) {
                userPreferences.setLoggedIn(existingUser.id)
                return Result.success(existingUser)
            }
        }

        // Demo / local test login support
        val isEmail = cleanContact.contains("@")
        val fallbackUser = User(
            id = UUID.randomUUID().toString(),
            name = cleanContact.substringBefore("@").replaceFirstChar { it.uppercase() },
            email = if (isEmail) cleanContact else null,
            phone = if (!isEmail) cleanContact else null,
            role = UserRole.PATIENT
        )
        userDao.insertUser(fallbackUser)
        userPreferences.setLoggedIn(fallbackUser.id)
        return Result.success(fallbackUser)
    }

    override suspend fun logout() {
        userPreferences.clearSession()
    }

    override suspend fun setOnboardingCompleted() {
        userPreferences.setOnboardingCompleted(true)
    }

    override suspend fun setBatteryGuidanceSeen() {
        userPreferences.setBatteryGuidanceSeen(true)
    }
}
