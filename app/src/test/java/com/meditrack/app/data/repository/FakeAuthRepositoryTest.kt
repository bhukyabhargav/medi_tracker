package com.meditrack.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.data.db.AppDatabase
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.UserRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FakeAuthRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var userDao: UserDao
    private lateinit var userPreferences: UserPreferences
    private lateinit var repository: FakeAuthRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        userDao = db.userDao()
        userPreferences = UserPreferences(context)
        repository = FakeAuthRepository(userDao, db.medicineDao(), db.doseEventDao(), db.caregiverLinkDao(), userPreferences)
    }

    @After
    fun tearDown() = runTest {
        repository.logout()
        db.close()
    }

    @Test
    fun `successful registration and OTP verification establishes session and creates user`() = runTest {
        val createResult = repository.createAccount(
            name = "Jane Patient",
            emailOrPhone = "jane@example.com",
            password = "securePassword123",
            role = UserRole.PATIENT
        )
        assertTrue(createResult.isSuccess)

        // Before OTP, user is not in Room and not logged in
        assertNull(userDao.getUserByContact("jane@example.com"))
        assertFalse(repository.observeIsLoggedIn().first())

        // Verify with correct OTP
        val verifyResult = repository.verifyOtp("jane@example.com", FakeAuthRepository.DEBUG_OTP)
        assertTrue(verifyResult.isSuccess)
        val createdUser = verifyResult.getOrNull()
        assertNotNull(createdUser)
        assertEquals("Jane Patient", createdUser?.name)
        assertEquals("jane@example.com", createdUser?.email)
        assertEquals(UserRole.PATIENT, createdUser?.role)

        // User now exists in Room and session is active
        val dbUser = userDao.getUserById(createdUser!!.id)
        assertNotNull(dbUser)
        assertEquals(createdUser.id, dbUser?.id)
        assertTrue(repository.observeIsLoggedIn().first())
        assertEquals(createdUser.id, repository.getCurrentUser()?.id)
    }

    @Test
    fun `failed OTP does not create user or establish session`() = runTest {
        repository.createAccount(
            name = "Test User",
            emailOrPhone = "test@example.com",
            password = "password123",
            role = UserRole.PATIENT
        )

        val wrongOtpResult = repository.verifyOtp("test@example.com", "000000")
        assertTrue(wrongOtpResult.isFailure)

        // User must NOT be in Room, session must remain false
        assertNull(userDao.getUserByContact("test@example.com"))
        assertFalse(repository.observeIsLoggedIn().first())
        assertNull(repository.getCurrentUser())
    }

    @Test
    fun `duplicate registered contact is rejected`() = runTest {
        // Register and verify user 1
        repository.createAccount("User One", "shared@test.com", "password123", UserRole.PATIENT)
        repository.verifyOtp("shared@test.com", FakeAuthRepository.DEBUG_OTP)

        // Attempting to register another account with same email (even with different case)
        val dupResult = repository.createAccount("User Two", "SHARED@test.com", "otherpassword", UserRole.CAREGIVER)
        assertTrue(dupResult.isFailure)
        assertTrue(dupResult.exceptionOrNull()?.message?.contains("already exists") == true)
    }

    @Test
    fun `duplicate pending registration is rejected`() = runTest {
        repository.createAccount("Pending User", "pending@test.com", "password123", UserRole.PATIENT)

        val dupPendingResult = repository.createAccount("Pending User Two", "pending@test.com", "anotherpassword", UserRole.PATIENT)
        assertTrue(dupPendingResult.isFailure)
        assertTrue(dupPendingResult.exceptionOrNull()?.message?.contains("already pending") == true)
    }

    @Test
    fun `login succeeds with correct credentials`() = runTest {
        repository.createAccount("Valid User", "valid@test.com", "correctPass123", UserRole.PATIENT)
        repository.verifyOtp("valid@test.com", FakeAuthRepository.DEBUG_OTP)
        repository.logout()
        assertFalse(repository.observeIsLoggedIn().first())

        val loginResult = repository.login("valid@test.com", "correctPass123")
        assertTrue(loginResult.isSuccess)
        assertEquals("Valid User", loginResult.getOrNull()?.name)
        assertTrue(repository.observeIsLoggedIn().first())
    }

    @Test
    fun `login fails with incorrect password and does not log in`() = runTest {
        repository.createAccount("Valid User", "valid@test.com", "correctPass123", UserRole.PATIENT)
        repository.verifyOtp("valid@test.com", FakeAuthRepository.DEBUG_OTP)
        repository.logout()

        val badPassResult = repository.login("valid@test.com", "wrongPassword")
        assertTrue(badPassResult.isFailure)
        assertEquals("Incorrect password", badPassResult.exceptionOrNull()?.message)
        assertFalse(repository.observeIsLoggedIn().first())
    }

    @Test
    fun `login fails with unknown contact without silent account creation`() = runTest {
        val unknownResult = repository.login("nonexistent@test.com", "anyPassword")
        assertTrue(unknownResult.isFailure)
        assertTrue(unknownResult.exceptionOrNull()?.message?.contains("Account not found") == true)
        assertNull(userDao.getUserByContact("nonexistent@test.com"))
        assertFalse(repository.observeIsLoggedIn().first())
    }

    @Test
    fun `doctor registration succeeds without registration number`() = runTest {
        val result = repository.createAccount(
            name = "Dr. Alice",
            emailOrPhone = "doctor@hospital.org",
            password = "doctorSecurePass",
            role = UserRole.DOCTOR
        )
        assertTrue(result.isSuccess)
        repository.verifyOtp("doctor@hospital.org", FakeAuthRepository.DEBUG_OTP)

        val user = userDao.getUserByContact("doctor@hospital.org")
        assertNotNull(user)
        assertEquals(UserRole.DOCTOR, user?.role)
    }

    @Test
    fun `multi-user observation tests current session user when multiple users exist in Room`() = runTest {
        // Create user 1
        repository.createAccount("Patient Alice", "alice@test.com", "password123", UserRole.PATIENT)
        val alice = repository.verifyOtp("alice@test.com", FakeAuthRepository.DEBUG_OTP).getOrThrow()

        // User 1 is observed
        var observedUser = repository.observeCurrentUser().first()
        assertEquals(alice.id, observedUser?.id)
        assertEquals("Patient Alice", observedUser?.name)

        // Logout
        repository.logout()
        observedUser = repository.observeCurrentUser().first()
        assertNull(observedUser)

        // Create user 2
        repository.createAccount("Doctor Bob", "bob@test.com", "password456", UserRole.DOCTOR)
        val bob = repository.verifyOtp("bob@test.com", FakeAuthRepository.DEBUG_OTP).getOrThrow()

        // Two users now exist in Room
        assertNotNull(userDao.getUserById(alice.id))
        assertNotNull(userDao.getUserById(bob.id))

        // Current session must observe Bob, NOT Alice
        observedUser = repository.observeCurrentUser().first()
        assertEquals(bob.id, observedUser?.id)
        assertEquals("Doctor Bob", observedUser?.name)

        // Switch back to Alice
        repository.logout()
        repository.login("alice@test.com", "password123")
        observedUser = repository.observeCurrentUser().first()
        assertEquals(alice.id, observedUser?.id)
        assertEquals("Patient Alice", observedUser?.name)
    }

    @Test
    fun `logout clears session but preserves device level onboarding flags`() = runTest {
        repository.createAccount("Dave", "dave@test.com", "password123", UserRole.PATIENT)
        repository.verifyOtp("dave@test.com", FakeAuthRepository.DEBUG_OTP)

        repository.setOnboardingCompleted()
        repository.setBatteryGuidanceSeen()

        assertTrue(repository.observeIsOnboardingCompleted().first())
        assertTrue(repository.observeIsBatteryGuidanceSeen().first())
        assertTrue(repository.observeIsLoggedIn().first())

        repository.logout()

        assertFalse(repository.observeIsLoggedIn().first())
        assertNull(repository.getCurrentUser())
        // Device-level setup flags remain preserved
        assertTrue(repository.observeIsOnboardingCompleted().first())
        assertTrue(repository.observeIsBatteryGuidanceSeen().first())
    }

    @Test
    fun `logout preserves medicines and doses in Room without foreign key violation`() = runTest {
        repository.createAccount("Eve", "eve@test.com", "password123", UserRole.PATIENT)
        val eve = repository.verifyOtp("eve@test.com", FakeAuthRepository.DEBUG_OTP).getOrThrow()

        // Insert medicine and dose with foreign key relationship
        val med = com.meditrack.app.domain.model.Medicine(
            id = "med_eve_1",
            patientId = eve.id,
            name = "Metformin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = com.meditrack.app.domain.model.FrequencyType.DAILY,
            timesOfDay = emptyList(),
            startDate = System.currentTimeMillis(),
            initialStock = 30,
            currentStock = 30
        )
        db.medicineDao().insert(med)

        val dose = com.meditrack.app.domain.model.DoseEvent(
            id = "dose_eve_1",
            medicineId = med.id,
            patientId = eve.id,
            scheduledAt = System.currentTimeMillis() + 3600000L,
            windowEndsAt = System.currentTimeMillis() + 7200000L
        )
        db.doseEventDao().insert(dose)

        // Logout must NOT throw SQLiteConstraintException and must preserve offline patient history
        repository.logout()

        assertNull(repository.getCurrentUser())
        assertNotNull(db.medicineDao().getById("med_eve_1"))
        assertNotNull(db.doseEventDao().getById("dose_eve_1"))

        // Account deletion wipes in proper order
        repository.deleteAccount(eve.id)
        assertNull(db.doseEventDao().getById("dose_eve_1"))
        assertNull(db.medicineDao().getById("med_eve_1"))
    }
}
