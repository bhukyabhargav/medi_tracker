package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class RecordVerifiedDoseUseCaseTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val processStockDecrementUseCase: ProcessStockDecrementUseCase = mockk(relaxed = true)

    private lateinit var useCase: RecordVerifiedDoseUseCase

    private val sampleMedicine = Medicine(
        id = "med_1",
        name = "Metformin",
        dosageText = "500mg",
        pillsPerDose = 2,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = 1000L,
        foodRelation = FoodRelation.AFTER,
        initialStock = 60,
        currentStock = 50
    )

    private val sampleDose = DoseEvent(
        id = "dose_1",
        medicineId = "med_1",
        scheduledAt = 2000L,
        windowEndsAt = 5600L,
        status = DoseStatus.PENDING,
        updatedAt = 1000L
    )

    @Before
    fun setup() {
        useCase = RecordVerifiedDoseUseCase(doseRepository, medicineRepository, alarmScheduler, processStockDecrementUseCase)
    }

    @Test
    fun recordVerifiedDose_onTime_transitionsToTaken_decrementsStock_andCancelsAlarm() = runTest {
        coEvery { doseRepository.getById("dose_1") } returns sampleDose
        coEvery { medicineRepository.getById("med_1") } returns sampleMedicine

        val updatedDoseSlot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(updatedDoseSlot)) } returns Unit

        val result = useCase("dose_1", photoPath = "path/photo.jpg", emptyCellsAfter = 1, takenAt = 2500L, isLate = false)

        assertTrue(result.isSuccess)
        val saved = updatedDoseSlot.captured
        assertEquals(DoseStatus.TAKEN, saved.status)
        assertEquals(2500L, saved.takenAt)
        assertEquals("path/photo.jpg", saved.verifiedPhotoPath)
        assertEquals(1, saved.emptyCellsAfter)

        // Decrements 2 pills via processStockDecrementUseCase exactly once
        coVerify(exactly = 1) { processStockDecrementUseCase("med_1", 2, 2500L) }
        coVerify { alarmScheduler.cancelDoseAlarm("dose_1") }
    }

    @Test
    fun recordVerifiedDose_late_transitionsToTakenLate_decrementsStock_andCancelsAlarm() = runTest {
        val missedDose = sampleDose.copy(id = "missed_1", status = DoseStatus.MISSED)
        coEvery { doseRepository.getById("missed_1") } returns missedDose
        coEvery { medicineRepository.getById("med_1") } returns sampleMedicine

        val updatedDoseSlot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(updatedDoseSlot)) } returns Unit

        val result = useCase("missed_1", photoPath = "path/late.jpg", emptyCellsAfter = 2, takenAt = 6000L, isLate = true)

        assertTrue(result.isSuccess)
        val saved = updatedDoseSlot.captured
        assertEquals(DoseStatus.TAKEN_LATE, saved.status)
        assertEquals(6000L, saved.takenAt)
        assertEquals("path/late.jpg", saved.verifiedPhotoPath)
        assertEquals(2, saved.emptyCellsAfter)
        assertTrue(saved.lateEntry)

        // Decrements stock exactly once
        coVerify(exactly = 1) { processStockDecrementUseCase("med_1", 2, 6000L) }
        coVerify { alarmScheduler.cancelDoseAlarm("missed_1") }
    }

    @Test
    fun recordVerifiedDose_failsWhenDoseNotFound() = runTest {
        coEvery { doseRepository.getById("non_existent") } returns null

        val result = useCase("non_existent")
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
    }

    @Test
    fun recordVerifiedDose_failsWhenAlreadyTaken_andDoesNotDecrementStock() = runTest {
        val alreadyTakenDose = sampleDose.copy(status = DoseStatus.TAKEN)
        coEvery { doseRepository.getById("dose_1") } returns alreadyTakenDose

        val result = useCase("dose_1")
        assertTrue(result.isFailure)
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
    }

    @Test
    fun recordVerifiedDose_concurrentCalls_onlyOneSucceedsAndStockDecrementedOnce() = runTest {
        var currentDose = sampleDose
        coEvery { doseRepository.getById("dose_1") } answers { currentDose }
        coEvery { medicineRepository.getById("med_1") } returns sampleMedicine
        coEvery { doseRepository.update(any()) } answers {
            val updated = firstArg<DoseEvent>()
            currentDose = updated
            Unit
        }

        val jobs = (1..10).map {
            async(Dispatchers.Default) {
                useCase("dose_1", photoPath = "photo_$it.jpg", emptyCellsAfter = 2, takenAt = 2500L)
            }
        }
        val results = jobs.awaitAll()

        val successes = results.count { it.isSuccess }
        val failures = results.count { it.isFailure }
        assertEquals(1, successes)
        assertEquals(9, failures)
        coVerify(exactly = 1) { processStockDecrementUseCase("med_1", 2, 2500L) }
    }
}
