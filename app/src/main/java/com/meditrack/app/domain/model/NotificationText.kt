package com.meditrack.app.domain.model

object NotificationText {

    /**
     * Builds push notification text matching the exact specification:
     * Caregiver:
     * - MISSED_DOSE: "Medicine dose has been missed."
     * - LOW_STOCK: "<Medicine> is running low – refill needed."
     * - DOSE_UNVERIFIED: "Dose could not be verified."
     * - DOSE_TAKEN_LATE: "Missed dose was taken late (verified)."
     * - CAREGIVER_INVITE: "<Name> invited you to be their caregiver."
     * Doctor / Patient:
     * - DOCTOR_INVITE_RECEIVED: "<Name> wants to connect as your doctor on MediTrack. Review the request."
     * - DOCTOR_INVITE_ACCEPTED: "<Name> accepted your doctor connection."
     * - DOCTOR_INVITE_DECLINED: "<Name> declined your doctor connection."
     * - DOCTOR_INVITE_REVOKED: "Doctor access has been revoked."
     * - PRESCRIPTION_PROPOSAL_RECEIVED: "Dr <Name> proposed a prescription for <Medicine>. Review and accept."
     * - PRESCRIPTION_ACCEPTED: "<Name> accepted the prescription proposal for <Medicine>."
     * - PRESCRIPTION_DECLINED: "<Name> declined the prescription proposal for <Medicine>."
     * - PRESCRIPTION_CLARIFICATION_REQUESTED: "<Name> requested clarification on <Medicine>."
     * - PRESCRIPTION_REVISED: "Dr <Name> updated the prescription proposal for <Medicine>. Review the new revision."
     */
    fun buildText(
        type: NotifType,
        medicineName: String? = null,
        patientName: String? = null,
        doctorName: String? = null
    ): Pair<String, String> {
        val medicine = medicineName ?: "Medicine"
        val pName = patientName ?: "A patient"
        val dName = doctorName ?: "Doctor"

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
                "$pName invited you to be their caregiver."
            )
            NotifType.DOCTOR_INVITE_RECEIVED -> Pair(
                "Doctor Connection Request",
                "$dName wants to connect as your doctor on MediTrack. Review the request."
            )
            NotifType.DOCTOR_INVITE_ACCEPTED -> Pair(
                "Doctor Connection Accepted",
                "$pName accepted your doctor connection."
            )
            NotifType.DOCTOR_INVITE_DECLINED -> Pair(
                "Doctor Connection Declined",
                "$pName declined your doctor connection."
            )
            NotifType.DOCTOR_INVITE_REVOKED -> Pair(
                "Doctor Connection Revoked",
                "Doctor access has been revoked."
            )
            NotifType.PRESCRIPTION_PROPOSAL_RECEIVED -> Pair(
                "Prescription Proposal",
                "Dr $dName proposed a prescription for $medicine. Review and accept."
            )
            NotifType.PRESCRIPTION_ACCEPTED -> Pair(
                "Prescription Accepted",
                "$pName accepted the prescription proposal for $medicine."
            )
            NotifType.PRESCRIPTION_DECLINED -> Pair(
                "Prescription Declined",
                "$pName declined the prescription proposal for $medicine."
            )
            NotifType.PRESCRIPTION_CLARIFICATION_REQUESTED -> Pair(
                "Clarification Requested",
                "$pName requested clarification on $medicine."
            )
            NotifType.PRESCRIPTION_REVISED -> Pair(
                "Prescription Revised",
                "Dr $dName updated the prescription proposal for $medicine. Review the new revision."
            )
        }
    }
}
