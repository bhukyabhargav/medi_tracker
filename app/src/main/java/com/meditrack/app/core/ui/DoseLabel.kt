package com.meditrack.app.core.ui

import com.meditrack.app.domain.model.DoseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Human-readable label for a dose row: the medicine's name and when the dose was scheduled,
 * for example "Metformin 500mg • 12 Nov, 08:00". Never shows internal ids.
 */
object DoseLabel {

    private val formatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

    fun of(
        dose: DoseEvent,
        medicineNamesById: Map<String, String>,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val name = medicineNamesById[dose.medicineId]?.takeIf { it.isNotBlank() } ?: "Removed medicine"
        val time = Instant.ofEpochMilli(dose.scheduledAt).atZone(zone).format(formatter)
        return "$name • $time"
    }
}
