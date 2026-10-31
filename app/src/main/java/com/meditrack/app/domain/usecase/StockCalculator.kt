package com.meditrack.app.domain.usecase

import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine

object StockCalculator {

    /**
     * Determines whether medicine stock is low.
     * Low stock rule:
     * remaining pills < (pillsPerDay * LOW_STOCK_DAYS_THRESHOLD).
     */
    fun isLow(
        medicine: Medicine,
        pillsPerDay: Double = estimatePillsPerDay(medicine)
    ): Boolean {
        if (medicine.currentStock <= 0) return true
        if (pillsPerDay <= 0.0) return false

        val threshold =
            pillsPerDay * AppConfig.LOW_STOCK_DAYS_THRESHOLD

        return medicine.currentStock < threshold
    }

    /**
     * Estimates daily pill consumption based on frequency settings.
     */
    fun estimatePillsPerDay(medicine: Medicine): Double {
        val pillsPerDose = medicine.pillsPerDose.coerceAtLeast(1)

        return when (medicine.frequencyType) {

            FrequencyType.DAILY -> {
                val times = medicine.timesOfDay.distinct().size

                if (times == 0) {
                    0.0
                } else {
                    (times * pillsPerDose).toDouble()
                }
            }

            FrequencyType.HOURLY -> {
                val intervalHours =
                    medicine.intervalHours?.takeIf { it > 0 } ?: 6

                val dosesPerDay = 24.0 / intervalHours

                dosesPerDay * pillsPerDose
            }

            FrequencyType.SPECIFIC_DAYS -> {
                val activeDaysCount = medicine.daysOfWeek
                    ?.filter { it in 1..7 }
                    ?.distinct()
                    ?.size ?: 0

                val times = medicine.timesOfDay.distinct().size

                if (activeDaysCount == 0 || times == 0) {
                    0.0
                } else {
                    val weeklyDoses = activeDaysCount * times

                    (weeklyDoses * pillsPerDose) / 7.0
                }
            }
        }
    }

    /**
     * Calculates remaining whole days of medicine supply.
     */
    fun daysOfSupplyRemaining(medicine: Medicine): Int {
        val daily = estimatePillsPerDay(medicine)

        if (daily <= 0.0) return 0

        return (medicine.currentStock / daily).toInt()
    }
}
