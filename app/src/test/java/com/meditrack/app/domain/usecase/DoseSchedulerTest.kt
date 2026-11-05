package com.meditrack.app.domain.usecase

import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DoseSchedulerTest {

    private val zoneId = ZoneId.of("UTC")

    private fun createBaseMedicine(
        frequencyType: FrequencyType,
        timesOfDay: List<LocalTime> = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
        intervalHours: Int? = null,
        daysOfWeek: List<Int>? = null,
        startDate: Long = LocalDate.of(2026, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endDate: Long? = null,
        stoppedByDoctor: Boolean = false
    ): Medicine {
        return Medicine(
            id = "med_123",
            name = "Amoxicillin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = frequencyType,
            timesOfDay = timesOfDay,
            intervalHours = intervalHours,
            daysOfWeek = daysOfWeek,
            startDate = startDate,
            endDate = endDate,
            foodRelation = FoodRelation.AFTER,
            initialStock = 30,
            currentStock = 30,
            stoppedByDoctor = stoppedByDoctor
        )
    }

    @Test
    fun daily_frequency_generates_exact_doses_per_day() {
        val medicine = createBaseMedicine(
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))
        )

        val from = ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.MIN, zoneId).toInstant().toEpochMilli()
        val to = ZonedDateTime.of(LocalDate.of(2026, 1, 3), LocalTime.MAX, zoneId).toInstant().toEpochMilli()

        val doses = DoseScheduler.generateDoses(medicine, from, to, zoneId)

        // 3 days * 2 times per day = 6 doses
        assertEquals(6, doses.size)
        // Verify 60 minute dose window
        val expectedWindowMillis = AppConfig.DOSE_WINDOW_MINUTES * 60 * 1000L
        for (dose in doses) {
            assertEquals(expectedWindowMillis, dose.windowEndsAt - dose.scheduledAt)
        }
    }

    @Test
    fun hourly_frequency_generates_doses_at_expected_intervals() {
        val medicine = createBaseMedicine(
            frequencyType = FrequencyType.HOURLY,
            intervalHours = 8,
            timesOfDay = listOf(LocalTime.of(6, 0))
        )

        val from = ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(0, 0), zoneId).toInstant().toEpochMilli()
        val to = ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(23, 59), zoneId).toInstant().toEpochMilli()

        val doses = DoseScheduler.generateDoses(medicine, from, to, zoneId)

        // At 06:00, 14:00, 22:00 -> 3 doses
        assertEquals(3, doses.size)
    }

    @Test
    fun specific_days_generates_doses_only_on_configured_weekdays() {
        // Monday = 1, Wednesday = 3
        val medicine = createBaseMedicine(
            frequencyType = FrequencyType.SPECIFIC_DAYS,
            daysOfWeek = listOf(1, 3),
            timesOfDay = listOf(LocalTime.of(10, 0))
        )

        // 2026-01-05 is Monday, 2026-01-11 is Sunday (1 full week)
        val from = ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.MIN, zoneId).toInstant().toEpochMilli()
        val to = ZonedDateTime.of(LocalDate.of(2026, 1, 11), LocalTime.MAX, zoneId).toInstant().toEpochMilli()

        val doses = DoseScheduler.generateDoses(medicine, from, to, zoneId)

        // Only Monday (Jan 5) and Wednesday (Jan 7)
        assertEquals(2, doses.size)
    }

    @Test
    fun respects_end_date_cutoff() {
        val endDate = ZonedDateTime.of(LocalDate.of(2026, 1, 2), LocalTime.of(12, 0), zoneId).toInstant().toEpochMilli()
        val medicine = createBaseMedicine(
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)),
            endDate = endDate
        )

        val from = ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.MIN, zoneId).toInstant().toEpochMilli()
        val to = ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.MAX, zoneId).toInstant().toEpochMilli()

        val doses = DoseScheduler.generateDoses(medicine, from, to, zoneId)

        // Jan 1 (9am, 9pm), Jan 2 (9am only; 9pm is after 12pm end date) -> 3 doses
        assertEquals(3, doses.size)
        assertTrue(doses.all { it.scheduledAt <= endDate })
    }

    @Test
    fun stopped_by_doctor_generates_no_doses() {
        val medicine = createBaseMedicine(
            frequencyType = FrequencyType.DAILY,
            stoppedByDoctor = true
        )

        val from = ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.MIN, zoneId).toInstant().toEpochMilli()
        val to = ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.MAX, zoneId).toInstant().toEpochMilli()

        val doses = DoseScheduler.generateDoses(medicine, from, to, zoneId)
        assertTrue(doses.isEmpty())
    }

    @Test
    fun daylight_saving_time_transition_preserves_local_dose_times() {
        val nyZone = ZoneId.of("America/New_York")
        // Spring forward in US: Sunday, March 8, 2026 (clock jumps 2am -> 3am, 23-hour day)
        val medicineSpring = Medicine(
            id = "med_dst_spring",
            name = "Thyroxine",
            dosageText = "50mcg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)),
            startDate = LocalDate.of(2026, 3, 7).atStartOfDay(nyZone).toInstant().toEpochMilli(),
            foodRelation = FoodRelation.BEFORE,
            initialStock = 30,
            currentStock = 30
        )

        val fromSpring = ZonedDateTime.of(LocalDate.of(2026, 3, 7), LocalTime.MIN, nyZone).toInstant().toEpochMilli()
        val toSpring = ZonedDateTime.of(LocalDate.of(2026, 3, 9), LocalTime.MAX, nyZone).toInstant().toEpochMilli()

        val springDoses = DoseScheduler.generateDoses(medicineSpring, fromSpring, toSpring, nyZone)
        // 3 days * 2 times = 6 doses
        assertEquals(6, springDoses.size)

        // Verify each dose occurs at exactly 9:00 AM or 9:00 PM in America/New_York
        for (dose in springDoses) {
            val local = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(dose.scheduledAt), nyZone)
            val hour = local.hour
            assertTrue("Hour should be 9 or 21, but was $hour", hour == 9 || hour == 21)
            assertEquals(0, local.minute)
        }

        // Fall back in US: Sunday, November 1, 2026 (clock repeats 1am -> 2am, 25-hour day)
        val fromFall = ZonedDateTime.of(LocalDate.of(2026, 10, 31), LocalTime.MIN, nyZone).toInstant().toEpochMilli()
        val toFall = ZonedDateTime.of(LocalDate.of(2026, 11, 2), LocalTime.MAX, nyZone).toInstant().toEpochMilli()

        val fallDoses = DoseScheduler.generateDoses(medicineSpring, fromFall, toFall, nyZone)
        // 3 days * 2 times = 6 doses
        assertEquals(6, fallDoses.size)

        for (dose in fallDoses) {
            val local = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(dose.scheduledAt), nyZone)
            val hour = local.hour
            assertTrue("Hour should be 9 or 21, but was $hour", hour == 9 || hour == 21)
            assertEquals(0, local.minute)
        }
    }

    @Test
    fun hourly_frequency_across_dst_transition() {
        val nyZone = ZoneId.of("America/New_York")
        val medicineHourly = Medicine(
            id = "med_hourly_dst",
            name = "Painkiller",
            dosageText = "1 tab",
            pillsPerDose = 1,
            frequencyType = FrequencyType.HOURLY,
            intervalHours = 4,
            timesOfDay = listOf(LocalTime.of(0, 0)),
            startDate = LocalDate.of(2026, 3, 8).atStartOfDay(nyZone).toInstant().toEpochMilli(),
            initialStock = 30,
            currentStock = 30
        )
        val fromSpring = ZonedDateTime.of(LocalDate.of(2026, 3, 8), LocalTime.MIN, nyZone).toInstant().toEpochMilli()
        val toSpring = ZonedDateTime.of(LocalDate.of(2026, 3, 8), LocalTime.MAX, nyZone).toInstant().toEpochMilli()

        val doses = DoseScheduler.generateDoses(medicineHourly, fromSpring, toSpring, nyZone)
        assertTrue(doses.isNotEmpty())
        for (dose in doses) {
            val local = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(dose.scheduledAt), nyZone)
            assertEquals(0, local.minute)
        }
    }
}
