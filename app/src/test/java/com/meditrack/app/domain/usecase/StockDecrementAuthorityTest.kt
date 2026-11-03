package com.meditrack.app.domain.usecase

import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

/**
 * Validates the single-authority rule for stock decrements per Stage 9 of README.md:
 * 1. Stock is decremented ONLY by RecordVerifiedDose (verified on-time or late).
 * 2. Normal verified doses decrement stock exactly once.
 * 3. Late verified doses decrement stock exactly once.
 * 4. Duplicate verified dose requests fail and do not decrement stock twice.
 * 5. Skipped doses NEVER change stock.
 * 6. Missed doses NEVER change stock.
 * 7. Unverified doses NEVER change stock.
 * 8. Manual-unverified doses NEVER change stock.
 */
class StockDecrementAuthorityTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val processStockDecrementUseCase: ProcessStockDecrementUseCase = mockk(relaxed = true)

    private lateinit var recordVerifiedDoseUseCase: RecordVerifiedDoseUseCase
    private lateinit var recordManualUnverifiedUseCase: RecordManualUnverifiedUseCase
    private lateinit var processMissedDoseUseCase: ProcessMissedDoseUseCase

    private val sampleMedicine = Medicine(
        id = "med_test",
        name = "Atorvastatin",
        dosageText = "20mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(20, 0)),
        startDate = 1000L,
        foodRelation = FoodRelation.AFTER,
        initialStock = 30,
        currentStock = 15
    )

    private val pendingDose = DoseEvent(
        id = "dose_pending",
        medicineId = "med_test",
        scheduledAt = 2000L,
        windowEndsAt = 5000L,
        status = DoseStatus.PENDING
    )

    private val missedDose = DoseEvent(
        id = "dose_missed",
        medicineId = "med_test",
        scheduledAt = 2000L,
        windowEndsAt = 5000L,
        status = DoseStatus.MISSED
    )

    @Before
    fun setUp() {
        recordVerifiedDoseUseCase = RecordVerifiedDoseUseCase(
            doseRepository = doseRepository,
            medicineRepository = medicineRepository,
            alarmScheduler = alarmScheduler,
            processStockDecrementUseCase = processStockDecrementUseCase
        )

        recordManualUnverifiedUseCase = RecordManualUnverifiedUseCase(
            doseRepository = doseRepository,
            alarmScheduler = alarmScheduler
        )

        processMissedDoseUseCase = ProcessMissedDoseUseCase(
            doseRepository = doseRepository,
            caregiverRepository = caregiverRepository,
            outboxRepository = outboxRepository,
            userPreferences = userPreferences
        )

        coEvery { medicineRepository.getById("med_test") } returns sampleMedicine
        coEvery { userPreferences.currentUserId } returns flowOf("patient_1")
    }

    @Test
    fun verifiedNormalDose_decrementsStockExactlyOnce() = runTest {
        coEvery { doseRepository.getById("dose_pending") } returns pendingDose

        val result = recordVerifiedDoseUseCase("dose_pending", photoPath = "/p.jpg", emptyCellsAfter = 1, takenAt = 2500L, isLate = false)

        assertTrue("Verified normal dose should succeed", result.isSuccess)
        coVerify(exactly = 1) { processStockDecrementUseCase("med_test", 1, 2500L) }
    }

    @Test
    fun verifiedLateDose_decrementsStockExactlyOnce() = runTest {
        coEvery { doseRepository.getById("dose_missed") } returns missedDose

        val result = recordVerifiedDoseUseCase("dose_missed", photoPath = "/late.jpg", emptyCellsAfter = 2, takenAt = 6000L, isLate = true)

        assertTrue("Verified late dose should succeed", result.isSuccess)
        coVerify(exactly = 1) { processStockDecrementUseCase("med_test", 1, 6000L) }
    }

    @Test
    fun duplicateVerifiedDose_doesNotDecrementStockTwice() = runTest {
        val alreadyTakenDose = pendingDose.copy(status = DoseStatus.TAKEN)
        coEvery { doseRepository.getById("dose_pending") } returns alreadyTakenDose

        val result = recordVerifiedDoseUseCase("dose_pending", photoPath = "/p.jpg", emptyCellsAfter = 1, takenAt = 3000L)

        assertFalse("Attempt to re-verify an already TAKEN dose must fail", result.isSuccess)
        // Critical: zero decrements on illegal double-verification attempt
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
    }

    @Test
    fun skippedDose_doesNotDecrementStock() = runTest {
        // Skipped doses transition directly or via action receiver to SKIPPED
        val skippedDose = pendingDose.copy(status = DoseStatus.SKIPPED)
        coEvery { doseRepository.getById("dose_pending") } returns skippedDose

        // Calling RecordVerifiedDose on a SKIPPED dose is illegal and fails
        val result = recordVerifiedDoseUseCase("dose_pending")
        assertFalse(result.isSuccess)

        // Neither ProcessStockDecrementUseCase nor updateStock is ever touched for SKIPPED
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
        coVerify(exactly = 0) { medicineRepository.updateStock(any(), any()) }
    }

    @Test
    fun missedDose_doesNotDecrementStock() = runTest {
        coEvery { doseRepository.getById("dose_pending") } returns pendingDose
        val slot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(slot)) } returns Unit

        // Intake window expires -> ProcessMissedDoseUseCase transitions to MISSED
        val result = processMissedDoseUseCase("dose_pending", now = 6000L)

        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.MISSED, slot.captured.status)

        // Stock MUST NOT be decremented
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
        coVerify(exactly = 0) { medicineRepository.updateStock(any(), any()) }
    }

    @Test
    fun unverifiedDose_exhaustedStrikes_doesNotDecrementStock() = runTest {
        val unverifiedDose = pendingDose.copy(status = DoseStatus.UNVERIFIED, verificationAttempts = 3)
        coEvery { doseRepository.getById("dose_unverified") } returns unverifiedDose

        // Calling RecordVerifiedDose on an UNVERIFIED dose without late-entry bypass fails
        val result = recordVerifiedDoseUseCase("dose_unverified", photoPath = "/unv.jpg")
        assertFalse(result.isSuccess)

        // Stock is never decremented
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
        coVerify(exactly = 0) { medicineRepository.updateStock(any(), any()) }
    }

    @Test
    fun manualUnverifiedDose_doesNotDecrementStock() = runTest {
        coEvery { doseRepository.getById("dose_missed") } returns missedDose
        val slot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(slot)) } returns Unit

        val result = recordManualUnverifiedUseCase("dose_missed", reportedAt = 6500L)

        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.MISSED, slot.captured.status)
        assertTrue(slot.captured.manualUnverified)

        // Stock is NOT decremented
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
        coVerify(exactly = 0) { medicineRepository.updateStock(any(), any()) }
    }
}
