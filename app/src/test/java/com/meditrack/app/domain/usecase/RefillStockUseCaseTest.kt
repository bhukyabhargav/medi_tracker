package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.MedicineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class RefillStockUseCaseTest {

    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private lateinit var useCase: RefillStockUseCase

    private val sampleMedicine = Medicine(
        id = "med_refill",
        name = "Metformin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = 1000L,
        foodRelation = FoodRelation.AFTER,
        initialStock = 30,
        currentStock = 2,
        lowStockNotified = true
    )

    @Before
    fun setUp() {
        useCase = RefillStockUseCase(medicineRepository)
    }

    @Test
    fun refill_increasesCurrentStock_andResetsLowStockNotified() = runTest {
        coEvery { medicineRepository.getById("med_refill") } returns sampleMedicine
        val slot = slot<Medicine>()
        coEvery { medicineRepository.update(capture(slot)) } returns Unit

        val result = useCase("med_refill", additionalPills = 30)

        assertTrue(result.isSuccess)
        val updated = result.getOrThrow()
        assertEquals(32, updated.currentStock)
        assertFalse(updated.lowStockNotified)

        coVerify(exactly = 1) { medicineRepository.update(any()) }
        assertEquals(32, slot.captured.currentStock)
        assertFalse(slot.captured.lowStockNotified)
    }

    @Test
    fun refill_resetsBaselineAndSetsLastRefillAt() = runTest {
        coEvery { medicineRepository.getById("med_refill") } returns sampleMedicine.copy(baselineEmptyCells = 10)
        val result = useCase(
            "med_refill", additionalPills = 10, newBaselineEmptyCells = 0, startedNewStrip = true, now = 5000L
        )
        assertTrue(result.isSuccess)
        val updated = result.getOrThrow()
        assertEquals(12, updated.currentStock)
        assertEquals(0, updated.baselineEmptyCells)
        assertEquals(5000L, updated.lastRefillAt)
        assertFalse(updated.lowStockNotified)
    }

    @Test
    fun refill_rejectsNonPositivePillCounts() = runTest {
        val resultZero = useCase("med_refill", additionalPills = 0)
        assertTrue(resultZero.isFailure)

        val resultNegative = useCase("med_refill", additionalPills = -5)
        assertTrue(resultNegative.isFailure)

        coVerify(exactly = 0) { medicineRepository.update(any()) }
    }

    @Test
    fun refill_returnsFailureWhenMedicineNotFound() = runTest {
        coEvery { medicineRepository.getById("unknown") } returns null

        val result = useCase("unknown", additionalPills = 10)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { medicineRepository.update(any()) }
    }

    @Test
    fun refill_plainTopUp_keepsLedgerAndBaseline() = runTest {
        val before = sampleMedicine.copy(baselineEmptyCells = 4, lastRefillAt = 1234L)
        coEvery { medicineRepository.getById("med_refill") } returns before

        val result = useCase("med_refill", additionalPills = 10, now = 5000L)

        val updated = result.getOrThrow()
        assertEquals(12, updated.currentStock)
        assertEquals(4, updated.baselineEmptyCells)
        assertEquals(1234L, updated.lastRefillAt)
        assertFalse(updated.lowStockNotified)
    }
}
