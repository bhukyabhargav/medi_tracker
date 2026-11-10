package com.meditrack.app.feature.verification

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.BuildConfig
import com.meditrack.app.core.util.BitmapLoader
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.RecordManualUnverifiedUseCase
import com.meditrack.app.domain.usecase.RecordVerifiedDoseUseCase
import com.meditrack.app.domain.usecase.SkipDoseUseCase
import com.meditrack.app.domain.usecase.SnoozeDoseUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import com.meditrack.app.feature.verification.pipeline.CoordinatorResult
import com.meditrack.app.feature.verification.pipeline.Detection
import com.meditrack.app.feature.verification.pipeline.FakeStripDetector
import com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior
import com.meditrack.app.feature.verification.pipeline.StripDetector
import com.meditrack.app.feature.verification.pipeline.VerificationCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.File
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerificationBypassSecurityTest {

    private val testDispatcher = StandardTestDispatcher()

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val verificationCoordinator: VerificationCoordinator = mockk(relaxed = true)
    private val recordVerifiedDoseUseCase: RecordVerifiedDoseUseCase = mockk(relaxed = true)
    private val recordManualUnverifiedUseCase: RecordManualUnverifiedUseCase = mockk(relaxed = true)
    private val skipDoseUseCase: SkipDoseUseCase = mockk(relaxed = true)
    private val snoozeDoseUseCase: SnoozeDoseUseCase = mockk(relaxed = true)
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val fakeStripDetector = FakeStripDetector()
    private val stripDetector: StripDetector = mockk(relaxed = true)

    private val sampleMedicine = Medicine(
        id = "med_1",
        name = "Metformin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = 1000L,
        foodRelation = FoodRelation.AFTER,
        initialStock = 60,
        currentStock = 50,
        baselinePhotoPath = "trusted_baseline.jpg",
        baselineEmptyCells = 2
    )

    private val sampleDose = DoseEvent(
        id = "dose_1",
        medicineId = "med_1",
        scheduledAt = System.currentTimeMillis() + 60_000L, // 1 min in future (pending)
        windowEndsAt = System.currentTimeMillis() + 3_600_000L,
        status = DoseStatus.PENDING,
        updatedAt = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { doseRepository.getById("dose_1") } returns sampleDose
        coEvery { medicineRepository.getById("med_1") } returns sampleMedicine
        mockkObject(BitmapLoader)
        every { BitmapLoader.decodeSampled(any<String>(), any<Int>()) } answers {
            Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): VerificationViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("doseId" to "dose_1"))
        return VerificationViewModel(
            savedStateHandle = savedStateHandle,
            doseRepository = doseRepository,
            medicineRepository = medicineRepository,
            verificationCoordinator = verificationCoordinator,
            recordVerifiedDoseUseCase = recordVerifiedDoseUseCase,
            recordManualUnverifiedUseCase = recordManualUnverifiedUseCase,
            skipDoseUseCase = skipDoseUseCase,
            snoozeDoseUseCase = snoozeDoseUseCase,
            alarmScheduler = alarmScheduler,
            fakeStripDetector = fakeStripDetector,
            stripDetector = stripDetector
        )
    }

    @Test
    fun useRejectedPhotoAnyway_whenTooEarly_isBlocked() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Create a valid photo on disk that decodes properly
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photoFile = File(context.filesDir, "rejected_photo.jpg")
        val bmp = android.graphics.Bitmap.createBitmap(50, 50, android.graphics.Bitmap.Config.ARGB_8888)
        photoFile.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }

        // Mock verification coordinator to fail with a retryable non-early reason
        coEvery { verificationCoordinator.verify(any(), any(), any(), any()) } returns CoordinatorResult.Failed(
            reason = RejectReason.WRONG_PILL_COUNT,
            attemptsRemaining = 2,
            friendlyMessage = "Wrong pill count"
        )

        // Set scheduled time way in the future (> early tolerance)
        val futureDose = sampleDose.copy(scheduledAt = System.currentTimeMillis() + 7_200_000L) // 2 hours in future
        coEvery { doseRepository.getById("dose_1") } returns futureDose

        // Process captured photo so lastRejectedPhotoPath is populated
        vm.processCapturedPhoto(photoFile.absolutePath)
        advanceUntilIdle()

        // Attempt "Use Photo Anyway" when TOO_EARLY
        vm.useRejectedPhotoAnyway()
        advanceUntilIdle()

        // Must be blocked
        val state = vm.uiState.value
        assertNotNull("Must set error message", state.errorMessage)
        assertTrue("Error must mention too early", state.errorMessage!!.contains("too early", ignoreCase = true))
        coVerify(exactly = 0) { recordVerifiedDoseUseCase(any(), any(), any(), any(), any()) }

        photoFile.delete()
    }

    @Test
    fun useRejectedPhotoAnyway_whenPhotoMissing_isBlocked() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Call with a non-existent photo path
        vm.useRejectedPhotoAnyway()
        advanceUntilIdle()

        coVerify(exactly = 0) { recordVerifiedDoseUseCase(any(), any(), any(), any(), any()) }
    }

    @Test
    fun useRejectedPhotoAnyway_whenDoseAlreadyTaken_isBlocked() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val photoFile = File(context.filesDir, "rejected_photo_closed.jpg")
        val bmp = android.graphics.Bitmap.createBitmap(50, 50, android.graphics.Bitmap.Config.ARGB_8888)
        photoFile.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }

        coEvery { verificationCoordinator.verify(any(), any(), any(), any()) } returns CoordinatorResult.Failed(
            reason = RejectReason.WRONG_PILL_COUNT,
            attemptsRemaining = 2,
            friendlyMessage = "Wrong pill count"
        )

        // Dose is already TAKEN
        val takenDose = sampleDose.copy(status = DoseStatus.TAKEN)
        coEvery { doseRepository.getById("dose_1") } returns takenDose

        vm.processCapturedPhoto(photoFile.absolutePath)
        advanceUntilIdle()

        vm.useRejectedPhotoAnyway()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Dose has already been recorded.", state.errorMessage)
        coVerify(exactly = 0) { recordVerifiedDoseUseCase(any(), any(), any(), any(), any()) }
        photoFile.delete()
    }

    @Test
    fun enrollment_decodeFailure_neverSavesBaseline_trustedBaselinePreserved() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Point to invalid/non-existent photo path
        vm.recordBaselineEnrollment("/invalid/non_existent/path.jpg")
        advanceUntilIdle()

        // Medicine must NOT have updated in repository
        coVerify(exactly = 0) { medicineRepository.update(any()) }
        assertEquals(
            "Original trusted baseline must remain unchanged",
            "trusted_baseline.jpg",
            sampleMedicine.baselinePhotoPath
        )
    }

    @Test
    fun enrollment_detectorThrows_neverSavesBaseline() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Create valid bitmap file
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photoFile = File(context.filesDir, "baseline_test.jpg").apply {
            val bmp = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        }

        // Detector throws unexpected runtime exception
        coEvery { stripDetector.detect(any()) } throws RuntimeException("Native TFLite fault")

        vm.recordBaselineEnrollment(photoFile.absolutePath)
        advanceUntilIdle()

        coVerify(exactly = 0) { medicineRepository.update(any()) }
        photoFile.delete()
    }

    @Test
    fun enrollment_validatorFails_neverSavesBaseline() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val photoFile = File(context.filesDir, "baseline_empty.jpg").apply {
            val bmp = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        }

        // Detector returns zero cells (empty)
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = emptyList(),
            stripFullyInFrame = false,
            stripConfidence = 0.1f,
            stripBox = null
        )

        vm.recordBaselineEnrollment(photoFile.absolutePath)
        advanceUntilIdle()

        coVerify(exactly = 0) { medicineRepository.update(any()) }
        photoFile.delete()
    }

    @Test
    fun confirmNewStripAndVerify_usesMeasuredEmptyCount_andResetsBaselineLedger() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val photoFile = File(context.filesDir, "new_strip_test.jpg").apply {
            val bmp = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        }

        coEvery { verificationCoordinator.verify(any(), any(), any(), any()) } returns CoordinatorResult.Failed(
            reason = RejectReason.WRONG_PILL_COUNT,
            attemptsRemaining = 1,
            friendlyMessage = "This looks like a different or new strip."
        )

        val cells = listOf(
            com.meditrack.app.feature.verification.pipeline.Cell(state = com.meditrack.app.feature.verification.pipeline.CellState.EMPTY, confidence = 0.9f),
            com.meditrack.app.feature.verification.pipeline.Cell(state = com.meditrack.app.feature.verification.pipeline.CellState.EMPTY, confidence = 0.9f),
            com.meditrack.app.feature.verification.pipeline.Cell(state = com.meditrack.app.feature.verification.pipeline.CellState.EMPTY, confidence = 0.9f),
            com.meditrack.app.feature.verification.pipeline.Cell(state = com.meditrack.app.feature.verification.pipeline.CellState.FULL, confidence = 0.9f)
        )
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = cells,
            stripFullyInFrame = true,
            stripConfidence = 0.95f
        )
        coEvery { recordVerifiedDoseUseCase(any(), any(), any(), any(), any()) } returns Result.success(Unit)

        // Capture photo to trigger rejection and populate lastRejectedPhotoPath
        vm.processCapturedPhoto(photoFile.absolutePath)
        advanceUntilIdle()

        // Confirm new strip
        vm.confirmNewStripAndVerify()
        advanceUntilIdle()

        val medSlot = io.mockk.slot<Medicine>()
        coVerify { medicineRepository.update(capture(medSlot)) }
        // Measured empty = 3, pillsPerDose = 1 => baseline empty = 3 - 1 = 2
        assertEquals(2, medSlot.captured.baselineEmptyCells)
        assertEquals(photoFile.absolutePath, medSlot.captured.baselinePhotoPath)
        assertNotNull(medSlot.captured.lastRefillAt)

        coVerify {
            recordVerifiedDoseUseCase(
                doseId = "dose_1",
                photoPath = photoFile.absolutePath,
                emptyCellsAfter = 3,
                takenAt = any()
            )
        }

        photoFile.delete()
    }
}
