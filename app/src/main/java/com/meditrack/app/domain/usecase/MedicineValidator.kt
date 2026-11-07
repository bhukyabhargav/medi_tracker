package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.FrequencyType
import java.time.LocalTime

object MedicineValidator {

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val field: Field, val errorMessage: String) : ValidationResult()
    }

    enum class Field {
        NAME,
        PILLS_PER_DOSE,
        DATES,
        TIMES,
        INITIAL_STOCK
    }

    /**
     * Validates medicine fields according to Stage 3 specifications:
     * - Name cannot be blank
     * - Pills per dose must be at least 1
     * - End date cannot be earlier than start date
     * - If reminder is enabled, at least one time of day must be specified
     * - Initial stock must be at least 1
     */
    fun validate(
        name: String,
        pillsPerDose: Int,
        startDate: Long,
        endDate: Long?,
        reminderEnabled: Boolean,
        timesOfDay: List<LocalTime>,
        initialStock: Int,
        frequencyType: FrequencyType = FrequencyType.DAILY,
        intervalHours: Int? = null,
        daysOfWeek: List<Int>? = null
    ): ValidationResult {
        if (name.trim().isBlank()) {
            return ValidationResult.Invalid(Field.NAME, "Medicine name cannot be empty")
        }
        if (pillsPerDose < 1) {
            return ValidationResult.Invalid(Field.PILLS_PER_DOSE, "Pills per dose must be at least 1")
        }
        if (endDate != null && endDate < startDate) {
            return ValidationResult.Invalid(Field.DATES, "End date cannot be earlier than start date")
        }
        if (reminderEnabled && timesOfDay.isEmpty()) {
            return ValidationResult.Invalid(Field.TIMES, "Please specify at least one time of day for reminders")
        }
        if (initialStock < 1) {
            return ValidationResult.Invalid(Field.INITIAL_STOCK, "Total pills in strip/pack must be at least 1")
        }
        return ValidationResult.Valid
    }
}
