package com.meditrack.app.domain.model

object NotificationText {

    /**
     * Builds push notification text matching the exact Stage 10 specification:
     * - MISSED_DOSE: "Medicine dose has been missed."
     * - LOW_STOCK: "<Medicine> is running low – refill needed."
     * - DOSE_UNVERIFIED: "Dose could not be verified."
     * - DOSE_TAKEN_LATE: "Missed dose was taken late (verified)."
     * - CAREGIVER_INVITE: "<Name> invited you to be their caregiver."
     */
    fun buildText(
        type: NotifType,
        medicineName: String? = null,
        patientName: String? = null
    ): Pair<String, String> {
        val medicine = medicineName ?: "Medicine"
        val name = patientName ?: "A patient"

        return when (type) {
            NotifType.MISSED_DOSE -> Pair(
                "Missed Dose",
                "Medicine dose has been missed."
            )
            NotifType.LOW_STOCK -> Pair(
                "Low Stock Alert",
                "$medicine is running low \u2013 refill needed."
            )
            NotifType.DOSE_UNVERIFIED -> Pair(
                "Dose Unverified",
                "Dose could not be verified."
            )
            NotifType.DOSE_TAKEN_LATE -> Pair(
                "Late Dose Taken",
                "Missed dose was taken late (verified)."
            )
            NotifType.CAREGIVER_INVITE -> Pair(
                "Caregiver Invitation",
                "$name invited you to be their caregiver."
            )
            else -> Pair(
                "MediTrack Alert",
                "You have a new update."
            )
        }
    }
}
