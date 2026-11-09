package com.meditrack.app.feature.medicine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.data.db.AppDatabase
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.data.repository.DoseRepositoryImpl
import com.meditrack.app.data.repository.MedicineRepositoryImpl
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.RefillStockUseCase
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatientMedicineLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var medicineDao: MedicineDao
    private lateinit var doseEventDao: DoseEventDao
    private lateinit var medicineRepository: MedicineRepository
    private lateinit var doseRepository: DoseRepository
    private lateinit var saveMedicineUseCase: SaveMedicineUseCase

    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val refillStockUseCase: RefillStockUseCase = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)

    private val userIdFlow = MutableStateFlow<String?>("user_A")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        medicineDao = db.medicineDao()
        doseEventDao = db.doseEventDao()

        every { userPreferences.currentUserId } returns userIdFlow
        every { userPreferences.isPrivacyConsentGiven } returns flowOf(true)

        medicineRepository = MedicineRepositoryImpl(medicineDao, userPreferences)
        doseRepository = DoseRepositoryImpl(doseEventDao, userPreferences)
        saveMedicineUseCase = SaveMedicineUseCase(medicineRepository, doseRepository, alarmScheduler, userPreferences)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun test1_userIsolation_addedMedicineAppearsInMedicinesListForSameUserAndNotDifferentUser() = runTest(testDispatcher) {
        userIdFlow.value = "user_A"

        val medA = Medicine(
            id = "med_A_1",
            patientId = "user_A",
            name = "Amoxicillin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = System.currentTimeMillis(),
            initialStock = 20,
            currentStock = 20
        )
        saveMedicineUseCase.saveNewMedicine(medA)

        // 1. Observe active medicines for User A
        val listForUserA = medicineRepository.observeActive().first()
        assertEquals(1, listForUserA.size)
        assertEquals("Amoxicillin", listForUserA[0].name)

        // 2. Switch session to User B
        userIdFlow.value = "user_B"
        val listForUserB = medicineRepository.observeActive().first()
        assertTrue("User B must NOT see medicines belonging to User A", listForUserB.isEmpty())

        // 3. Switch back to User A
        userIdFlow.value = "user_A"
        val listForUserAAgain = medicineRepository.observeActive().first()
        assertEquals(1, listForUserAAgain.size)
        assertEquals("Amoxicillin", listForUserAAgain[0].name)
    }

    @Test
    fun test2_editingMedicine_preservesCurrentStockAndBaselineEmptyCellsLedger() = runTest(testDispatcher) {
        userIdFlow.value = "user_A"

        val initialMed = Medicine(
            id = "med_ledger_1",
            patientId = "user_A",
            name = "Metformin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = System.currentTimeMillis(),
            initialStock = 30,
            currentStock = 30,
            baselinePhotoPath = "/storage/baseline_photo.jpg",
            baselineEmptyCells = 3
        )
        saveMedicineUseCase.saveNewMedicine(initialMed)

        // Simulate pill consumption over several days
        medicineDao.updateStock(initialMed.id, 22)
        assertEquals(22, medicineDao.getById(initialMed.id)!!.currentStock)

        // User edits dosage text and instructions via AddEditMedicineViewModel
        val addEditVm = AddEditMedicineViewModel(medicineRepository, saveMedicineUseCase, userPreferences)
        addEditVm.loadMedicine(initialMed.id)
        addEditVm.uiState.first { it.isEditing }

        addEditVm.onDosageChange("1000mg")
        addEditVm.onInstructionsChange("Take with dinner")
        addEditVm.saveDirectlyIfEditing()
        addEditVm.uiState.first { it.currentStep == AddEditStep.SUCCESS }
        

        val updatedMed = medicineDao.getById(initialMed.id)
        assertNotNull(updatedMed)
        assertEquals("1000mg", updatedMed!!.dosageText)
        assertEquals("Take with dinner", updatedMed.instructions)
        // VERIFY historical state is PRESERVED:
        assertEquals("currentStock must NOT reset to initialStock on edit", 22, updatedMed.currentStock)
        assertEquals("baselineEmptyCells must remain preserved", 3, updatedMed.baselineEmptyCells)
        assertEquals("baselinePhotoPath must remain preserved", "/storage/baseline_photo.jpg", updatedMed.baselinePhotoPath)
    }

    @Test
    fun test3_deletingMedicine_hidesFromActiveLists_removesFuturePendingDoses_preservesPastHistory() = runTest(testDispatcher) {
        userIdFlow.value = "user_A"
        val now = System.currentTimeMillis()

        val med = Medicine(
            id = "med_delete_1",
            patientId = "user_A",
            name = "Atorvastatin",
            dosageText = "20mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(21, 0)),
            startDate = now - 86400000L,
            initialStock = 30,
            currentStock = 29
        )
        saveMedicineUseCase.saveNewMedicine(med)

        // Insert historical taken dose
        val pastDose = DoseEvent(
            id = "dose_past_taken",
            patientId = "user_A",
            medicineId = med.id,
            scheduledAt = now - 7200000L,
            windowEndsAt = now - 3600000L,
            status = DoseStatus.TAKEN,
            takenAt = now - 7000000L
        )
        doseEventDao.insert(pastDose)

        // Insert future pending dose
        val futureDose = DoseEvent(
            id = "dose_future_pending",
            patientId = "user_A",
            medicineId = med.id,
            scheduledAt = now + 7200000L,
            windowEndsAt = now + 10800000L,
            status = DoseStatus.PENDING
        )
        doseEventDao.insert(futureDose)

        // Delete medicine
        val listVm = MedicinesListViewModel(medicineRepository, saveMedicineUseCase, refillStockUseCase)
        listVm.deleteMedicine(med.id)
        while (medicineDao.getById(med.id)?.isDeleted != true) {
            kotlinx.coroutines.delay(10)
        }

        // 1. Medicine is hidden from active list
        val activeMeds = medicineRepository.observeActive().first()
        assertTrue("Deleted medicine must be hidden from active lists", activeMeds.none { it.id == med.id })

        // 2. Future pending dose is removed
        assertNull("Future pending doses must be removed when medicine is deleted", doseEventDao.getById("dose_future_pending"))

        // 3. Past history is preserved
        val retrievedPastDose = doseEventDao.getById("dose_past_taken")
        assertNotNull("Past dose history must be preserved for clinical auditing", retrievedPastDose)
        assertEquals(DoseStatus.TAKEN, retrievedPastDose!!.status)

        // 4. Medicine record is soft-deleted (isDeleted = true)
        val rawMed = medicineDao.getById(med.id)
        assertNotNull(rawMed)
        assertTrue(rawMed!!.isDeleted)
    }

    @Test
    fun test4_reminderEnabledFalse_stillCreatesScheduledDoseEvents() = runTest(testDispatcher) {
        userIdFlow.value = "user_A"
        val now = System.currentTimeMillis()

        val medNoReminders = Medicine(
            id = "med_no_alarm_1",
            patientId = "user_A",
            name = "Vitamin D3",
            dosageText = "1000IU",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = now,
            reminderEnabled = false, // Reminders OFF
            initialStock = 60,
            currentStock = 60
        )

        val scheduledDoses = saveMedicineUseCase.saveNewMedicine(medNoReminders)
        assertTrue("Dose events must be generated even when reminderEnabled is false", scheduledDoses.isNotEmpty())

        val dbDoses = doseEventDao.getAllByPatientId("user_A").filter { it.medicineId == medNoReminders.id }
        assertEquals("Database must contain generated dose events for tracking and verification", scheduledDoses.size, dbDoses.size)

        // Verify alarm scheduler did NOT schedule audible alarm, but scheduled missed check
        coVerify(atLeast = 1) { alarmScheduler.scheduleMissedCheck(any()) }
        coVerify(exactly = 0) { alarmScheduler.scheduleDoseAlarm(any()) }
    }
}
