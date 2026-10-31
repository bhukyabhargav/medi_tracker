package com.meditrack.app.data.repository

import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic local fake authentication repository for development and offline testing.
 *
 * NOTE: Credentials hashing here is a demo-only SHA-256 digest scoped for local offline-first
 * verification and development. Real backend authentication and multi-factor authentication
 * will be provided by Firebase Auth in Stage 7 and Stage 10.
 */
@Singleton
class FakeAuthRepository @Inject constructor(
    private val userDao: UserDao,
    private val userPreferences: UserPreferences
) : AuthRepository {

    private data class PendingAccount(
        val user: User,
        val passwordDigest: String,
        val otpCode: String = DEBUG_OTP
    )

    private val pendingAccounts = ConcurrentHashMap<String, PendingAccount>()

    companion object {
        const val DEBUG_OTP = "123456"
        private const val DEMO_SALT = "MediTrackLocalDemoSalt_2026"
    }

    /**
     * Reactively observes the user currently logged in according to [UserPreferences.currentUserId].
     * If logged out or session is empty, emits null.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCurrentUser(): Flow<User?> {
        return userPreferences.currentUserId.flatMapLatest { userId ->
            if (userId.isNullOrEmpty()) {
                flowOf(null)
            } else {
                userDao.observeUserById(userId)
            }
        }
    }

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
        val (normalizedContact, isEmail) = normalizeContact(emailOrPhone)
        if (normalizedContact.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email or phone number cannot be empty"))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters"))
        }

        // Check if an account already exists in Room for this contact
        val existingUser = userDao.getUserByContact(normalizedContact)
        if (existingUser != null) {
            return Result.failure(IllegalArgumentException("An account with this email or phone number already exists"))
        }

        // Check if a registration is already pending for this contact
        if (pendingAccounts.containsKey(normalizedContact)) {
            return Result.failure(IllegalStateException("Registration is already pending verification for this contact"))
        }

        val userId = UUID.randomUUID().toString()
        val user = User(
            id = userId,
            name = name.trim(),
            email = if (isEmail) normalizedContact else null,
            phone = if (!isEmail) normalizedContact else null,
            role = role,
            doctorRegistrationNo = if (role == UserRole.DOCTOR) doctorRegistrationNo?.trim() else null
        )

        val passwordDigest = hashPassword(password)
        pendingAccounts[normalizedContact] = PendingAccount(
            user = user,
            passwordDigest = passwordDigest,
            otpCode = DEBUG_OTP
        )

        return Result.success(userId)
    }

    override suspend fun verifyOtp(emailOrPhone: String, otp: String): Result<User> {
        val (normalizedContact, _) = normalizeContact(emailOrPhone)
        val pending = pendingAccounts[normalizedContact]
            ?: return Result.failure(IllegalStateException("No pending registration found for $emailOrPhone"))

        if (otp.trim() != DEBUG_OTP && otp.trim() != pending.otpCode) {
            return Result.failure(IllegalArgumentException("Invalid verification code. Enter $DEBUG_OTP"))
        }

        // Verified successfully: persist user to Room, store password digest, set session, and clean pending registration
        userDao.insertUser(pending.user)
        userPreferences.saveUserPasswordDigest(pending.user.id, pending.passwordDigest)
        userPreferences.setLoggedIn(pending.user.id)
        pendingAccounts.remove(normalizedContact)

        return Result.success(pending.user)
    }

    override suspend fun login(emailOrPhone: String, password: String): Result<User> {
        val (normalizedContact, _) = normalizeContact(emailOrPhone)
        if (normalizedContact.isEmpty() || password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Contact and password are required"))
        }

        // Look up registered user by contact in Room
        val existingUser = userDao.getUserByContact(normalizedContact)
            ?: return Result.failure(IllegalArgumentException("Account not found for $emailOrPhone"))

        // Validate password against stored digest
        val storedDigest = userPreferences.getUserPasswordDigest(existingUser.id).firstOrNull()
        val enteredDigest = hashPassword(password)

        if (storedDigest != enteredDigest) {
            return Result.failure(IllegalArgumentException("Incorrect password"))
        }

        userPreferences.setLoggedIn(existingUser.id)
        return Result.success(existingUser)
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

    private fun normalizeContact(raw: String): Pair<String, Boolean> {
        val trimmed = raw.trim()
        val isEmail = trimmed.contains("@")
        return if (isEmail) {
            trimmed.lowercase() to true
        } else {
            // Strip whitespace and dashes from phone while keeping digits and '+'
            val cleanPhone = trimmed.replace(" ", "").replace("-", "")
            cleanPhone to false
        }
    }

    private fun hashPassword(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((DEMO_SALT + password).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
