package com.meditrack.app.feature.medicine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import com.meditrack.app.feature.verification.pipeline.Cell
import com.meditrack.app.feature.verification.pipeline.CellState
import com.meditrack.app.feature.verification.pipeline.Detection
import com.meditrack.app.feature.verification.pipeline.StripDetector
import com.meditrack.app.feature.verification.pipeline.VerificationPipeline
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
class PartlyUsedStripEnrollmentTest {

    private val testDispatcher = StandardTestDispatcher()
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val saveMedicineUseCase: SaveMedicineUseCase = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val stripDetector: StripDetector = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { userPreferences.currentUserId } returns flowOf("patient_123")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSharpBitmap(): Bitmap {
        val width = 200
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isEven = ((x / 4) + (y / 4)) % 2 == 0
                bitmap.setPixel(x, y, if (isEven) Color.rgb(30, 30, 30) else Color.rgb(200, 200, 200))
            }
        }
        return bitmap
    }

    @Test
    fun partlyUsedStrip_enrolledWith2EmptyBlisters_verifiesFirstDoseSuccessfully() = runTest {
        // 1. Setup ViewModel and fill Medicine form
        val viewModel = AddEditMedicineViewModel(
            medicineRepository = medicineRepository,
            saveMedicineUseCase = saveMedicineUseCase,
            userPreferences = userPreferences,
            stripDetector = stripDetector
        )

        viewModel.onNameChange("Amlodipine")
        viewModel.onDosageChange("5mg")
        viewModel.onPillsPerDoseChange("1")
        viewModel.onInitialStockChange("28")
        viewModel.onFrequencyTypeChange(FrequencyType.DAILY)

        viewModel.onContinueToPhoto()
        assertEquals(AddEditStep.ENROLLMENT_PHOTO, viewModel.uiState.value.currentStep)

        // 2. Patient captures enrollment photo of partly-used strip (2 blisters already empty)
        val baselinePhotoPath = "/data/user/0/com.meditrack.app/files/baseline/amlodipine_enroll.jpg"
        viewModel.onPhotoCapturedWithLedger(
            photoPath = baselinePhotoPath,
            baselineEmpty = 2
        )

        assertEquals(baselinePhotoPath, viewModel.uiState.value.baselinePhotoPath)
        assertEquals(2, viewModel.uiState.value.baselineEmptyCells)

        // 3. Confirm and save medicine
        val savedMedicineSlot = slot<Medicine>()
        coEvery { saveMedicineUseCase.saveNewMedicine(capture(savedMedicineSlot), any(), any()) } returns listOf(
            DoseEvent(id = "dose_1", medicineId = "med_amlodipine", scheduledAt = 1000L, windowEndsAt = 2000L, status = DoseStatus.PENDING)
        )

        viewModel.onConfirmPhotoAndSave()
        advanceUntilIdle()

        assertEquals(AddEditStep.SUCCESS, viewModel.uiState.value.currentStep)
        val enrolledMedicine = savedMedicineSlot.captured
        assertEquals("Enrolled medicine must persist baselineEmptyCells = 2", 2, enrolledMedicine.baselineEmptyCells)
        assertEquals(baselinePhotoPath, enrolledMedicine.baselinePhotoPath)
        assertEquals(1, enrolledMedicine.pillsPerDose)

        // 4. First dose taken: patient pops 1 pill out of strip and takes verification photo
        // Detection now finds 3 empty cells (2 baseline + 1 taken dose)
        val sharpVerificationPhoto = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(box = RectF(0.2f, 0.2f, 0.3f, 0.3f), state = CellState.EMPTY, confidence = 0.95f),
                Cell(box = RectF(0.4f, 0.2f, 0.5f, 0.3f), state = CellState.EMPTY, confidence = 0.95f),
                Cell(box = RectF(0.6f, 0.2f, 0.7f, 0.3f), state = CellState.EMPTY, confidence = 0.95f), // Newly popped pill!
                Cell(box = RectF(0.2f, 0.5f, 0.3f, 0.6f), state = CellState.FULL, confidence = 0.95f),
                Cell(box = RectF(0.4f, 0.5f, 0.5f, 0.6f), state = CellState.FULL, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.98f
        )

        // 5. Verification pipeline executes for first dose (lastVerifiedEmptyCells is null)
        val pipeline = VerificationPipeline(stripDetector)
        val verificationResult = pipeline.verify(
            bitmap = sharpVerificationPhoto,
            medicine = enrolledMedicine,
            lastVerifiedEmptyCells = null
        )

        // Delta calculation: newEmpty (3) - baselineEmptyCells (2) = 1 == pillsPerDose (1) -> PASS!
        assertTrue("First dose verification on partly-used strip must pass", verificationResult.passed)
        assertNull(verificationResult.rejectReason)
        assertEquals(3, verificationResult.emptyCellsAfter)
    }
}
