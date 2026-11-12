package com.meditrack.app.domain.model

enum class UserRole {
    PATIENT,
    CAREGIVER,
    DOCTOR
}

enum class FrequencyType {
    DAILY,
    HOURLY,
    SPECIFIC_DAYS
}

enum class FoodRelation {
    BEFORE,
    AFTER,
    ANY
}

enum class DoseStatus {
    PENDING,
    TAKEN,
    SKIPPED,
    UNVERIFIED,
    MISSED,
    TAKEN_LATE
}

enum class VerificationResultType {
    PASSED,
    REJECTED
}

enum class RejectReason {
    BLURRY,
    TOO_DARK,
    TOO_BRIGHT,
    GLARE,
    STRIP_NOT_FULLY_VISIBLE,
    PILLS_NOT_REMOVED,
    WRONG_PILL_COUNT,
    DUPLICATE_PHOTO,
    TOO_EARLY,
    /**
     * Retained for schema backwards-compatibility.
     * Dose verification no longer rejects on medicine name text mismatch.
     */
    WRONG_MEDICINE
}

enum class LinkStatus {
    PENDING,
    ACCEPTED,
    REVOKED
}

enum class DoctorLinkStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    REVOKED
}

enum class ProposalType {
    ADD,
    EDIT,
    STOP
}

enum class ProposalStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    CLARIFICATION_REQUESTED,
    SUPERSEDED
}

enum class NotifType {
    MISSED_DOSE,
    LOW_STOCK,
    DOSE_UNVERIFIED,
    DOSE_TAKEN_LATE,
    CAREGIVER_INVITE,
    DOCTOR_INVITE_RECEIVED,
    DOCTOR_INVITE_ACCEPTED,
    DOCTOR_INVITE_DECLINED,
    DOCTOR_INVITE_REVOKED,
    PRESCRIPTION_PROPOSAL_RECEIVED,
    PRESCRIPTION_ACCEPTED,
    PRESCRIPTION_DECLINED,
    PRESCRIPTION_CLARIFICATION_REQUESTED,
    PRESCRIPTION_REVISED,
    DOSE_SKIPPED
}
