package com.meditrack.app.domain.usecase

import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object DoseScheduler {

    private const val MILLIS_PER_MINUTE = 60 * 1000L

    /**
     * Generates DoseEvents for a given medicine within the specified time range.
     * Respects medicine.startDate, medicine.endDate, and frequency configuration.
     */
    fun generateDoses(
        medicine: Medicine,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DoseEvent> {
        if (medicine.stoppedByDoctor) return emptyList()
        if (fromEpochMillis > toEpochMillis) return emptyList()

        val effectiveStart = maxOf(fromEpochMillis, medicine.startDate)
        val effectiveEnd = if (medicine.endDate != null) {
            minOf(toEpochMillis, medicine.endDate)
        } else {
            toEpochMillis
        }

        if (effectiveStart > effectiveEnd) return emptyList()

        val scheduledTimes = when (medicine.frequencyType) {
            FrequencyType.DAILY -> generateDailyTimes(medicine, effectiveStart, effectiveEnd, zoneId)
            FrequencyType.HOURLY -> generateHourlyTimes(medicine, effectiveStart, effectiveEnd, zoneId)
            FrequencyType.SPECIFIC_DAYS -> generateSpecificDaysTimes(medicine, effectiveStart, effectiveEnd, zoneId)
        }

        val windowDurationMillis = AppConfig.effectiveDoseWindowMinutes * MILLIS_PER_MINUTE

        return scheduledTimes.sorted().map { scheduledAt ->
            DoseEvent(
                id = "${medicine.id}_$scheduledAt",
                medicineId = medicine.id,
                patientId = medicine.patientId,
                scheduledAt = scheduledAt,
                windowEndsAt = scheduledAt + windowDurationMillis,
                status = DoseStatus.PENDING,
                updatedAt = scheduledAt
            )
        }
    }

    private fun generateDailyTimes(
        medicine: Medicine,
        startMillis: Long,
        endMillis: Long,
        zoneId: ZoneId
    ): List<Long> {
        if (medicine.timesOfDay.isEmpty()) return emptyList()

        val startDate = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDate()

        val result = mutableListOf<Long>()
        var currentDate = startDate

        while (!currentDate.isAfter(endDate)) {
            for (time in medicine.timesOfDay) {
                val zdt = ZonedDateTime.of(currentDate, time, zoneId)
                val millis = zdt.toInstant().toEpochMilli()
                if (millis in startMillis..endMillis) {
                    result.add(millis)
                }
            }
            currentDate = currentDate.plusDays(1)
        }

        return result
    }

    private fun generateHourlyTimes(
        medicine: Medicine,
        startMillis: Long,
        endMillis: Long,
        zoneId: ZoneId
    ): List<Long> {
        val intervalHours = medicine.intervalHours?.takeIf { it > 0 } ?: 6

        val anchorTime = medicine.timesOfDay.firstOrNull()
        val anchorDate = Instant.ofEpochMilli(medicine.startDate).atZone(zoneId).toLocalDate()
        var currentZdt = if (anchorTime != null) {
            ZonedDateTime.of(anchorDate, anchorTime, zoneId)
        } else {
            Instant.ofEpochMilli(medicine.startDate).atZone(zoneId)
        }

        val result = mutableListOf<Long>()

        // Fast forward to near startMillis if currentZdt is in the past
        val startInstant = Instant.ofEpochMilli(startMillis)
        while (currentZdt.toInstant().isBefore(startInstant)) {
            val next = currentZdt.plusHours(intervalHours.toLong())
            if (!next.isBefore(startInstant)) {
                // Keep currentZdt at the step just before or equal to startInstant
                break
            }
            currentZdt = next
        }

        val endInstant = Instant.ofEpochMilli(endMillis)
        while (!currentZdt.toInstant().isAfter(endInstant)) {
            val millis = currentZdt.toInstant().toEpochMilli()
            if (millis in startMillis..endMillis) {
                result.add(millis)
            }
            currentZdt = currentZdt.plusHours(intervalHours.toLong())
        }

        return result
    }

    private fun generateSpecificDaysTimes(
        medicine: Medicine,
        startMillis: Long,
        endMillis: Long,
        zoneId: ZoneId
    ): List<Long> {
        val allowedDays = medicine.daysOfWeek?.toSet() ?: emptySet()
        if (allowedDays.isEmpty() || medicine.timesOfDay.isEmpty()) return emptyList()

        val startDate = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDate()

        val result = mutableListOf<Long>()
        var currentDate = startDate

        while (!currentDate.isAfter(endDate)) {
            val dayOfWeekValue = currentDate.dayOfWeek.value // 1 (Mon) .. 7 (Sun)
            if (allowedDays.contains(dayOfWeekValue)) {
                for (time in medicine.timesOfDay) {
                    val zdt = ZonedDateTime.of(currentDate, time, zoneId)
                    val millis = zdt.toInstant().toEpochMilli()
                    if (millis in startMillis..endMillis) {
                        result.add(millis)
                    }
                }
            }
            currentDate = currentDate.plusDays(1)
        }

        return result
    }
}
