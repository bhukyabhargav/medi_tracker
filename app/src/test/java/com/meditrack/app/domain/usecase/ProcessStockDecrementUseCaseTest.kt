package com.meditrack.app.domain.usecase

import android.content.Context
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class ProcessStockDecrementUseCaseTest {

    private val context: Context = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)

    private lateinit var useCase: ProcessStockDecrementUseCase
    private var alertCount = 0

    // Medicine with 2 doses per day (1 pill/dose). Daily consumption = 2.0 pills/day.
    // Low stock threshold = 2.0 * 3 days = 6.0 pills. (Low when remaining < 6.0)
    private val sampleMedicine = Medicine(
        id = "med_stock",
        name = "Amoxicillin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
        startDate = 1000L,
        foodRelation = FoodRelation.AFTER,
        initialStock = 30,
        currentStock = 7,
        lowStockNotified = false
    )

    @Before
    fun setUp() {
        alertCount = 0
        useCase = ProcessStockDecrementUseCase(
            context = context,
            medicineRepository = medicineRepository,
            caregiverRepository = caregiverRepository,
            outboxRepository = outboxRepository,
            userPreferences = userPreferences
        ).apply {
            notifier = { _, _, _ -> alertCount++ }
        }

        coEvery { userPreferences.currentUserId } returns flowOf("patient_1")
    }

    @Test
    fun decrement_decreasesStock_andEvaluatesLowStock() = runTest {
        coEvery { medicineRepository.getById("med_stock") } returns sampleMedicine
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_1") } returns true

        val medSlot = slot<Medicine>()
        coEvery { medicineRepository.update(capture(medSlot)) } returns Unit

        // Current stock is 7. Decrement by 1 -> 6. Not low yet (< 6.0 is low).
        val result = useCase("med_stock", pillsToDecrement = 1)

        assertNotNull(result)
        assertEquals(6, result?.newStock)
        assertFalse(result?.isLow ?: true)
        assertFalse(result?.transitionedToLow ?: true)
        assertFalse(result?.outboxEnqueued ?: true)
        assertEquals(0, alertCount)
    }

    @Test
    fun decrement_whenCrossingThreshold_withCaregiver_triggersSingleAlertAndOutbox() = runTest {
        // Stock drops from 6 -> 5 (< 6.0 threshold, becomes low!)
        val medWith6 = sampleMedicine.copy(currentStock = 6, lowStockNotified = false)
        coEvery { medicineRepository.getById("med_stock") } returns medWith6
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_1") } returns true

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        val result = useCase("med_stock", pillsToDecrement = 1, now = 5000L)

        assertNotNull(result)
        assertEquals(5, result?.newStock)
        assertTrue(result?.isLow == true)
        assertTrue(result?.transitionedToLow == true)
        assertTrue(result?.outboxEnqueued == true)
        assertEquals(1, alertCount)

        // Verify outbox payload
        coVerify(exactly = 1) { outboxRepository.enqueue(any()) }
        assertEquals(NotifType.LOW_STOCK, outboxSlot.captured.type)
        assertTrue(outboxSlot.captured.payloadJson.contains("Amoxicillin"))
        assertTrue(outboxSlot.captured.payloadJson.contains("\"remaining\":5"))

        // Verify lowStockNotified flag set to true
        val medSlot = slot<Medicine>()
        coVerify { medicineRepository.update(capture(medSlot)) }
        assertTrue(medSlot.captured.lowStockNotified)
    }

    @Test
    fun decrement_whenAlreadyLow_doesNotSpamAlertOrOutbox() = runTest {
        // Stock is already low (4 pills) and lowStockNotified is true
        val alreadyLowMed = sampleMedicine.copy(currentStock = 4, lowStockNotified = true)
        coEvery { medicineRepository.getById("med_stock") } returns alreadyLowMed
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_1") } returns true

        val result = useCase("med_stock", pillsToDecrement = 1, now = 6000L)

        assertNotNull(result)
        assertEquals(3, result?.newStock)
        assertTrue(result?.isLow == true)
        assertFalse("Must NOT transition to low again if already low", result?.transitionedToLow ?: true)
        assertFalse("Must NOT spam caregiver outbox", result?.outboxEnqueued ?: true)
        assertEquals("Must NOT spam patient with alert", 0, alertCount)
        coVerify(exactly = 0) { outboxRepository.enqueue(any()) }
    }

    @Test
    fun decrement_whenCrossingThreshold_withoutCaregiver_promptsCaregiverConnection() = runTest {
        val medWith6 = sampleMedicine.copy(currentStock = 6, lowStockNotified = false)
        coEvery { medicineRepository.getById("med_stock") } returns medWith6
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_1") } returns false

        val result = useCase("med_stock", pillsToDecrement = 1, now = 7000L)

        assertNotNull(result)
        assertEquals(5, result?.newStock)
        assertTrue(result?.isLow == true)
        assertTrue(result?.transitionedToLow == true)
        assertFalse(result?.outboxEnqueued ?: true)
        assertTrue("Must prompt to add a caregiver when none connected", result?.promptCaregiver == true)
        assertEquals(1, alertCount)
        coVerify(exactly = 0) { outboxRepository.enqueue(any()) }
    }

    @Test
    fun stockNeverDropsBelowZero() = runTest {
        val medWith1 = sampleMedicine.copy(currentStock = 1, lowStockNotified = true)
        coEvery { medicineRepository.getById("med_stock") } returns medWith1

        val result = useCase("med_stock", pillsToDecrement = 5)

        assertNotNull(result)
        assertEquals(0, result?.newStock)
    }

    @Test
    fun thresholdBoundary_exactlyAtThreshold_isNotLow_oneBelow_isLow() = runTest {
        // Daily rate: 2 times/day * 1 pill = 2.0 pills/day.
        // Threshold: 2.0 * 3 days = 6.0 pills.
        // Exact threshold (6 pills): remaining < threshold is FALSE -> NOT low stock
        val medAtThreshold = sampleMedicine.copy(currentStock = 6, lowStockNotified = false)
        assertFalse("Stock exactly equal to pillsPerDay * 3 (6) must NOT be considered low stock", StockCalculator.isLow(medAtThreshold))

        // One below threshold (5 pills): remaining < threshold is TRUE -> IS low stock
        val medOneBelow = sampleMedicine.copy(currentStock = 5, lowStockNotified = false)
        assertTrue("Stock one below threshold (5) MUST be considered low stock", StockCalculator.isLow(medOneBelow))

        // Decrement to exact threshold (7 -> 6): should not trigger low stock or alert
        coEvery { medicineRepository.getById("med_stock") } returns sampleMedicine.copy(currentStock = 7, lowStockNotified = false)
        val resultTo6 = useCase("med_stock", pillsToDecrement = 1)
        assertEquals(6, resultTo6?.newStock)
        assertFalse(resultTo6?.isLow == true)
        assertFalse(resultTo6?.transitionedToLow == true)
        assertEquals(0, alertCount)

        // Decrement to one below threshold (6 -> 5): triggers low stock and single alert
        coEvery { medicineRepository.getById("med_stock") } returns sampleMedicine.copy(currentStock = 6, lowStockNotified = false)
        val resultTo5 = useCase("med_stock", pillsToDecrement = 1)
        assertEquals(5, resultTo5?.newStock)
        assertTrue(resultTo5?.isLow == true)
        assertTrue(resultTo5?.transitionedToLow == true)
        assertEquals(1, alertCount)
    }
}
