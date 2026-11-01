package com.meditrack.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class MedicineValidatorTest {

    @Test
    fun valid_medicine_input_passes_validation() {
        val result = MedicineValidator.validate(
            name = "Paracetamol",
            pillsPerDose = 1,
            startDate = 1700000000000L,
            endDate = 1700500000000L,
            reminderEnabled = true,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            initialStock = 20
        )
        assertTrue(result is MedicineValidator.ValidationResult.Valid)
    }

    @Test
    fun blank_name_fails_validation() {
        val result = MedicineValidator.validate(
            name = "   ",
            pillsPerDose = 1,
            startDate = 1700000000000L,
            endDate = null,
            reminderEnabled = true,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            initialStock = 20
        )
        assertTrue(result is MedicineValidator.ValidationResult.Invalid)
        assertEquals(MedicineValidator.Field.NAME, (result as MedicineValidator.ValidationResult.Invalid).field)
        assertEquals("Medicine name cannot be empty", result.errorMessage)
    }

    @Test
    fun pills_per_dose_less_than_one_fails_validation() {
        val result = MedicineValidator.validate(
            name = "Aspirin",
            pillsPerDose = 0,
            startDate = 1700000000000L,
            endDate = null,
            reminderEnabled = true,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            initialStock = 20
        )
        assertTrue(result is MedicineValidator.ValidationResult.Invalid)
        assertEquals(MedicineValidator.Field.PILLS_PER_DOSE, (result as MedicineValidator.ValidationResult.Invalid).field)
        assertEquals("Pills per dose must be at least 1", result.errorMessage)
    }

    @Test
    fun end_date_earlier_than_start_date_fails_validation() {
        val result = MedicineValidator.validate(
            name = "Aspirin",
            pillsPerDose = 1,
            startDate = 1700500000000L,
            endDate = 1700000000000L,
            reminderEnabled = true,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            initialStock = 20
        )
        assertTrue(result is MedicineValidator.ValidationResult.Invalid)
        assertEquals(MedicineValidator.Field.DATES, (result as MedicineValidator.ValidationResult.Invalid).field)
        assertEquals("End date cannot be earlier than start date", result.errorMessage)
    }

    @Test
    fun empty_times_when_reminder_enabled_fails_validation() {
        val result = MedicineValidator.validate(
            name = "Aspirin",
            pillsPerDose = 1,
            startDate = 1700000000000L,
            endDate = null,
            reminderEnabled = true,
            timesOfDay = emptyList(),
            initialStock = 20
        )
        assertTrue(result is MedicineValidator.ValidationResult.Invalid)
        assertEquals(MedicineValidator.Field.TIMES, (result as MedicineValidator.ValidationResult.Invalid).field)
        assertEquals("Please specify at least one time of day for reminders", result.errorMessage)
    }

    @Test
    fun empty_times_when_reminder_disabled_passes_validation() {
        val result = MedicineValidator.validate(
            name = "Aspirin",
            pillsPerDose = 1,
            startDate = 1700000000000L,
            endDate = null,
            reminderEnabled = false,
            timesOfDay = emptyList(),
            initialStock = 20
        )
        assertTrue(result is MedicineValidator.ValidationResult.Valid)
    }

    @Test
    fun non_positive_initial_stock_fails_validation() {
        val result = MedicineValidator.validate(
            name = "Aspirin",
            pillsPerDose = 1,
            startDate = 1700000000000L,
            endDate = null,
            reminderEnabled = false,
            timesOfDay = emptyList(),
            initialStock = 0
        )
        assertTrue(result is MedicineValidator.ValidationResult.Invalid)
        assertEquals(MedicineValidator.Field.INITIAL_STOCK, (result as MedicineValidator.ValidationResult.Invalid).field)
        assertEquals("Total pills in strip/pack must be at least 1", result.errorMessage)
    }
}
