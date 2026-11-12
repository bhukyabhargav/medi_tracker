package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import kotlin.math.roundToInt

data class AdherenceReport(
    val percentage: Double?,
    val percentageFormatted: String,
    val displayText: String,
    val verifiedTakenCount: Int,
    val takenOnTimeCount: Int,
    val takenLateCount: Int,
    val missedCount: Int,
    val skippedCount: Int,
    val unverifiedClosedCount: Int,
    val closedDosesCount: Int,
    val manualUnverifiedCount: Int
)

/** Adherence of one medicine, shown in the per-medicine list. */
data class MedicineAdherence(
    val medicineId: String,
    val medicineName: String,
    val report: AdherenceReport
)

object AdherenceCalculator {

    /**
     * Splits the doses by medicine and calculates each medicine's adherence with the same formula as [calculate].
     * Every medicine in [medicineNames] is listed (even with no closed doses yet, shown as "—"); doses of a
     * medicine that is not in the map (for example a removed medicine) are listed under "Removed medicine".
     * Sorted by medicine name.
     */
    fun calculateByMedicine(
        doses: List<DoseEvent>,
        medicineNames: Map<String, String>,
        now: Long = System.currentTimeMillis()
    ): List<MedicineAdherence> {
        val dosesByMedicine = doses.groupBy { it.medicineId }
        val ids = (medicineNames.keys + dosesByMedicine.keys).distinct()
        return ids.map { id ->
            MedicineAdherence(
                medicineId = id,
                medicineName = medicineNames[id]?.takeIf { it.isNotBlank() } ?: "Removed medicine",
                report = calculate(dosesByMedicine[id] ?: emptyList(), now)
            )
        }.sortedBy { it.medicineName.lowercase() }
    }

    /**
     * Calculates medication adherence based on the closed doses.
     * Section 3.5 formula:
     * verifiedTaken = count(TAKEN) + count(TAKEN_LATE)
     * closedDoses   = verifiedTaken + count(MISSED) + count(SKIPPED) + count(UNVERIFIED)
     * adherence %   = verifiedTaken / closedDoses * 100 (0 closed doses -> null / "—")
     * UNVERIFIED doses are closed only when windowEndsAt <= now.
     */
    fun calculate(
        doses: List<DoseEvent>,
        now: Long = System.currentTimeMillis()
    ): AdherenceReport {
        var takenOnTime = 0
        var takenLate = 0
        var missed = 0
        var skipped = 0
        var unverified = 0
        var manualUnverified = 0

        for (dose in doses) {
            when (dose.status) {
                DoseStatus.TAKEN -> takenOnTime++
                DoseStatus.TAKEN_LATE -> takenLate++
                DoseStatus.MISSED -> {
                    missed++
                    if (dose.manualUnverified) {
                        manualUnverified++
                    }
                }
                DoseStatus.SKIPPED -> skipped++
                DoseStatus.UNVERIFIED -> {
                    // UNVERIFIED only counts as closed once its intake window has ended
                    if (dose.windowEndsAt <= now) unverified++
                }
                DoseStatus.PENDING -> {
                    // Future / pending doses are not closed
                }
            }
        }

        val verifiedTaken = takenOnTime + takenLate
        val closedDoses = verifiedTaken + missed + skipped + unverified

        if (closedDoses == 0) {
            return AdherenceReport(
                percentage = null,
                percentageFormatted = "—",
                displayText = "—",
                verifiedTakenCount = 0,
                takenOnTimeCount = 0,
                takenLateCount = 0,
                missedCount = 0,
                skippedCount = 0,
                unverifiedClosedCount = 0,
                closedDosesCount = 0,
                manualUnverifiedCount = 0
            )
        }

        val adherencePercentage = (verifiedTaken.toDouble() / closedDoses) * 100.0
        // Never round 99.x% to 100% if not strictly 100% to maintain clinical accuracy
        val roundedPercentage = if (adherencePercentage < 100.0 && adherencePercentage.roundToInt() == 100) {
            99
        } else {
            adherencePercentage.roundToInt()
        }
        val percentageFormatted = "$roundedPercentage%"

        val displayText = if (takenLate > 0) {
            "$percentageFormatted ($takenLate late)"
        } else {
            percentageFormatted
        }

        return AdherenceReport(
            percentage = adherencePercentage,
            percentageFormatted = percentageFormatted,
            displayText = displayText,
            verifiedTakenCount = verifiedTaken,
            takenOnTimeCount = takenOnTime,
            takenLateCount = takenLate,
            missedCount = missed,
            skippedCount = skipped,
            unverifiedClosedCount = unverified,
            closedDosesCount = closedDoses,
            manualUnverifiedCount = manualUnverified
        )
    }
}
