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
        intervalHours: Int? = null,
        daysOfWeek: List<Int>? = null
    ): Medicine {
        return Medicine(
            id = "med_1",
            name = "Metformin",
            dosageText = "500mg",
            pillsPerDose = pillsPerDose,
            frequencyType = frequencyType,
            timesOfDay = timesOfDay,
            intervalHours = intervalHours,
            daysOfWeek = daysOfWeek,
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

    @Test
    fun empty_daily_schedule_yields_zero_pills_per_day_and_zero_supply() {
        val med = createMedicine(
            currentStock = 10,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = emptyList()
        )
        assertEquals(0.0, StockCalculator.estimatePillsPerDay(med), 0.001)
        assertEquals(0, StockCalculator.daysOfSupplyRemaining(med))
        assertFalse(StockCalculator.isLow(med))

        // When stock is 0, isLow should still be true even if schedule is empty
        val medZeroStock = createMedicine(
            currentStock = 0,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = emptyList()
        )
        assertTrue(StockCalculator.isLow(medZeroStock))
    }

    @Test
    fun empty_specific_weekdays_yields_zero_pills_per_day_and_zero_supply() {
        val medEmptyList = createMedicine(
            currentStock = 15,
            frequencyType = FrequencyType.SPECIFIC_DAYS,
            daysOfWeek = emptyList(),
            timesOfDay = listOf(LocalTime.of(8, 0))
        )
        assertEquals(0.0, StockCalculator.estimatePillsPerDay(medEmptyList), 0.001)
        assertEquals(0, StockCalculator.daysOfSupplyRemaining(medEmptyList))
        assertFalse(StockCalculator.isLow(medEmptyList))

        val medNullDays = createMedicine(
            currentStock = 15,
            frequencyType = FrequencyType.SPECIFIC_DAYS,
            daysOfWeek = null,
            timesOfDay = listOf(LocalTime.of(8, 0))
        )
        assertEquals(0.0, StockCalculator.estimatePillsPerDay(medNullDays), 0.001)
        assertEquals(0, StockCalculator.daysOfSupplyRemaining(medNullDays))
        assertFalse(StockCalculator.isLow(medNullDays))
    }

    @Test
    fun duplicate_weekdays_are_counted_once() {
        // Monday (1) and Tuesday (2) repeated
        val med = createMedicine(
            currentStock = 20,
            frequencyType = FrequencyType.SPECIFIC_DAYS,
            daysOfWeek = listOf(1, 1, 2, 2, 2),
            timesOfDay = listOf(LocalTime.of(9, 0)),
            pillsPerDose = 1
        )
        // 2 active days * 1 dose * 1 pill / 7.0 = 2/7 pills/day
        val expectedDaily = (2.0 * 1 * 1) / 7.0
        assertEquals(expectedDaily, StockCalculator.estimatePillsPerDay(med), 0.001)
    }

    @Test
    fun invalid_weekdays_are_filtered_out() {
        // 0 and 8 are invalid; 1 (Mon) and 7 (Sun) are valid -> 2 active days
        val med = createMedicine(
            currentStock = 20,
            frequencyType = FrequencyType.SPECIFIC_DAYS,
            daysOfWeek = listOf(0, 1, 7, 8, -5, 12),
            timesOfDay = listOf(LocalTime.of(9, 0)),
            pillsPerDose = 1
        )
        val expectedDaily = (2.0 * 1 * 1) / 7.0
        assertEquals(expectedDaily, StockCalculator.estimatePillsPerDay(med), 0.001)

        // All invalid days -> 0 active days -> 0.0 pills per day
        val medAllInvalid = createMedicine(
            currentStock = 20,
            frequencyType = FrequencyType.SPECIFIC_DAYS,
            daysOfWeek = listOf(0, 8, 9),
            timesOfDay = listOf(LocalTime.of(9, 0))
        )
        assertEquals(0.0, StockCalculator.estimatePillsPerDay(medAllInvalid), 0.001)
    }

    @Test
    fun duplicate_scheduled_times_are_counted_once() {
        // Daily with duplicate 8:00 AM
        val medDaily = createMedicine(
            currentStock = 20,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0), LocalTime.of(8, 0), LocalTime.of(20, 0)),
            pillsPerDose = 1
        )
        // 2 distinct times * 1 pill = 2.0 pills/day
        assertEquals(2.0, StockCalculator.estimatePillsPerDay(medDaily), 0.001)

        // Specific days with duplicate times
        val medSpecific = createMedicine(
            currentStock = 20,
            frequencyType = FrequencyType.SPECIFIC_DAYS,
            daysOfWeek = listOf(1, 3, 5), // 3 days
            timesOfDay = listOf(LocalTime.of(8, 0), LocalTime.of(8, 0)), // 1 distinct time
            pillsPerDose = 2
        )
        // 3 days * 1 time * 2 pills / 7 = 6/7 pills/day
        val expectedSpecificDaily = (3.0 * 1 * 2) / 7.0
        assertEquals(expectedSpecificDaily, StockCalculator.estimatePillsPerDay(medSpecific), 0.001)
    }
}
