package com.meditrack.app.domain.model

import com.meditrack.app.core.util.JsonPayload

data class PrescriptionProposalPayload(
    val medicineName: String,
    val dosageText: String,
    val pillsPerDose: Int = 1,
    val frequencyType: FrequencyType = FrequencyType.DAILY,
    val timesOfDay: List<String> = emptyList(), // e.g. ["08:00", "20:00"]
    val intervalHours: Int? = null,
    val daysOfWeek: List<Int>? = null,
    val startDate: Long = System.currentTimeMillis(),
    val endDate: Long? = null,
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val instructions: String = "",
    val initialStock: Int = 30,
    val doctorName: String = "",
    val reason: String? = null
) {
    fun toJson(): String {
        val pairs = mutableListOf<Pair<String, Any?>>(
            "medicineName" to medicineName,
            "dosageText" to dosageText,
            "pillsPerDose" to pillsPerDose,
            "frequencyType" to frequencyType.name,
            "timesOfDay" to timesOfDay,
            "startDate" to startDate,
            "foodRelation" to foodRelation.name,
            "instructions" to instructions,
            "initialStock" to initialStock,
            "doctorName" to doctorName
        )
        if (intervalHours != null) pairs.add("intervalHours" to intervalHours)
        if (daysOfWeek != null) pairs.add("daysOfWeek" to daysOfWeek)
        if (endDate != null) pairs.add("endDate" to endDate)
        if (reason != null) pairs.add("reason" to reason)
        return JsonPayload.build(pairs)
    }

    companion object {
        fun fromJson(json: String): PrescriptionProposalPayload {
            val name = JsonPayload.getString(json, "medicineName") ?: ""
            val dosage = JsonPayload.getString(json, "dosageText") ?: ""
            val pills = JsonPayload.getInt(json, "pillsPerDose") ?: 1
            val freqName = JsonPayload.getString(json, "frequencyType")
            val freq = freqName?.let { runCatching { FrequencyType.valueOf(it) }.getOrNull() } ?: FrequencyType.DAILY
            val startDate = JsonPayload.getLong(json, "startDate") ?: System.currentTimeMillis()
            val endDate = JsonPayload.getLong(json, "endDate")
            val foodName = JsonPayload.getString(json, "foodRelation")
            val food = foodName?.let { runCatching { FoodRelation.valueOf(it) }.getOrNull() } ?: FoodRelation.ANY
            val instructions = JsonPayload.getString(json, "instructions") ?: ""
            val stock = JsonPayload.getInt(json, "initialStock") ?: 30
            val docName = JsonPayload.getString(json, "doctorName") ?: ""
            val reason = JsonPayload.getString(json, "reason")
            val interval = JsonPayload.getInt(json, "intervalHours")

            // Simple parser for timesOfDay list: ["08:00","20:00"]
            val timesPattern = Regex("\"timesOfDay\"\\s*:\\s*\\[([^\\]]*)\\]")
            val timesMatch = timesPattern.find(json)
            val timesList = timesMatch?.groupValues?.get(1)?.split(",")
                ?.map { it.trim().trim('"') }
                ?.filter { it.isNotEmpty() } ?: emptyList()

            // Simple parser for daysOfWeek list: [1,2,3]
            val daysPattern = Regex("\"daysOfWeek\"\\s*:\\s*\\[([^\\]]*)\\]")
            val daysMatch = daysPattern.find(json)
            val daysList = daysMatch?.groupValues?.get(1)?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }

            return PrescriptionProposalPayload(
                medicineName = name,
                dosageText = dosage,
                pillsPerDose = pills,
                frequencyType = freq,
                timesOfDay = timesList,
                intervalHours = interval,
                daysOfWeek = daysList,
                startDate = startDate,
                endDate = endDate,
                foodRelation = food,
                instructions = instructions,
                initialStock = stock,
                doctorName = docName,
                reason = reason
            )
        }
    }
}
