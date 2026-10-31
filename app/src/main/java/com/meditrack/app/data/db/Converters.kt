package com.meditrack.app.data.db

import androidx.room.TypeConverter
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.LinkStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.model.VerificationResultType
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class Converters {

    private val timeFormatter = DateTimeFormatter.ISO_LOCAL_TIME

    @TypeConverter
    fun fromLocalTimeList(times: List<LocalTime>?): String? {
        return times?.joinToString(",") { it.format(timeFormatter) }
    }

    @TypeConverter
    fun toLocalTimeList(data: String?): List<LocalTime>? {
        if (data.isNullOrBlank()) return emptyList()
        return data.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LocalTime.parse(it, timeFormatter) }
    }

    @TypeConverter
    fun fromIntList(list: List<Int>?): String? {
        return list?.joinToString(",")
    }

    @TypeConverter
    fun toIntList(data: String?): List<Int>? {
        if (data.isNullOrBlank()) return emptyList()
        return data.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
    }

    @TypeConverter
    fun fromUserRole(value: UserRole?): String? = value?.name

    @TypeConverter
    fun toUserRole(value: String?): UserRole? = value?.let { enumValueOf<UserRole>(it) }

    @TypeConverter
    fun fromFrequencyType(value: FrequencyType?): String? = value?.name

    @TypeConverter
    fun toFrequencyType(value: String?): FrequencyType? = value?.let { enumValueOf<FrequencyType>(it) }

    @TypeConverter
    fun fromFoodRelation(value: FoodRelation?): String? = value?.name

    @TypeConverter
    fun toFoodRelation(value: String?): FoodRelation? = value?.let { enumValueOf<FoodRelation>(it) }

    @TypeConverter
    fun fromDoseStatus(value: DoseStatus?): String? = value?.name

    @TypeConverter
    fun toDoseStatus(value: String?): DoseStatus? = value?.let { enumValueOf<DoseStatus>(it) }

    @TypeConverter
    fun fromVerificationResultType(value: VerificationResultType?): String? = value?.name

    @TypeConverter
    fun toVerificationResultType(value: String?): VerificationResultType? = value?.let { enumValueOf<VerificationResultType>(it) }

    @TypeConverter
    fun fromRejectReason(value: RejectReason?): String? = value?.name

    @TypeConverter
    fun toRejectReason(value: String?): RejectReason? = value?.let { enumValueOf<RejectReason>(it) }

    @TypeConverter
    fun fromLinkStatus(value: LinkStatus?): String? = value?.name

    @TypeConverter
    fun toLinkStatus(value: String?): LinkStatus? = value?.let { enumValueOf<LinkStatus>(it) }

    @TypeConverter
    fun fromDoctorLinkStatus(value: DoctorLinkStatus?): String? = value?.name

    @TypeConverter
    fun toDoctorLinkStatus(value: String?): DoctorLinkStatus? = value?.let { enumValueOf<DoctorLinkStatus>(it) }

    @TypeConverter
    fun fromProposalType(value: ProposalType?): String? = value?.name

    @TypeConverter
    fun toProposalType(value: String?): ProposalType? = value?.let { enumValueOf<ProposalType>(it) }

    @TypeConverter
    fun fromProposalStatus(value: ProposalStatus?): String? = value?.name

    @TypeConverter
    fun toProposalStatus(value: String?): ProposalStatus? = value?.let { enumValueOf<ProposalStatus>(it) }

    @TypeConverter
    fun fromNotifType(value: NotifType?): String? = value?.name

    @TypeConverter
    fun toNotifType(value: String?): NotifType? = value?.let { enumValueOf<NotifType>(it) }
}
