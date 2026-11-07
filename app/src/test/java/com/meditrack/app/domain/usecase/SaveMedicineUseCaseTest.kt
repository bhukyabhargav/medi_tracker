package com.meditrack.app.domain.usecase

import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SaveMedicineUseCaseTest {

    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private lateinit var useCase: SaveMedicineUseCase

    private val zoneId = ZoneId.of("UTC")

    @Before
    fun setup() {
        coEvery { userPreferences.isPrivacyConsentGiven } returns flowOf(true)
        useCase = SaveMedicineUseCase(medicineRepository, doseRepository, alarmScheduler, userPreferences)
    }

    private fun createSampleMedicine(
        id: String = "med_1",
        name: String = "Amoxicillin",
        startDate: Long = LocalDate.of(2026, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endDate: Long? = null,
        initialStock: Int = 30,
        timesOfDay: List<LocalTime> = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))
    ): Medicine {
        return Medicine(
            id = id,
            name = name,
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = timesOfDay,
            startDate = startDate,
            endDate = endDate,
            foodRelation = FoodRelation.AFTER,
            initialStock = initialStock,
            currentStock = initialStock
        )
    }

    @Test
    fun saveNewMedicine_sets_current_stock_and_inserts_doses() = runTest {
        val med = createSampleMedicine(
            startDate = LocalDate.of(2026, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endDate = LocalDate.of(2026, 1, 3).atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()
        )

        val insertedMedSlot = slot<Medicine>()
        coEvery { medicineRepository.insert(capture(insertedMedSlot)) } returns Unit

        val insertedDosesSlot = slot<List<DoseEvent>>()
        coEvery { doseRepository.insertAll(capture(insertedDosesSlot)) } returns Unit

        val doses = useCase.saveNewMedicine(
            medicine = med,
            rollingDaysAhead = 7L,
            zoneId = zoneId
        )

        coVerify { medicineRepository.insert(any()) }
        assertEquals(30, insertedMedSlot.captured.currentStock)

        // 3 days * 2 times per day = 6 doses
        assertEquals(6, doses.size)
        coVerify { doseRepository.insertAll(any()) }
        assertEquals(6, insertedDosesSlot.captured.size)
    }

    @Test
    fun updateMedicine_deletes_future_pending_doses_and_regenerates_future_only() = runTest {
        val med = createSampleMedicine(
            timesOfDay = listOf(LocalTime.of(9, 0), LocalTime.of(15, 0), LocalTime.of(21, 0))
        )

        coEvery { medicineRepository.update(any()) } returns Unit
        coEvery { doseRepository.deleteFuturePendingDoses(any(), any()) } returns Unit

        val doses = useCase.updateMedicine(
            updatedMedicine = med,
            rollingDaysAhead = 3L,
            zoneId = zoneId
        )

        coVerify { medicineRepository.update(match { it.id == med.id }) }
        coVerify { doseRepository.deleteFuturePendingDoses(eq("med_1"), any()) }
        coVerify { doseRepository.insertAll(any()) }
        assertTrue(doses.isNotEmpty())
    }

    @Test
    fun deleteMedicine_deletes_future_pending_doses_and_soft_deletes_medicine() = runTest {
        val med = createSampleMedicine(id = "med_delete_me")
        coEvery { medicineRepository.getById("med_delete_me") } returns med

        useCase.deleteMedicine("med_delete_me")

        coVerify { doseRepository.deleteFuturePendingDoses(eq("med_delete_me"), any()) }
        coVerify { medicineRepository.update(match { it.id == "med_delete_me" && it.isDeleted }) }
    }

    @Test
    fun saveNewMedicine_throwsIllegalStateException_whenPrivacyConsentNotGiven() = runTest {
        val userPrefs = mockk<UserPreferences>(relaxed = true)
        coEvery { userPrefs.isPrivacyConsentGiven } returns flowOf(false)

        val gatedUseCase = SaveMedicineUseCase(medicineRepository, doseRepository, alarmScheduler, userPrefs)
        val med = createSampleMedicine()

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                gatedUseCase.saveNewMedicine(med)
            }
        }
    }

    @Test
    fun saveNewMedicine_succeeds_whenPrivacyConsentGiven() = runTest {
        val userPrefs = mockk<UserPreferences>(relaxed = true)
        coEvery { userPrefs.isPrivacyConsentGiven } returns flowOf(true)

        val gatedUseCase = SaveMedicineUseCase(medicineRepository, doseRepository, alarmScheduler, userPrefs)
        val med = createSampleMedicine(
            startDate = LocalDate.of(2026, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endDate = LocalDate.of(2026, 1, 2).atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()
        )

        val doses = gatedUseCase.saveNewMedicine(med, rollingDaysAhead = 2L, zoneId = zoneId)
        assertTrue(doses.isNotEmpty())
    }
}
