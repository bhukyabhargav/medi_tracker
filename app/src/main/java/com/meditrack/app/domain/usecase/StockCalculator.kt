package com.meditrack.app.domain.usecase

import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import kotlin.math.ceil

object StockCalculator {

    /**
     * Determines whether medicine stock is low.
     * Low stock rule: remaining pills < (pillsPerDay * LOW_STOCK_DAYS_THRESHOLD).
     */
    fun isLow(medicine: Medicine, pillsPerDay: Double = estimatePillsPerDay(medicine)): Boolean {
        if (medicine.currentStock <= 0) return true
        val threshold = pillsPerDay * AppConfig.LOW_STOCK_DAYS_THRESHOLD
        return medicine.currentStock < threshold
    }

    /**
     * Estimates daily pill consumption based on frequency settings and pillsPerDose.
     */
    fun estimatePillsPerDay(medicine: Medicine): Double {
        val pillsPerDose = medicine.pillsPerDose.coerceAtLeast(1)
        return when (medicine.frequencyType) {
            FrequencyType.DAILY -> {
                val times = medicine.timesOfDay.size.coerceAtLeast(1)
                (times * pillsPerDose).toDouble()
            }
            FrequencyType.HOURLY -> {
                val intervalHours = medicine.intervalHours?.takeIf { it > 0 } ?: 6
                val dosesPerDay = 24.0 / intervalHours
                dosesPerDay * pillsPerDose
            }
            FrequencyType.SPECIFIC_DAYS -> {
                val activeDaysCount = medicine.daysOfWeek?.size ?: 1
                val times = medicine.timesOfDay.size.coerceAtLeast(1)
                val weeklyDoses = activeDaysCount * times
                (weeklyDoses * pillsPerDose) / 7.0
            }
        }
    }

    /**
     * Calculates remaining days of supply given current stock and daily consumption.
     */
    fun daysOfSupplyRemaining(medicine: Medicine): Int {
        val daily = estimatePillsPerDay(medicine)
        if (daily <= 0.0) return 0
        return (medicine.currentStock / daily).toInt()
    }
}
