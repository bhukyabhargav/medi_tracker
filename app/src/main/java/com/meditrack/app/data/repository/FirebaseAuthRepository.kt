package com.meditrack.app.data.repository

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userDao: UserDao,
    private val userPreferences: UserPreferences,
    private val fakeAuthRepository: FakeAuthRepository
) : AuthRepository {

    private val isFirebaseAvailable: Boolean
        get() = try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }

    private val firebaseAuth: FirebaseAuth?
        get() = if (isFirebaseAvailable) FirebaseAuth.getInstance() else null

    private val firestore: FirebaseFirestore?
        get() = if (isFirebaseAvailable) FirebaseFirestore.getInstance() else null

    override fun observeCurrentUser(): Flow<User?> = fakeAuthRepository.observeCurrentUser()

    override fun observeIsLoggedIn(): Flow<Boolean> = userPreferences.isLoggedIn

    override fun observeIsOnboardingCompleted(): Flow<Boolean> = userPreferences.isOnboardingCompleted

    override fun observeIsBatteryGuidanceSeen(): Flow<Boolean> = userPreferences.isBatteryGuidanceSeen

    override suspend fun getCurrentUser(): User? {
        val currentId = userPreferences.currentUserId.firstOrNull() ?: return null
        return userDao.getUserById(currentId)
    }

    override suspend fun getUserByContact(contact: String): User? = fakeAuthRepository.getUserByContact(contact)

    override suspend fun createAccount(
        name: String,
        emailOrPhone: String,
        password: String,
        role: UserRole
    ): Result<String> {
        if (!userPreferences.isPrivacyConsentGiven.firstOrNull().let { it == true }) {
            return Result.failure(IllegalStateException("Privacy consent must be granted before account creation or storing data."))
        }

        val auth = firebaseAuth
        val db = firestore
        if (auth == null || db == null) {
            // Fallback to fake authentication when Firebase is not initialized
            return fakeAuthRepository.createAccount(name, emailOrPhone, password, role)
        }

        return try {
            val email = if (emailOrPhone.contains("@")) emailOrPhone.trim() else "${emailOrPhone.trim()}@meditrack.local"
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user
                ?: return Result.failure(IllegalStateException("Firebase user creation failed"))

            val user = User(
                id = firebaseUser.uid,
                name = name.trim(),
                email = if (emailOrPhone.contains("@")) emailOrPhone.trim() else null,
                phone = if (!emailOrPhone.contains("@")) emailOrPhone.trim() else null,
                role = role,
                createdAt = System.currentTimeMillis()
            )

            // Persist user to Firestore
            val userMap = hashMapOf(
                "id" to user.id,
                "name" to user.name,
                "email" to user.email,
                "phone" to user.phone,
                "role" to user.role.name,
                "createdAt" to user.createdAt
            )
            try {
                db.collection("users").document(user.id).set(userMap).await()
            } catch (_: Exception) {}

            // Persist user to Room DB (source of truth)
            userDao.insertUser(user)
            userPreferences.setLoggedIn(user.id)

            Result.success(user.id)
        } catch (e: Exception) {
            // Gracefully fall back to local Room database fake authentication!
            fakeAuthRepository.createAccount(name, emailOrPhone, password, role)
        }
    }

    override suspend fun verifyOtp(emailOrPhone: String, otp: String): Result<User> {
        val current = getCurrentUser()
        if (current != null) {
            val contact = emailOrPhone.trim()
            val matches = current.email.equals(contact, ignoreCase = true) || current.phone == contact
            if (matches) return Result.success(current)
        }
        return fakeAuthRepository.verifyOtp(emailOrPhone, otp)
    }

    override suspend fun login(emailOrPhone: String, password: String): Result<User> {
        val auth = firebaseAuth
        val db = firestore
        if (auth == null || db == null) {
            return fakeAuthRepository.login(emailOrPhone, password)
        }

        return try {
            val email = if (emailOrPhone.contains("@")) emailOrPhone.trim() else "${emailOrPhone.trim()}@meditrack.local"
            val authResult = auth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user
                ?: return fakeAuthRepository.login(emailOrPhone, password)

            // Retrieve user profile from Firestore or Room
            var user = userDao.getUserById(firebaseUser.uid)
            if (user == null) {
                try {
                    val doc = db.collection("users").document(firebaseUser.uid).get().await()
                    if (doc.exists()) {
                        val roleStr = doc.getString("role") ?: UserRole.PATIENT.name
                        user = User(
                            id = firebaseUser.uid,
                            name = doc.getString("name") ?: "User",
                            email = doc.getString("email"),
                            phone = doc.getString("phone"),
                            role = try { UserRole.valueOf(roleStr) } catch (_: Exception) { UserRole.PATIENT },
                            createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                        )
                        userDao.insertUser(user)
                    }
                } catch (_: Exception) {}
            }
            if (user == null) {
                user = User(
                    id = firebaseUser.uid,
                    name = firebaseUser.displayName ?: "User",
                    email = firebaseUser.email,
                    createdAt = System.currentTimeMillis()
                )
                userDao.insertUser(user)
            }

            userPreferences.setLoggedIn(user.id)
            Result.success(user)
        } catch (e: Exception) {
            fakeAuthRepository.login(emailOrPhone, password)
        }
    }

    override suspend fun loginAsDemo(role: UserRole): Result<User> = fakeAuthRepository.loginAsDemo(role)

    override suspend fun logout() {
        try {
            firebaseAuth?.signOut()
        } catch (_: Exception) {}
        fakeAuthRepository.logout()
    }

    override suspend fun setOnboardingCompleted() = fakeAuthRepository.setOnboardingCompleted()

    override suspend fun setBatteryGuidanceSeen() = fakeAuthRepository.setBatteryGuidanceSeen()
}
