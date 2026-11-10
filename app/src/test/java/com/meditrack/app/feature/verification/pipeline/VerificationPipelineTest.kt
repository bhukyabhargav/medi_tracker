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
import org.junit.Assert.assertNotNull
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


    /**
     * Builds a Detection whose cells sit on a regular 5-column grid with equal-sized boxes (like a real strip),
     * keeping the given states and confidences. Presence checks need realistic geometry.
     */
    private fun detectionOf(cells: List<Cell>, stripConfidence: Float? = 0.90f): Detection {
        val placed = cells.mapIndexed { i, c ->
            val col = i % 5
            val row = i / 5
            val left = 0.10f + col * 0.16f
            val top = 0.20f + row * 0.22f
            c.copy(box = android.graphics.RectF(left, top, left + 0.11f, top + 0.15f))
        }
        return Detection(cells = placed, stripFullyInFrame = true, stripConfidence = stripConfidence)
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
    fun insufficientResolution_failsLevel1QualityGate_andDoesNotCallDetector() = runTest {
        // Image smaller than globalMinDimensionPx (100) fails Level 1 immediately
        val tinyBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val result = pipeline.verify(tinyBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
        coVerify(exactly = 0) { stripDetector.detect(any()) }
    }

    @Test
    fun blurryStripWithSharpBackground_failsLevel2QualityGate_afterDetection() = runTest {
        val width = 300
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Background is sharp checkerboard
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isEven = ((x / 4) + (y / 4)) % 2 == 0
                bitmap.setPixel(x, y, if (isEven) Color.rgb(30, 30, 30) else Color.rgb(200, 200, 200))
            }
        }

        // Center blister area [75, 75, 225, 225] is solid gray (perfectly blurry)
        for (y in 75 until 225) {
            for (x in 75 until 225) {
                bitmap.setPixel(x, y, Color.rgb(128, 128, 128))
            }
        }

        // Detector locates the strip in the center
        val centerCells = listOf(
            Cell(box = android.graphics.RectF(0.30f, 0.30f, 0.40f, 0.42f), state = CellState.EMPTY, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.45f, 0.30f, 0.55f, 0.42f), state = CellState.FULL, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.60f, 0.30f, 0.70f, 0.42f), state = CellState.FULL, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.30f, 0.50f, 0.40f, 0.62f), state = CellState.FULL, confidence = 0.95f)
        )
        val detection = Detection(
            cells = centerCells,
            stripFullyInFrame = true,
            stripConfidence = 0.90f,
            stripBox = android.graphics.RectF(0.25f, 0.25f, 0.75f, 0.75f)
        )
        coEvery { stripDetector.detect(any()) } returns detection

        val result = pipeline.verify(bitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        // Must reject as BLURRY despite the sharp background!
        assertFalse("Sharp background must never rescue a blurry blister strip", result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
        coVerify(exactly = 1) { stripDetector.detect(any()) }
    }

    @Test
    fun sharpStripWithDarkBackground_passesLevel2QualityGate() = runTest {
        val width = 300
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Background is dark (table cloth, RGB 22)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, Color.rgb(22, 22, 22))
            }
        }

        // Center blister area [75, 75, 225, 225] is sharp and well-lit checkerboard
        for (y in 75 until 225) {
            for (x in 75 until 225) {
                val isEven = ((x / 4) + (y / 4)) % 2 == 0
                bitmap.setPixel(x, y, if (isEven) Color.rgb(35, 35, 35) else Color.rgb(200, 200, 200))
            }
        }

        val centerCells = listOf(
            Cell(box = android.graphics.RectF(0.30f, 0.30f, 0.40f, 0.42f), state = CellState.EMPTY, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.45f, 0.30f, 0.55f, 0.42f), state = CellState.FULL, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.60f, 0.30f, 0.70f, 0.42f), state = CellState.FULL, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.30f, 0.50f, 0.40f, 0.62f), state = CellState.FULL, confidence = 0.95f)
        )
        val detection = Detection(
            cells = centerCells,
            stripFullyInFrame = true,
            stripConfidence = 0.90f,
            stripBox = android.graphics.RectF(0.25f, 0.25f, 0.75f, 0.75f)
        )
        coEvery { stripDetector.detect(any()) } returns detection

        val result = pipeline.verify(bitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        // Must pass! Dark background does not fail when strip is sharp and well-lit
        assertTrue("Good strip must not be rejected for a dark background", result.passed)
        assertEquals(1, result.emptyCellsAfter)
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
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.90f)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.PILLS_NOT_REMOVED, result.rejectReason)
    }

    @Test
    fun wrongPillCount_deltaDoesNotMatchPillsPerDose_failsWrongPillCount() = runTest {
        val sharpBitmap = createSharpBitmap()
        // lastVerified = 0, pillsPerDose = 1, but newEmpty = 3 -> delta = 3 != 1
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.90f)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
    }

    @Test
    fun partialCells_triggerRetakeGuard_perStage13A1() = runTest {
        // Rewritten per Stage 13 Problem A1: cell_partial was never trained/verified in model.
        // Ambiguous PARTIAL detection must trigger retake (WRONG_PILL_COUNT), not silently count as FULL.
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.PARTIAL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.90f)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
    }

    @Test
    fun lowConfidenceCells_triggerConservativeRejection_perStage13A6() = runTest {
        // Rewritten per Stage 13 Problem A6: any cell confidence below VerificationConfig threshold
        // must conservatively reject with a retake message (RejectReason.BLURRY) instead of passing.
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.30f),
                Cell(state = CellState.FULL, confidence = 0.30f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.90f)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
    }

    @Test
    fun nullLastVerifiedDose_usesMedicineBaselineEmptyCells() = runTest {
        val sharpBitmap = createSharpBitmap()
        val medWithBaseline = sampleMedicine.copy(baselineEmptyCells = 2)

        // newEmpty = 3, delta = 3 - 2 = 1 == pillsPerDose (1) -> PASS
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.90f)

        val result = pipeline.verify(sharpBitmap, medWithBaseline, lastVerifiedEmptyCells = null)

        assertTrue(result.passed)
        assertEquals(3, result.emptyCellsAfter)
    }

    @Test
    fun photograph_passesVerification_withoutAnyMedicineNameOcr() = runTest {
        val sharpBitmap = createSharpBitmap()
        // Standard valid strip detection: 1 empty, 1 full. Delta = 1 == pillsPerDose
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.90f)

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
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.90f),
                Cell(state = CellState.FULL, confidence = 0.90f),
                Cell(state = CellState.FULL, confidence = 0.90f),
                Cell(state = CellState.FULL, confidence = 0.90f)
            ), stripConfidence = 0.90f)

        // The pipeline does not inspect or require printed text
        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertTrue("Cropped or unreadable name on strip must not reject", result.passed)
        assertNull(result.rejectReason)
    }

    @Test
    fun noMedicineNameComparison_isExecutedDuringDoseVerification() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.90f)

        // Medicine name could be anything; scheduled medicine identity comes from DB record
        val medicineWithUnusualName = sampleMedicine.copy(name = "X99 Special Formula Complex")
        val result = pipeline.verify(sharpBitmap, medicineWithUnusualName, lastVerifiedEmptyCells = 0)

        assertTrue(result.passed)
        assertEquals(1, result.emptyCellsAfter)
    }

    @Test
    fun lowStripConfidence_rejectsWithStripNotFullyVisible() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.30f)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)
        assertFalse(result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
    }

    @Test
    fun lowCellConfidence_rejectsWithBlurry() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.40f), // Below reliableCellConfidence
                Cell(state = CellState.FULL, confidence = 0.40f),
                Cell(state = CellState.EMPTY, confidence = 0.95f)
            ), stripConfidence = 0.90f)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)
        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
    }

    @Test
    fun partialCellDetected_rejectsWithWrongPillCount() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
                Cell(state = CellState.EMPTY, confidence = 0.95f),
                Cell(state = CellState.PARTIAL, confidence = 0.90f), // Ambiguous partial/damaged pill
                Cell(state = CellState.FULL, confidence = 0.95f),
                Cell(state = CellState.FULL, confidence = 0.95f)
            ), stripConfidence = 0.90f)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)
        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
    }

    @Test
    fun cellCountDeviation_exceedsTolerance_rejectsWithWrongPillCount() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f)
        ), stripConfidence = 0.90f) // Total 4 cavities

        // Previous scan had 10 cavities. Difference = 6 > tolerance (1)
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
        coEvery { stripDetector.detect(any()) } returns detectionOf(cells)

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
        coEvery { stripDetector.detect(any()) } returns detectionOf(cells)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.BLURRY, result.rejectReason)
    }

    // ----- Blister presence: ordinary photos must never be accepted as a blister strip -----

    private fun scatteredFalseDetection(): Detection {
        // What the model produces on a normal photo: a "strip" with decent confidence plus a few cells of
        // different sizes at irregular places.
        val boxes = listOf(
            android.graphics.RectF(0.10f, 0.10f, 0.22f, 0.25f),
            android.graphics.RectF(0.55f, 0.20f, 0.60f, 0.24f),
            android.graphics.RectF(0.30f, 0.62f, 0.52f, 0.90f),
            android.graphics.RectF(0.80f, 0.70f, 0.86f, 0.74f),
            android.graphics.RectF(0.40f, 0.05f, 0.47f, 0.13f)
        )
        return Detection(
            cells = boxes.map { Cell(box = it, state = CellState.FULL, confidence = 0.70f) },
            stripFullyInFrame = true,
            stripConfidence = 0.85f,
            stripBox = android.graphics.RectF(0f, 0f, 1f, 1f)
        )
    }

    @Test
    fun scatteredFalseCellsOnOrdinaryPhoto_areRejectedAsNoBlister() = runTest {
        coEvery { stripDetector.detect(any()) } returns scatteredFalseDetection()
        val result = pipeline.verify(createSharpBitmap(), sampleMedicine, lastVerifiedEmptyCells = 0)
        assertFalse(result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
    }

    @Test
    fun cellsWithoutGeometry_areRejectedAsNoBlister() = runTest {
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = List(6) { Cell(state = CellState.FULL, confidence = 0.95f) }, // all RectF() = no geometry
            stripFullyInFrame = true,
            stripConfidence = 0.90f
        )
        val result = pipeline.verify(createSharpBitmap(), sampleMedicine, lastVerifiedEmptyCells = 0)
        assertFalse(result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
    }

    @Test
    fun stripCutByBorder_withPopulatedCells_failsCheck() = runTest {
        val sharpBitmap = createSharpBitmap()
        val detection = detectionOf(listOf(
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f)
        )).copy(stripFullyInFrame = false) // Cut off by the border!

        coEvery { stripDetector.detect(any()) } returns detection

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse("Strip cut by border must fail even when cells were found", result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
    }

    @Test
    fun smallOffCentreStrip_passesVerification() = runTest {
        val sharpBitmap = createSharpBitmap()
        // Small 20% strip in bottom-right corner, well inside borders
        val cells = listOf(
            Cell(box = android.graphics.RectF(0.60f, 0.60f, 0.68f, 0.68f), state = CellState.EMPTY, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.72f, 0.60f, 0.80f, 0.68f), state = CellState.FULL, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.60f, 0.72f, 0.68f, 0.80f), state = CellState.FULL, confidence = 0.95f),
            Cell(box = android.graphics.RectF(0.72f, 0.72f, 0.80f, 0.80f), state = CellState.FULL, confidence = 0.95f)
        )
        val stripBox = android.graphics.RectF(0.58f, 0.58f, 0.82f, 0.82f)
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = cells,
            stripFullyInFrame = true,
            stripConfidence = 0.92f,
            stripBox = stripBox
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertTrue("Small off-centre strip within bounds must be accepted: ${result.friendlyMessage}", result.passed)
        assertEquals(1, result.emptyCellsAfter)
    }

    @Test
    fun rotatedStrip_passesVerification() = runTest {
        val sharpBitmap = createSharpBitmap()
        // Diagonal cells (rotated strip)
        val cells = (0 until 4).map { i ->
            val l = 0.20f + i * 0.12f
            val t = 0.20f + i * 0.12f
            val state = if (i == 0) CellState.EMPTY else CellState.FULL
            Cell(box = android.graphics.RectF(l, t, l + 0.08f, t + 0.08f), state = state, confidence = 0.92f)
        }
        val stripBox = android.graphics.RectF(0.18f, 0.18f, 0.60f, 0.60f)
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = cells,
            stripFullyInFrame = true,
            stripConfidence = 0.92f,
            stripBox = stripBox
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertTrue("Rotated strip within bounds must be accepted: ${result.friendlyMessage}", result.passed)
        assertEquals(1, result.emptyCellsAfter)
    }

    @Test
    fun allStructuredFailureReasons_haveNonEmptyCodeMessageAndHint() {
        for (reason in VerificationFailureReason.values()) {
            assertTrue("Code must not be blank for ${reason.name}", reason.code.isNotBlank())
            assertTrue("Message must not be blank for ${reason.name}", reason.userMessage.isNotBlank())
            assertTrue("Retry hint must not be blank for ${reason.name}", reason.retryHint.isNotBlank())
            assertNotNull("Must map to a valid RejectReason", reason.mappedRejectReason)
        }
    }

    @Test
    fun partiallyVisibleStrip_returnsStructuredBlisterPartiallyVisible() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = emptyList(),
            stripFullyInFrame = false,
            stripConfidence = 0.85f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
        assertEquals(VerificationFailureReason.BLISTER_PARTIALLY_VISIBLE, result.failureReason)
        assertEquals("BLISTER_PARTIALLY_VISIBLE", result.failureReason?.code)
        assertNotNull(result.retryHint)
        assertTrue(result.retryHint!!.contains("frame", ignoreCase = true) || result.retryHint!!.contains("edges", ignoreCase = true))
    }

    @Test
    fun presenceValidationFails_returnsStructuredBlisterNotFound() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns Detection(
            cells = emptyList(),
            stripFullyInFrame = true,
            stripConfidence = 0.20f
        )

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.STRIP_NOT_FULLY_VISIBLE, result.rejectReason)
        assertEquals(VerificationFailureReason.BLISTER_NOT_FOUND, result.failureReason)
        assertEquals("BLISTER_NOT_FOUND", result.failureReason?.code)
        assertNotNull(result.retryHint)
    }

    @Test
    fun partialCells_returnsStructuredAmbiguousCellDetection() = runTest {
        val sharpBitmap = createSharpBitmap()
        coEvery { stripDetector.detect(any()) } returns detectionOf(listOf(
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.PARTIAL, confidence = 0.90f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f)
        ), stripConfidence = 0.90f)

        val result = pipeline.verify(sharpBitmap, sampleMedicine, lastVerifiedEmptyCells = 0)

        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
        assertEquals(VerificationFailureReason.AMBIGUOUS_CELL_DETECTION, result.failureReason)
        assertEquals("AMBIGUOUS_CELL_DETECTION", result.failureReason?.code)
        assertNotNull(result.retryHint)
    }

    @Test
    fun firstDoseAfterEnrollment_withOneCellTolerance_passesVerification() = runTest {
        val sharpBitmap = createSharpBitmap()
        // N = 2 baseline empty cells, pillsPerDose = 2
        // Detection has N + pillsPerDose - 1 = 3 empty cells (delta = 1)
        val medicine = sampleMedicine.copy(
            baselineEmptyCells = 2,
            pillsPerDose = 2
        )
        val cells = listOf(
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f)
        )
        coEvery { stripDetector.detect(any()) } returns detectionOf(cells)

        val result = pipeline.verify(sharpBitmap, medicine, lastVerifiedEmptyCells = null)

        assertTrue("First dose with 1-cell tolerance must pass: ${result.friendlyMessage}", result.passed)
        assertEquals(3, result.emptyCellsAfter)
    }

    @Test
    fun firstDoseAfterEnrollment_noNewPillsRemoved_returnsPillsNotRemoved() = runTest {
        val sharpBitmap = createSharpBitmap()
        val medicine = sampleMedicine.copy(
            baselineEmptyCells = 2,
            pillsPerDose = 1
        )
        val cells = listOf(
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f)
        )
        coEvery { stripDetector.detect(any()) } returns detectionOf(cells)

        val result = pipeline.verify(sharpBitmap, medicine, lastVerifiedEmptyCells = null)

        assertFalse(result.passed)
        assertEquals(RejectReason.PILLS_NOT_REMOVED, result.rejectReason)
        assertEquals(2, result.emptyCellsAfter)
    }

    @Test
    fun laterDose_keepsStrictDelta_rejectsVariance() = runTest {
        val sharpBitmap = createSharpBitmap()
        val medicine = sampleMedicine.copy(
            baselineEmptyCells = 2,
            pillsPerDose = 2
        )
        val cells = listOf(
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f)
        )
        coEvery { stripDetector.detect(any()) } returns detectionOf(cells)

        val result = pipeline.verify(sharpBitmap, medicine, lastVerifiedEmptyCells = 2)

        assertFalse("Later dose must strictly require delta == pillsPerDose", result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
        assertEquals("Expected 2 pill(s) removed, but counted 1.", result.friendlyMessage)
    }

    @Test
    fun crossDetectorMismatch_deltaInTolerance_passesVerification() = runTest {
        val sharpBitmap = createSharpBitmap()
        // Baseline recorded via CLOUD detector: baselineEmptyCells = 2, pillsPerDose = 2
        val medicine = sampleMedicine.copy(
            baselineEmptyCells = 2,
            pillsPerDose = 2,
            baselineDetectorSource = "CLOUD"
        )
        // Current scan produced by LOCAL fallback detector: counts 3 empty (delta = 1)
        val cells = listOf(
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f)
        )
        val localDetection = detectionOf(cells).copy(source = DetectorSource.LOCAL)
        coEvery { stripDetector.detect(any()) } returns localDetection

        val result = pipeline.verify(sharpBitmap, medicine, lastVerifiedEmptyCells = 2, lastVerifiedDetectorSource = "CLOUD")

        assertTrue("Cross detector mismatch within tolerance must pass: ${result.friendlyMessage}", result.passed)
        assertEquals(3, result.emptyCellsAfter)
    }

    @Test
    fun crossDetectorMismatch_deltaNegative_asksRetakeOnlineNeverFailsWithDifferentStrip() = runTest {
        val sharpBitmap = createSharpBitmap()
        val medicine = sampleMedicine.copy(
            baselineEmptyCells = 2,
            pillsPerDose = 1,
            baselineDetectorSource = "CLOUD"
        )
        // Local fallback counts 1 empty cell (delta = -1 < 0)
        val cells = listOf(
            Cell(state = CellState.EMPTY, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f),
            Cell(state = CellState.FULL, confidence = 0.95f)
        )
        val localDetection = detectionOf(cells).copy(source = DetectorSource.LOCAL)
        coEvery { stripDetector.detect(any()) } returns localDetection

        val result = pipeline.verify(sharpBitmap, medicine, lastVerifiedEmptyCells = null)

        assertFalse(result.passed)
        assertEquals(RejectReason.WRONG_PILL_COUNT, result.rejectReason)
        assertFalse("Must NEVER fail with different strip error on cross-detector mismatch",
            result.friendlyMessage!!.contains("different or new strip", ignoreCase = true))
        assertTrue(result.friendlyMessage!!.contains("online", ignoreCase = true))
    }
}
