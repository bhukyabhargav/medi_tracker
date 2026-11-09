package com.meditrack.app.data.repository

import com.meditrack.app.BuildConfig
import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
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
 */
@Singleton
class FakeAuthRepository @Inject constructor(
    private val userDao: UserDao,
    private val medicineDao: MedicineDao,
    private val doseEventDao: DoseEventDao,
    private val caregiverLinkDao: CaregiverLinkDao,
    private val userPreferences: UserPreferences
) : AuthRepository {

    private data class PendingAccount(
        val user: User,
        val passwordDigest: String,
        val otpCode: String
    )

    private val pendingAccounts = ConcurrentHashMap<String, PendingAccount>()

    companion object {
        const val DEBUG_OTP = "123456"
        private const val DEMO_SALT = "MediTrackLocalDemoSalt_2026"
    }

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

    override suspend fun getUserByContact(contact: String): User? {
        val (normalized, _) = normalizeContact(contact)
        return userDao.getUserByContact(normalized)
    }

    override suspend fun createAccount(
        name: String,
        emailOrPhone: String,
        password: String,
        role: UserRole
    ): Result<String> {
        if (!userPreferences.isPrivacyConsentGiven.firstOrNull().let { it == true }) {
            return Result.failure(IllegalStateException("Privacy consent must be granted before account creation or storing data."))
        }

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
            createdAt = System.currentTimeMillis()
        )

        val passwordDigest = hashPassword(password)
        val otpCode = if (BuildConfig.DEBUG) DEBUG_OTP else (100000..999999).random().toString()
        pendingAccounts[normalizedContact] = PendingAccount(
            user = user,
            passwordDigest = passwordDigest,
            otpCode = otpCode
        )

        return Result.success(userId)
    }

    override suspend fun verifyOtp(emailOrPhone: String, otp: String): Result<User> {
        val (normalizedContact, _) = normalizeContact(emailOrPhone)
        val pending = pendingAccounts[normalizedContact]
            ?: return Result.failure(IllegalStateException("No pending registration found for $emailOrPhone"))

        val expectedOtp = pending.otpCode
        val isOtpValid = otp.trim() == expectedOtp || (BuildConfig.DEBUG && otp.trim() == DEBUG_OTP)

        if (!isOtpValid) {
            return Result.failure(IllegalArgumentException("Invalid verification code."))
        }

        // Verified successfully: persist user to Room, store password digest, set session, and clean pending registration
        userDao.insertUser(pending.user)
        userPreferences.saveUserPasswordDigest(pending.user.id, pending.passwordDigest)
        userPreferences.setLoggedIn(pending.user.id)
        repairLegacyPatientData(pending.user.id)
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
        repairLegacyPatientData(existingUser.id)
        return Result.success(existingUser)
    }

    override suspend fun logout() {
        // Logging out must NOT delete the user's clinical history (offline-first app, and the
        // foreign key dose_events.medicineId is RESTRICT). Account isolation is enforced by
        // filtering every read/write on patientId (see UserPreferences.currentUserId).
        userPreferences.clearSession()
    }

    override suspend fun setOnboardingCompleted() {
        userPreferences.setOnboardingCompleted(true)
    }

    override suspend fun setBatteryGuidanceSeen() {
        userPreferences.setBatteryGuidanceSeen(true)
    }

    override suspend fun loginAsDemo(role: UserRole): Result<User> {
        userPreferences.setPrivacyConsentGiven(true)
        val demoId = when (role) {
            UserRole.PATIENT -> "demo_patient_001"
            UserRole.DOCTOR -> "demo_doctor_001"
            UserRole.CAREGIVER -> "demo_caregiver_001"
        }
        val demoName = when (role) {
            UserRole.PATIENT -> "Demo Patient"
            UserRole.DOCTOR -> "Dr. Smith (Demo)"
            UserRole.CAREGIVER -> "Sarah (Caregiver Demo)"
        }
        val demoEmail = "${role.name.lowercase()}.demo@meditrack.app"
        val user = User(
            id = demoId,
            name = demoName,
            email = demoEmail,
            role = role,
            createdAt = System.currentTimeMillis()
        )
        userDao.insertUser(user)
        userPreferences.setLoggedIn(demoId)
        repairLegacyPatientData(demoId)
        userPreferences.setOnboardingCompleted(true)
        userPreferences.setBatteryGuidanceSeen(true)
        return Result.success(user)
    }

    private fun normalizeContact(raw: String): Pair<String, Boolean> {
        val trimmed = raw.trim()
        val isEmail = trimmed.contains("@")
        return if (isEmail) {
            trimmed.lowercase() to true
        } else {
            val cleanPhone = trimmed.replace(" ", "").replace("-", "")
            cleanPhone to false
        }
    }

    private fun hashPassword(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((DEMO_SALT + password).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun repairLegacyPatientData(userId: String) {
        try {
            medicineDao.claimLegacyMedicines(userId)
            doseEventDao.claimLegacyDoseEvents(userId)
        } catch (_: Exception) {}
    }

}
