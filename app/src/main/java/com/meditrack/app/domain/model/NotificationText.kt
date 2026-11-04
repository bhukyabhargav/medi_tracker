package com.meditrack.app.domain.model

object NotificationText {

    /**
     * Builds push notification text matching the exact Stage 10 & Stage 10B specification:
     * - MISSED_DOSE: "Medicine dose has been missed."
     * - LOW_STOCK: "<Medicine> is running low – refill needed."
     * - DOSE_UNVERIFIED: "Dose could not be verified."
     * - DOSE_TAKEN_LATE: "Missed dose was taken late (verified)."
     * - CAREGIVER_INVITE: "<Name> invited you to be their caregiver."
     * - DOCTOR_INVITE_RECEIVED: "<name> wants to connect as your doctor on MediTrack. Review the request."
     * - DOCTOR_INVITE_ACCEPTED: "<patientName> accepted your doctor connection."
     * - DOCTOR_INVITE_DECLINED: "<patientName> declined your doctor connection."
     * - DOCTOR_INVITE_REVOKED: "Doctor access has been revoked."
     * - PRESCRIPTION_PROPOSAL_RECEIVED: "Dr <name> proposed a prescription for <Medicine>. Review and accept."
     * - PRESCRIPTION_ACCEPTED: "<patientName> accepted the prescription proposal for <Medicine>."
     * - PRESCRIPTION_DECLINED: "<patientName> declined the prescription proposal for <Medicine>."
     * - PRESCRIPTION_CLARIFICATION_REQUESTED: "<patientName> requested clarification on <Medicine>."
     * - PRESCRIPTION_REVISED: "Dr <name> updated the prescription proposal for <Medicine>. Review the new revision."
     */
    fun buildText(
        type: NotifType,
        medicineName: String? = null,
        patientName: String? = null,
        doctorName: String? = null
    ): Pair<String, String> {
        val medicine = medicineName ?: "Medicine"
        val name = patientName ?: doctorName ?: "User"

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
            NotifType.DOCTOR_INVITE_RECEIVED, NotifType.DOCTOR_INVITE -> Pair(
                "Doctor Connection Request",
                "$name wants to connect as your doctor on MediTrack. Review the request."
            )
            NotifType.DOCTOR_INVITE_ACCEPTED -> Pair(
                "Doctor Connection Accepted",
                "$name accepted your doctor connection."
            )
            NotifType.DOCTOR_INVITE_DECLINED -> Pair(
                "Doctor Connection Declined",
                "$name declined your doctor connection."
            )
            NotifType.DOCTOR_INVITE_REVOKED -> Pair(
                "Doctor Connection Revoked",
                "Doctor access has been revoked."
            )
            NotifType.PRESCRIPTION_PROPOSAL_RECEIVED, NotifType.MEDICINE_PRESCRIBED -> Pair(
                "Prescription Proposed",
                "Dr $name proposed a prescription for $medicine. Review and accept."
            )
            NotifType.PRESCRIPTION_ACCEPTED -> Pair(
                "Prescription Accepted",
                "$name accepted the prescription proposal for $medicine."
            )
            NotifType.PRESCRIPTION_DECLINED -> Pair(
                "Prescription Declined",
                "$name declined the prescription proposal for $medicine."
            )
            NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED -> Pair(
                "Prescription Clarification Requested",
                "$name requested clarification on $medicine."
            )
            NotifType.PRESCRIPTION_REVISED, NotifType.MEDICINE_CHANGED_BY_DOCTOR -> Pair(
                "Prescription Revised",
                "Dr $name updated the prescription proposal for $medicine. Review the new revision."
            )
            NotifType.MEDICINE_STOPPED_BY_DOCTOR -> Pair(
                "Prescription Stopped",
                "Dr $name proposed stopping $medicine."
            )
        }
    }
}
