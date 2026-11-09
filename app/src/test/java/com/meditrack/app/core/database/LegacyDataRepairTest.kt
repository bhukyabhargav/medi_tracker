package com.meditrack.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.data.db.AppDatabase
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyDataRepairTest {

    private lateinit var db: AppDatabase
    private lateinit var medicineDao: MedicineDao
    private lateinit var doseEventDao: DoseEventDao
    private lateinit var legacyDataRepair: LegacyDataRepair

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        medicineDao = db.medicineDao()
        doseEventDao = db.doseEventDao()
        legacyDataRepair = LegacyDataRepair(medicineDao, doseEventDao)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun repairLegacyData_claimsUnownedMedicinesAndDoseEventsToAuthenticatedUser() = runTest {
        val now = System.currentTimeMillis()

        // 1. Unowned legacy medicine and dose event (patientId = "")
        val legacyMed = Medicine(
            id = "med_legacy_001",
            patientId = "",
            name = "Metformin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = now,
            initialStock = 30,
            currentStock = 30
        )
        medicineDao.insert(legacyMed)

        val legacyDose = DoseEvent(
            id = "dose_legacy_001",
            patientId = "",
            medicineId = legacyMed.id,
            scheduledAt = now + 3600000L,
            windowEndsAt = now + 7200000L,
            status = DoseStatus.PENDING
        )
        doseEventDao.insert(legacyDose)

        // 2. Medicine belonging to another user (should remain untouched)
        val otherUserMed = Medicine(
            id = "med_other_002",
            patientId = "user_other_999",
            name = "Lisinopril",
            dosageText = "10mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(9, 0)),
            startDate = now,
            initialStock = 15,
            currentStock = 15
        )
        medicineDao.insert(otherUserMed)

        val otherUserDose = DoseEvent(
            id = "dose_other_002",
            patientId = "user_other_999",
            medicineId = otherUserMed.id,
            scheduledAt = now + 3600000L,
            windowEndsAt = now + 7200000L,
            status = DoseStatus.PENDING
        )
        doseEventDao.insert(otherUserDose)

        // Pre-repair assertions: legacy rows exist
        assertEquals(1, medicineDao.countLegacyMedicines())
        assertEquals(1, doseEventDao.countLegacyDoseEvents())
        val activeBeforeRepair = medicineDao.observeActiveByPatient("user_active_123").first()
        assertTrue("Active medicines for user_active_123 must be empty before repair", activeBeforeRepair.isEmpty())

        // Execute one-time repair for the authenticated user
        val totalRepaired = legacyDataRepair.repairLegacyData("user_active_123")
        assertEquals(2, totalRepaired) // 1 medicine + 1 dose event claimed

        // Post-repair assertions
        assertEquals(0, medicineDao.countLegacyMedicines())
        assertEquals(0, doseEventDao.countLegacyDoseEvents())

        val claimedMed = medicineDao.getById("med_legacy_001")
        assertNotNull(claimedMed)
        assertEquals("user_active_123", claimedMed!!.patientId)

        val claimedDose = doseEventDao.getById("dose_legacy_001")
        assertNotNull(claimedDose)
        assertEquals("user_active_123", claimedDose!!.patientId)

        // Check active query returns the repaired medicine for the active user
        val activeAfterRepair = medicineDao.observeActiveByPatient("user_active_123").first()
        assertEquals(1, activeAfterRepair.size)
        assertEquals("Metformin", activeAfterRepair[0].name)

        // Other user's data remains isolated and untouched
        val otherMedAfter = medicineDao.getById("med_other_002")
        assertNotNull(otherMedAfter)
        assertEquals("user_other_999", otherMedAfter!!.patientId)

        val otherDoseAfter = doseEventDao.getById("dose_other_002")
        assertNotNull(otherDoseAfter)
        assertEquals("user_other_999", otherDoseAfter!!.patientId)
    }

    @Test
    fun repairLegacyData_withBlankUserId_doesNothing() = runTest {
        val now = System.currentTimeMillis()
        val legacyMed = Medicine(
            id = "med_blank_check",
            patientId = "",
            name = "Paracetamol",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = now,
            initialStock = 10,
            currentStock = 10
        )
        medicineDao.insert(legacyMed)

        val repaired = legacyDataRepair.repairLegacyData("")
        assertEquals(0, repaired)
        assertEquals(1, medicineDao.countLegacyMedicines())
    }
}
