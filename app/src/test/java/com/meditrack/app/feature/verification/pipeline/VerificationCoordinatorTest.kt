package com.meditrack.app.feature.verification.pipeline

import android.graphics.Bitmap
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.domain.model.VerificationAttempt
import com.meditrack.app.domain.model.VerificationResultType
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.VerificationRepository
import com.meditrack.app.domain.usecase.RecordVerifiedDoseUseCase
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerificationCoordinatorTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val verificationRepository: VerificationRepository = mockk(relaxed = true)
    private val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val verificationPipeline: VerificationPipeline = mockk(relaxed = true)
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val processStockDecrementUseCase: com.meditrack.app.domain.usecase.ProcessStockDecrementUseCase = mockk(relaxed = true)
    private val processMissedDoseUseCase: com.meditrack.app.domain.usecase.ProcessMissedDoseUseCase = mockk(relaxed = true)
    private lateinit var recordVerifiedDoseUseCase: RecordVerifiedDoseUseCase

    private lateinit var coordinator: VerificationCoordinator

    private val sampleMedicine = Medicine(
        id = "med_123",
        name = "Amlodipine",
        dosageText = "5mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(9, 0)),
        startDate = 1000L,
        initialStock = 10,
        currentStock = 10,
        baselineEmptyCells = 0,
        baselinePhotoPath = "file:///baseline.jpg"
    )

    private val now = 2000L
    private val sampleDose = DoseEvent(
        id = "dose_abc",
        medicineId = "med_123",
        scheduledAt = 1000L,
        windowEndsAt = 5000L, // valid intake window
        status = DoseStatus.PENDING,
        verificationAttempts = 0
    )

    private val dummyBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        recordVerifiedDoseUseCase = RecordVerifiedDoseUseCase(
            doseRepository = doseRepository,
            medicineRepository = medicineRepository,
            alarmScheduler = alarmScheduler,
            processStockDecrementUseCase = processStockDecrementUseCase
        )

        coordinator = VerificationCoordinator(
            doseRepository = doseRepository,
            medicineRepository = medicineRepository,
            verificationRepository = verificationRepository,
            caregiverRepository = caregiverRepository,
            outboxRepository = outboxRepository,
            userPreferences = userPreferences,
            verificationPipeline = verificationPipeline,
            recordVerifiedDoseUseCase = recordVerifiedDoseUseCase,
            processMissedDoseUseCase = processMissedDoseUseCase,
            alarmScheduler = alarmScheduler
        )

        coEvery { doseRepository.getById("dose_abc") } returns sampleDose
        coEvery { medicineRepository.getById("med_123") } returns sampleMedicine
        coEvery { verificationRepository.getRecentAttemptsForMedicine(any(), any()) } returns emptyList()
        coEvery { doseRepository.getLatestVerifiedDose("med_123") } returns null
        coEvery { userPreferences.currentUserId } returns flowOf("user_1")
    }

    @Test
    fun passVerification_recordsAttempt_updatesDoseTaken_andDecrementsStock() = runTest {
        coEvery {
            verificationPipeline.verify(
                bitmap = any(),
                medicine = any(),
                lastVerifiedEmptyCells = any(),
                existingHashes = any(),
                currentHashOverride = any()
            )
        } returns VerificationResult(
            passed = true,
            emptyCellsAfter = 1,
            friendlyMessage = "Dose verified"
        )

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Success)
        val success = result as CoordinatorResult.Success
        assertEquals(1, success.emptyCellsAfter)
        assertFalse(success.isLate)

        // Verify attempt was recorded
        val attemptSlot = slot<VerificationAttempt>()
        coVerify { verificationRepository.recordAttempt(capture(attemptSlot)) }
        assertEquals(VerificationResultType.PASSED, attemptSlot.captured.result)

        // Verify dose was transitioned to TAKEN
        val doseSlot = slot<DoseEvent>()
        coVerify { doseRepository.update(capture(doseSlot)) }
        assertEquals(DoseStatus.TAKEN, doseSlot.captured.status)
        assertEquals(now, doseSlot.captured.takenAt)
        assertEquals(1, doseSlot.captured.emptyCellsAfter)

        // Verify stock decremented via processStockDecrementUseCase
        coVerify { processStockDecrementUseCase("med_123", 1, any()) }
    }

    @Test
    fun failedVerification_attempt1_recordsAttempt_incrementsAttempts_allowsRetake() = runTest {
        coEvery {
            verificationPipeline.verify(
                bitmap = any(),
                medicine = any(),
                lastVerifiedEmptyCells = any(),
                existingHashes = any(),
                currentHashOverride = any()
            )
        } returns VerificationResult(
            passed = false,
            rejectReason = RejectReason.WRONG_PILL_COUNT,
            friendlyMessage = "Incorrect pill count"
        )

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Failed)
        val failed = result as CoordinatorResult.Failed
        assertEquals(RejectReason.WRONG_PILL_COUNT, failed.reason)
        assertEquals(2, failed.attemptsRemaining) // 3 - 1 = 2 remaining

        // Verify dose updated with 1 attempt
        val doseSlot = slot<DoseEvent>()
        coVerify { doseRepository.update(capture(doseSlot)) }
        assertEquals(1, doseSlot.captured.verificationAttempts)
        assertEquals(DoseStatus.PENDING, doseSlot.captured.status) // Not TAKEN!

        // Stock NOT decremented
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
    }

    @Test
    fun thirdFailedAttempt_exhaustsRetries_transitionsToUnverified_andEnqueuesCaregiverOutbox() = runTest {
        // Dose already has 2 attempts
        val doseWith2Attempts = sampleDose.copy(verificationAttempts = 2)
        coEvery { doseRepository.getById("dose_abc") } returns doseWith2Attempts
        coEvery { caregiverRepository.hasConnectedCaregiver("user_1") } returns true

        coEvery {
            verificationPipeline.verify(
                bitmap = any(),
                medicine = any(),
                lastVerifiedEmptyCells = any(),
                existingHashes = any(),
                currentHashOverride = any()
            )
        } returns VerificationResult(
            passed = false,
            rejectReason = RejectReason.PILLS_NOT_REMOVED,
            friendlyMessage = "No pills were removed"
        )

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Exhausted)
        val exhausted = result as CoordinatorResult.Exhausted
        assertEquals(RejectReason.PILLS_NOT_REMOVED, exhausted.reason)

        // Dose transitioned to UNVERIFIED with 3 attempts
        val doseSlot = slot<DoseEvent>()
        coVerify { doseRepository.update(capture(doseSlot)) }
        assertEquals(DoseStatus.UNVERIFIED, doseSlot.captured.status)
        assertEquals(3, doseSlot.captured.verificationAttempts)

        // Outbox event enqueued for connected caregiver
        val outboxSlot = slot<NotificationOutbox>()
        coVerify { outboxRepository.enqueue(capture(outboxSlot)) }
        assertEquals(NotifType.DOSE_UNVERIFIED, outboxSlot.captured.type)
        assertTrue(outboxSlot.captured.payloadJson.contains("PILLS_NOT_REMOVED"))

        // Stock NOT decremented
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
    }

    @Test
    fun thirdFailedAttempt_withoutCaregiver_doesNotEnqueueOutbox() = runTest {
        val doseWith2Attempts = sampleDose.copy(verificationAttempts = 2)
        coEvery { doseRepository.getById("dose_abc") } returns doseWith2Attempts
        coEvery { caregiverRepository.hasConnectedCaregiver("user_1") } returns false

        coEvery {
            verificationPipeline.verify(
                bitmap = any(),
                medicine = any(),
                lastVerifiedEmptyCells = any(),
                existingHashes = any(),
                currentHashOverride = any()
            )
        } returns VerificationResult(
            passed = false,
            rejectReason = RejectReason.STRIP_NOT_FULLY_VISIBLE
        )

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Exhausted)
        coVerify(exactly = 0) { outboxRepository.enqueue(any()) }
    }

    @Test
    fun alreadyTakenDose_returnsError_andDoesNotVerify() = runTest {
        val takenDose = sampleDose.copy(status = DoseStatus.TAKEN)
        coEvery { doseRepository.getById("dose_abc") } returns takenDose

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Error)
        coVerify(exactly = 0) { verificationPipeline.verify(any(), any(), any(), any(), any()) }
    }

    @Test
    fun windowExpiredDuringVerification_routesToLatePolicy() = runTest {
        // Window ended at 1500L, now is 2000L
        val expiredDose = sampleDose.copy(windowEndsAt = 1500L)
        coEvery { doseRepository.getById("dose_abc") } returns expiredDose
        coEvery { doseRepository.getNextScheduledDose("med_123", 1000L) } returns null

        coEvery {
            verificationPipeline.verify(
                bitmap = any(),
                medicine = any(),
                lastVerifiedEmptyCells = any(),
                existingHashes = any(),
                currentHashOverride = any()
            )
        } returns VerificationResult(passed = true, emptyCellsAfter = 1)

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = 2000L)

        // LateEntryPolicy allows logging late within cutoff
        assertTrue(result is CoordinatorResult.Success)
        val success = result as CoordinatorResult.Success
        assertTrue(success.isLate)

        val doseSlot = slot<DoseEvent>()
        coVerify { doseRepository.update(capture(doseSlot)) }
        assertEquals(DoseStatus.TAKEN_LATE, doseSlot.captured.status)
        assertTrue(doseSlot.captured.lateEntry)
    }

    @Test
    fun modelNotInstalled_returnsGracefulUnavailableMessage_andDoesNotMarkDoseTaken() = runTest {
        coEvery {
            verificationPipeline.verify(
                bitmap = any(),
                medicine = any(),
                lastVerifiedEmptyCells = any(),
                existingHashes = any(),
                currentHashOverride = any()
            )
        } throws IllegalStateException("Model assets/strip_detector.tflite not installed.")

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Error)
        val error = result as CoordinatorResult.Error
        assertTrue(error.message.contains("temporarily unavailable"))

        // Dose is NOT marked TAKEN, stock NOT decremented
        coVerify(exactly = 0) { doseRepository.update(match { it.status == DoseStatus.TAKEN }) }
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
    }

    @Test
    fun bypassAttempt_onUnverifiedDose_failsStateTransition() = runTest {
        // A dose in UNVERIFIED status cannot be marked TAKEN by RecordVerifiedDoseUseCase
        val unverifiedDose = sampleDose.copy(status = DoseStatus.UNVERIFIED)
        coEvery { doseRepository.getById("dose_abc") } returns unverifiedDose

        val bypassResult = recordVerifiedDoseUseCase("dose_abc", "/path/photo.jpg", 1, now)

        assertTrue(bypassResult.isFailure)
        coVerify(exactly = 0) { doseRepository.update(match { it.status == DoseStatus.TAKEN }) }
        coVerify(exactly = 0) { processStockDecrementUseCase(any(), any(), any()) }
    }

    @Test
    fun lateDose_exhaustingAttempts_remainsMissed_andDoesNotSendOutbox() = runTest {
        val missedDose = sampleDose.copy(status = DoseStatus.MISSED, verificationAttempts = 2)
        coEvery { doseRepository.getById("dose_abc") } returns missedDose
        coEvery { caregiverRepository.hasConnectedCaregiver("user_1") } returns true

        coEvery {
            verificationPipeline.verify(
                bitmap = any(),
                medicine = any(),
                lastVerifiedEmptyCells = any(),
                existingHashes = any(),
                currentHashOverride = any()
            )
        } returns VerificationResult(
            passed = false,
            rejectReason = RejectReason.PILLS_NOT_REMOVED
        )

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Exhausted)
        val doseSlot = slot<DoseEvent>()
        coVerify { doseRepository.update(capture(doseSlot)) }
        assertEquals(DoseStatus.MISSED, doseSlot.captured.status)
        assertEquals(3, doseSlot.captured.verificationAttempts)

        // No DOSE_UNVERIFIED outbox event is enqueued for missed dose
        coVerify(exactly = 0) { outboxRepository.enqueue(any()) }
    }

    @Test
    fun verify_beforeScheduledAt_rejectsWithTooEarly() = runTest {
        val futureDose = sampleDose.copy(scheduledAt = 2000L + 2 * 60 * 60 * 1000L)
        coEvery { doseRepository.getById("dose_abc") } returns futureDose

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = 2000L)

        assertTrue(result is CoordinatorResult.Failed)
        assertEquals(RejectReason.TOO_EARLY, (result as CoordinatorResult.Failed).reason)
        coVerify(exactly = 0) { verificationPipeline.verify(any(), any(), any(), any(), any()) }
    }

    @Test
    fun verify_blurryQualityRejection_doesNotConsumeAttempts() = runTest {
        coEvery {
            verificationPipeline.verify(any(), any(), any(), any(), any())
        } returns VerificationResult(passed = false, rejectReason = RejectReason.BLURRY)

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Failed)
        assertEquals(RejectReason.BLURRY, (result as CoordinatorResult.Failed).reason)
        assertEquals(3, (result as CoordinatorResult.Failed).attemptsRemaining) // 0 attempts used, 3 remaining!
    }

    @Test
    fun verify_structuredFailureReason_carriesFailureReasonAndRetryHint() = runTest {
        coEvery {
            verificationPipeline.verify(any(), any(), any(), any(), any())
        } returns VerificationResult(
            passed = false,
            rejectReason = RejectReason.BLURRY,
            failureReason = VerificationFailureReason.IMAGE_BLURRY,
            friendlyMessage = "The blister pack is blurry.",
            retryHint = "Hold phone steady and tap to focus."
        )

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue(result is CoordinatorResult.Failed)
        val failed = result as CoordinatorResult.Failed
        assertEquals(RejectReason.BLURRY, failed.reason)
        assertEquals(VerificationFailureReason.IMAGE_BLURRY, failed.failureReason)
        assertEquals("IMAGE_BLURRY", failed.failureReason?.code)
        assertEquals("Hold phone steady and tap to focus.", failed.retryHint)
        assertEquals(3, failed.attemptsRemaining) // Quality gate: strikes preserved!
    }

    @Test
    fun verify_modelMissingException_returnsCoordinatorError_neverPasses() = runTest {
        coEvery { doseRepository.getById("dose_abc") } returns sampleDose
        coEvery { medicineRepository.getById("med_123") } returns sampleMedicine
        coEvery {
            verificationPipeline.verify(any(), any(), any(), any(), any())
        } throws IllegalStateException("Model assets/strip_detector.tflite not installed.")

        val result = coordinator.verify("dose_abc", "/path/photo.jpg", dummyBitmap, now = now)

        assertTrue("Missing model must result in CoordinatorResult.Error", result is CoordinatorResult.Error)
        assertTrue((result as CoordinatorResult.Error).message.contains("Verification temporarily unavailable"))
        // Proves dose status was NOT marked taken
        coVerify(exactly = 0) { doseRepository.update(match { it.status == DoseStatus.TAKEN }) }
    }
}
