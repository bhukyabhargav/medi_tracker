package com.meditrack.app.feature.verification.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.feature.verification.quality.PerceptualHasher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerificationPipelineTest {

    private val stripDetector: StripDetector = mockk(relaxed = true)
    private lateinit var pipeline: VerificationPipeline

    private val sampleMedicine = Medicine(
        id = "med_1",
        name = "Paracetamol",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = System.currentTimeMillis(),
        initialStock = 10,
        currentStock = 10,
        baselineEmptyCells = 0,
        baselinePhotoPath = "file:///baseline.jpg"
    )

    @Before
    fun setUp() {
        pipeline = VerificationPipeline(stripDetector)
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
    fun blurryImage_failsQualityGate_andDoesNotCallDetector() = runTest {
        // Uniform gray image is blurry
        val blurryBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                blurryBitmap.setPixel(x, y, Color.rgb(128, 128, 128))
            }
        }

        val result = pipeline.verify(blurryBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
        coVerify(exactly = 0) { stripDetector.detect(any()) }
    }

    @Test
    fun tooDarkImage_failsQualityGate_andDoesNotCallDetector() = runTest {
        val darkBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                darkBitmap.setPixel(x, y, Color.rgb(5, 5, 5))
            }
        }

        val result = pipeline.verify(darkBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.TOO_DARK, result.rejectReason)
        coVerify(exactly = 0) { stripDetector.detect(any()) }
    }

    @Test
    fun duplicatePhoto_failsCheck_andDoesNotCallDetector() = runTest {
        val sharpBitmap = createSharpBitmap()
        val hash = PerceptualHasher.computeHash(sharpBitmap)

        val result = pipeline.verify(
            bitmap = sharpBitmap,
            medicine = sampleMedicine,
            lastVerifiedEmptyCells = 0,
            existingHashes = listOf(hash)
        )

        assertFalse(result.passed)
        assertEquals(RejectReason.DUPLICATE_PHOTO, result.rejectReason)
        coVerify(exactly = 0) { stripDetector.detect(any()) }
    }

    @Test
    fun stripNotFullyInFrame_failsCheck() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = emptyList(),
            stripFullyInFrame = false
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
    }

    @Test
    fun noCellsDetected_failsStripNotFullyVisible() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = emptyList(),
            stripFullyInFrame = true
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
    }

    @Test
    fun zeroPillsRemoved_failsPillsNotRemoved() = runTest {
        val sharpBitmap = createSharpBitmap()
        // lastVerified was 0 empty cells, current has 0 empty cells -> delta = 0
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.PILLS_NOT_REMOVED, result.rejectReason)
    }

    @Test
    fun wrongPillCount_deltaDoesNotMatchPillsPerDose_failsWrongPillCount() = runTest {
        val sharpBitmap = createSharpBitmap()
        // lastVerified = 0, pillsPerDose = 1, but newEmpty = 3 -> delta = 3 != 1
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
    }

    @Test
    fun partialCells_triggerRetakeGuard_perStage13A1() = runTest {
        // Rewritten per Stage 13 Problem A1: cell_partial was never trained/verified in model.
        // Ambiguous PARTIAL detection must trigger retake (WRONG_PILL_COUNT), not silently count as FULL.
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.PARTIAL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
    }

    @Test
    fun lowConfidenceCells_triggerConservativeRejection_perStage13A6() = runTest {
        // Rewritten per Stage 13 Problem A6: any cell confidence below VerificationConfig threshold
        // must conservatively reject with a retake message (RejectReason.BLURRY) instead of passing.
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.30f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
    }

    @Test
    fun nullLastVerifiedDose_usesMedicineBaselineEmptyCells() = runTest {
        val sharpBitmap = createSharpBitmap()
        val medWithBaseline = sampleMedicine.copy(baselineEmptyCells = 2)

        // newEmpty = 3, delta = 3 - 2 = 1 == pillsPerDose (1) -> PASS
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        val result = pipeline.verify(sharpBitmap, medWithBaseline, lastVerifiedEmptyCells = null)

        assertTrue(result.passed)
        assertEquals(3, result.emptyCellsAfter)
    }

    @Test
    fun photograph_passesVerification_withoutAnyMedicineNameOcr() = runTest {
        val sharpBitmap = createSharpBitmap()
        // Standard valid strip detection: 1 empty, 1 full. Delta = 1 == pillsPerDose
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        // Passes strictly based on quality, uniqueness, framing, and ledger delta
        assertTrue("Verification must pass without any OCR text extraction", result.passed)
        assertNull(result.rejectReason)
        assertEquals(1, result.emptyCellsAfter)
    }

    @Test
    fun hiddenOrCroppedOrUnreadableMedicineName_cannotCauseRejection() = runTest {
        val sharpBitmap = createSharpBitmap()
        // Blister strip with no visible text / cropped text
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.90f),
                Cell(state = CellState.FULL, confidence = 0.90f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        // The pipeline does not inspect or require printed text
        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertTrue("Cropped or unreadable name on strip must not reject", result.passed)
        assertNull(result.rejectReason)
    }

    @Test
    fun noMedicineNameComparison_isExecutedDuringDoseVerification() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        // Medicine name could be anything; scheduled medicine identity comes from DB record
        val medicineWithUnusualName = sampleMedicine.copy(name = "X99 Special Formula Complex")
        val result = pipeline.verify(sharpBitmap, medicineWithUnusualName, lastVerifiedEmptyCells = 0)

        assertTrue(result.passed)
        assertEquals(1, result.emptyCellsAfter)
    }

    @Test
    fun lowStripConfidence_rejectsWithStripNotFullyVisible() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.30f // Below VerificationConfig.confidenceThreshold (0.50f)
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)
        assertFalse(result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
    }

    @Test
    fun lowCellConfidence_rejectsWithBlurry() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.40f) // Below minCellConfidence (0.60f)
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)
        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
    }

    @Test
    fun partialCellDetected_rejectsWithWrongPillCount() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.PARTIAL, confidence = 0.90f) // Ambiguous partial/damaged pill
            ),
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)
        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
    }

    @Test
    fun cellCountDeviation_exceedsTolerance_rejectsWithWrongPillCount() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), // Total 2 cavities
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )

        // Previous scan had 10 cavities. Difference = 8 > tolerance (1)
        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0, previousTotalCells = 10)
        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
    }

    @Test
    fun allRejectReasons_areValidAndDefined() {
        val expectedReasons = setOf(
            RejectReason.BLURRY,
            RejectReason.TOO_DARK,
            RejectReason.TOO_BRIGHT,
            RejectReason.GLARE,
            RejectReason.STRIP_NOT_FULLY_VISIBLE,
            RejectReason.PILLS_NOT_REMOVED,
            RejectReason.WRONG_PILL_COUNT,
            RejectReason.DUPLICATE_PHOTO,
            RejectReason.TOO_EARLY,
            RejectReason.WRONG_MEDICINE
        )
        assertEquals(expectedReasons, RejectReason.values().toSet())
    }

    @Test
    fun singleWeakCellAmongManyReliableCells_isNotRejected() = runTest {
        // Real foil strips regularly have one cell at 0.45-0.60. That must NOT reject the whole photo.
        val sharpBitmap = createSharpBitmap()
        val cells = buildList {
            add(Cell(state = CellState.EMPTY, confidence = 0.52f)) // weaker but still a valid cell
            add(Cell(state = CellState.EMPTY, confidence = 0.80f))
            repeat(8) { add(Cell(state = CellState.FULL, confidence = 0.85f)) }
        }
        coEvery { stripDetector.detect(any()) } returns Detection(cells = cells, stripFullyInFrame = true)

        val medicine = sampleMedicine.copy(baselineEmptyCells = 1)
        val result = pipeline.verify(sharpBitmap, medicine, lastVerifiedEmptyCells = 1)

        // 2 empty now, 1 before, pillsPerDose = 1 -> PASS
        assertTrue(result.passed)
    }

    @Test
    fun manyUnreliableCells_areStillRejectedAsBlurry() = runTest {
        val sharpBitmap = createSharpBitmap()
        val cells = buildList {
            repeat(6) { add(Cell(state = CellState.FULL, confidence = 0.50f)) }
            repeat(4) { add(Cell(state = CellState.FULL, confidence = 0.90f)) }
        }
        coEvery { stripDetector.detect(any()) } returns Detection(cells = cells, stripFullyInFrame = true)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
    }
}
