package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class StockCalculatorTest {

    private fun createMedicine(
        currentStock: Int,
        frequencyType: FrequencyType = FrequencyType.DAILY,
        timesOfDay: List<LocalTime> = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), // 2 times/day
        pillsPerDose: Int = 1,
        intervalHours: Int? = null
    ): Medicine {
        return Medicine(
            id = "med_1",
            name = "Metformin",
            dosageText = "500mg",
            pillsPerDose = pillsPerDose,
            frequencyType = frequencyType,
            timesOfDay = timesOfDay,
            intervalHours = intervalHours,
            startDate = 1000L,
            foodRelation = FoodRelation.AFTER,
            initialStock = 60,
            currentStock = currentStock
        )
    }

    @Test
    fun daily_frequency_detects_low_stock_below_3_days_threshold() {
        // 2 pills/day * 3 days = 6 pills threshold
        val medLow = createMedicine(currentStock = 5)
        assertTrue(StockCalculator.isLow(medLow))

        val medBoundary = createMedicine(currentStock = 6)
        assertFalse(StockCalculator.isLow(medBoundary))

        val medSufficient = createMedicine(currentStock = 20)
        assertFalse(StockCalculator.isLow(medSufficient))
    }

    @Test
    fun zero_stock_is_always_low() {
        val medZero = createMedicine(currentStock = 0)
        assertTrue(StockCalculator.isLow(medZero))
    }

    @Test
    fun hourly_frequency_correctly_estimates_daily_consumption() {
        // Every 8 hours = 3 doses/day * 1 pill = 3 pills/day
        // Threshold: 3 pills/day * 3 days = 9 pills
        val med = createMedicine(
            currentStock = 8,
            frequencyType = FrequencyType.HOURLY,
            intervalHours = 8
        )
        assertEquals(3.0, StockCalculator.estimatePillsPerDay(med), 0.01)
        assertTrue(StockCalculator.isLow(med))

        val medOk = createMedicine(
            currentStock = 9,
            frequencyType = FrequencyType.HOURLY,
            intervalHours = 8
        )
        assertFalse(StockCalculator.isLow(medOk))
    }

    @Test
    fun days_of_supply_remaining_calculation() {
        val med = createMedicine(currentStock = 14) // 2 pills/day
        assertEquals(7, StockCalculator.daysOfSupplyRemaining(med))
    }
}
