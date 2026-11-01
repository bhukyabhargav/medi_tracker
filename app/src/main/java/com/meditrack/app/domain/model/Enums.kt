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
    DUPLICATE_PHOTO
}

enum class LinkStatus {
    PENDING,
    ACCEPTED
}

enum class DoctorLinkStatus {
    PENDING,
    ACCEPTED,
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
    APPLIED
}

enum class NotifType {
    MISSED_DOSE,
    LOW_STOCK,
    DOSE_UNVERIFIED,
    DOSE_TAKEN_LATE,
    CAREGIVER_INVITE,
    DOCTOR_INVITE,
    MEDICINE_PRESCRIBED,
    MEDICINE_CHANGED_BY_DOCTOR,
    MEDICINE_STOPPED_BY_DOCTOR
}
