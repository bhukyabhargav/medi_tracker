package com.meditrack.app.feature.verification

import android.graphics.Bitmap
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.VerificationRepository
import com.meditrack.app.domain.usecase.AdherenceCalculator
import com.meditrack.app.domain.usecase.LateEntryPolicy
import com.meditrack.app.domain.usecase.ProcessStockDecrementUseCase
import com.meditrack.app.domain.usecase.RecordManualUnverifiedUseCase
import com.meditrack.app.domain.usecase.RecordVerifiedDoseUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import com.meditrack.app.feature.verification.pipeline.CoordinatorResult
import com.meditrack.app.feature.verification.pipeline.VerificationCoordinator
import com.meditrack.app.feature.verification.pipeline.VerificationPipeline
import com.meditrack.app.feature.verification.pipeline.VerificationResult
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LateDoseVerificationTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val verificationRepository: VerificationRepository = mockk(relaxed = true)
    private val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val verificationPipeline: VerificationPipeline = mockk(relaxed = true)
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val processStockDecrementUseCase: ProcessStockDecrementUseCase = mockk(relaxed = true)

    private lateinit var coordinator: VerificationCoordinator
    private lateinit var recordManualUnverifiedUseCase: RecordManualUnverifiedUseCase
    private lateinit var recordVerifiedDoseUseCase: RecordVerifiedDoseUseCase

    private val baseTime = 10000000L
    private val sampleMedicine = Medicine(
        id = "med_1",
        name = "Metformin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = 0L,
        initialStock = 20,
        currentStock = 20
    )

    private val missedDose = DoseEvent(
        id = "missed_dose_1",
        medicineId = "med_1",
        scheduledAt = baseTime,
        windowEndsAt = baseTime + 3600000L,
        status = DoseStatus.MISSED
    )

    private val dummyBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        recordVerifiedDoseUseCase = RecordVerifiedDoseUseCase(
            doseRepository,
            medicineRepository,
            alarmScheduler,
            processStockDecrementUseCase
        )
        recordManualUnverifiedUseCase = RecordManualUnverifiedUseCase(doseRepository, alarmScheduler)

        coordinator = VerificationCoordinator(
            doseRepository = doseRepository,
            medicineRepository = medicineRepository,
            verificationRepository = verificationRepository,
            caregiverRepository = caregiverRepository,
            outboxRepository = outboxRepository,
            userPreferences = userPreferences,
            verificationPipeline = verificationPipeline,
            recordVerifiedDoseUseCase = recordVerifiedDoseUseCase,
            alarmScheduler = alarmScheduler,
            processStockDecrementUseCase = processStockDecrementUseCase
        )

        coEvery { doseRepository.getById("missed_dose_1") } returns missedDose
        coEvery { medicineRepository.getById("med_1") } returns sampleMedicine
        coEvery { verificationRepository.getRecentAttemptsForMedicine(any(), any()) } returns emptyList()
        coEvery { doseRepository.getLatestVerifiedDose("med_1") } returns null
        coEvery { userPreferences.currentUserId } returns flowOf("user_1")
    }

    @Test
    fun boundaryTest_nextDoseVs24h_earlierWins() {
        val hour = 3600000L
        val dose = missedDose.copy(scheduledAt = 0L)

        // Case A: Next dose is 6 hours away (< 24h). Next dose cutoff wins!
        val nextAt6h = 6 * hour
        assertTrue(LateEntryPolicy.canLogLate(dose, nextScheduledAt = nextAt6h, now = 5 * hour))
        assertFalse(LateEntryPolicy.canLogLate(dose, nextScheduledAt = nextAt6h, now = 7 * hour))

        // Case B: Next dose is 30 hours away (> 24h). 24h limit wins!
        val nextAt30h = 30 * hour
        assertTrue(LateEntryPolicy.canLogLate(dose, nextScheduledAt = nextAt30h, now = 23 * hour))
        assertFalse(LateEntryPolicy.canLogLate(dose, nextScheduledAt = nextAt30h, now = 25 * hour))

        // Case C: No future dose scheduled. 24h limit applies
        assertTrue(LateEntryPolicy.canLogLate(dose, nextScheduledAt = null, now = 24 * hour))
        assertFalse(LateEntryPolicy.canLogLate(dose, nextScheduledAt = null, now = 24 * hour + 1))
    }

    @Test
    fun doubleDoseSafetyGap_formulaEvaluatesCorrectly() {
        val hour = 3600000L
        val scheduledAt = 0L
        val nextScheduledAt = 8 * hour // 8 hours interval
        // safety gap = max(1h, 25% of 8h = 2h) = 2 hours.
        // Safety gap applies when time until next dose is in 0..2 hours (i.e. now in 6h..8h)

        // 3 hours before next dose (now = 5h): Outside safety gap
        assertFalse(LateEntryPolicy.isWithinDoubleDoseSafetyGap(scheduledAt, nextScheduledAt, now = 5 * hour))

        // 1.5 hours before next dose (now = 6.5h): Inside safety gap!
        assertTrue(LateEntryPolicy.isWithinDoubleDoseSafetyGap(scheduledAt, nextScheduledAt, now = (6.5 * hour).toLong()))
    }

    @Test
    fun lateVerification_success_transitionsToTakenLate_andEnqueuesOutbox() = runTest {
        val captureTime = baseTime + (2 * 3600000L) // 2 hours late
        coEvery { doseRepository.getNextScheduledDose("med_1", baseTime) } returns null
        coEvery { caregiverRepository.hasConnectedCaregiver("user_1") } returns true
        coEvery {
            verificationPipeline.verify(any(), any(), any(), any(), any())
        } returns VerificationResult(passed = true, emptyCellsAfter = 1)

        val result = coordinator.verify("missed_dose_1", "/proof/late.jpg", dummyBitmap, now = captureTime)

        assertTrue(result is CoordinatorResult.Success)
        val success = result as CoordinatorResult.Success
        assertTrue(success.isLate)

        // Status transitioned to TAKEN_LATE
        val doseSlot = slot<DoseEvent>()
        coVerify { doseRepository.update(capture(doseSlot)) }
        assertEquals(DoseStatus.TAKEN_LATE, doseSlot.captured.status)
        assertEquals(captureTime, doseSlot.captured.takenAt)
        assertTrue(doseSlot.captured.lateEntry)
        assertEquals(1, doseSlot.captured.emptyCellsAfter)

        // Stock decremented via ProcessStockDecrementUseCase
        coVerify { processStockDecrementUseCase("med_1", 1, captureTime) }

        // Enqueued DOSE_TAKEN_LATE outbox notification
        val outboxSlot = slot<NotificationOutbox>()
        coVerify { outboxRepository.enqueue(capture(outboxSlot)) }
        assertEquals(NotifType.DOSE_TAKEN_LATE, outboxSlot.captured.type)
        assertTrue(outboxSlot.captured.payloadJson.contains("missed_dose_1"))
    }

    @Test
    fun manualUnverified_setsFlag_statusStaysMissed_stockNotDecremented() = runTest {
        val result = recordManualUnverifiedUseCase("missed_dose_1")

        assertTrue(result.isSuccess)
        val doseSlot = slot<DoseEvent>()
        coVerify { doseRepository.update(capture(doseSlot)) }
        assertEquals(DoseStatus.MISSED, doseSlot.captured.status)
        assertTrue(doseSlot.captured.manualUnverified)

        // Stock is NOT decremented
        coVerify(exactly = 0) { medicineRepository.updateStock(any(), any()) }
    }

    @Test
    fun adherenceCalculator_countsTakenLateAsTaken_andManualUnverifiedNeverAsTaken() {
        val now = 1000000L
        val doses = listOf(
            missedDose.copy(id = "1", status = DoseStatus.TAKEN),
            missedDose.copy(id = "2", status = DoseStatus.TAKEN),
            missedDose.copy(id = "3", status = DoseStatus.TAKEN),
            missedDose.copy(id = "4", status = DoseStatus.TAKEN_LATE, lateEntry = true), // counts as taken!
            missedDose.copy(id = "5", status = DoseStatus.MISSED, manualUnverified = true), // does NOT count as taken!
            missedDose.copy(id = "6", status = DoseStatus.MISSED)
        )

        val stats = AdherenceCalculator.calculate(doses)

        // Total closed doses = 6
        // Taken count = 3 TAKEN + 1 TAKEN_LATE = 4
        // Missed count = 2
        assertEquals(6, stats.closedDosesCount)
        assertEquals(4, stats.verifiedTakenCount)
        assertEquals(2, stats.missedCount)
        assertEquals(1, stats.takenLateCount)
        // Adherence percentage = 4 / 6 = 66.67% -> rounds to 67%
        assertEquals(67, kotlin.math.round(stats.percentage ?: 0.0).toInt())
        assertEquals("67%", stats.percentageFormatted)
    }
}
