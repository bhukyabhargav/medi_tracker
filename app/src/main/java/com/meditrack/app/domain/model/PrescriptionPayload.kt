package com.meditrack.app.domain.model

data class PrescriptionPayload(
    val medicineName: String,
    val dosageText: String,
    val pillsPerDose: Int = 1,
    val frequencyType: FrequencyType = FrequencyType.DAILY,
    val times: List<String> = listOf("09:00"),
    val startDate: String,
    val endDate: String? = null,
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val additionalInstructions: String? = null,
    val initialStock: Int = 30,
    val revision: Int = 1,
    val clarificationNote: String? = null,
    val doctorName: String = "Doctor"
) {
    fun toJson(): String {
        val timesStr = times.joinToString(",") { "\"${escape(it)}\"" }
        val sb = StringBuilder("{")
        sb.append("\"medicineName\":\"${escape(medicineName)}\",")
        sb.append("\"dosageText\":\"${escape(dosageText)}\",")
        sb.append("\"pillsPerDose\":$pillsPerDose,")
        sb.append("\"frequencyType\":\"${frequencyType.name}\",")
        sb.append("\"times\":[$timesStr],")
        sb.append("\"startDate\":\"${escape(startDate)}\",")
        if (endDate != null) sb.append("\"endDate\":\"${escape(endDate)}\",")
        sb.append("\"foodRelation\":\"${foodRelation.name}\",")
        if (additionalInstructions != null) sb.append("\"additionalInstructions\":\"${escape(additionalInstructions)}\",")
        sb.append("\"initialStock\":$initialStock,")
        sb.append("\"revision\":$revision,")
        if (clarificationNote != null) sb.append("\"clarificationNote\":\"${escape(clarificationNote)}\",")
        sb.append("\"doctorName\":\"${escape(doctorName)}\"")
        sb.append("}")
        return sb.toString()
    }

    companion object {
        private fun escape(s: String): String {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        }

        fun fromJson(jsonStr: String): PrescriptionPayload {
            fun extractString(key: String): String? {
                val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(jsonStr)
                return match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\\\", "\\")
            }

            fun extractInt(key: String, default: Int): Int {
                val match = Regex("\"$key\"\\s*:\\s*(\\d+)").find(jsonStr)
                return match?.groupValues?.get(1)?.toIntOrNull() ?: default
            }

            val timesList = mutableListOf<String>()
            val timesMatch = Regex("\"times\"\\s*:\\s*\\[([^\\]]*)\\]").find(jsonStr)
            if (timesMatch != null) {
                val items = timesMatch.groupValues[1].split(",")
                for (item in items) {
                    val clean = item.replace("\"", "").trim()
                    if (clean.isNotEmpty()) timesList.add(clean)
                }
            }
            if (timesList.isEmpty()) {
                timesList.add("09:00")
            }

            val freqStr = extractString("frequencyType") ?: "DAILY"
            val foodStr = extractString("foodRelation") ?: "ANY"

            return PrescriptionPayload(
                medicineName = extractString("medicineName") ?: "Medicine",
                dosageText = extractString("dosageText") ?: "1 pill",
                pillsPerDose = extractInt("pillsPerDose", 1),
                frequencyType = try {
                    FrequencyType.valueOf(freqStr)
                } catch (_: Exception) {
                    FrequencyType.DAILY
                },
                times = timesList,
                startDate = extractString("startDate") ?: "",
                endDate = extractString("endDate"),
                foodRelation = try {
                    FoodRelation.valueOf(foodStr)
                } catch (_: Exception) {
                    FoodRelation.ANY
                },
                additionalInstructions = extractString("additionalInstructions"),
                initialStock = extractInt("initialStock", 30),
                revision = extractInt("revision", 1),
                clarificationNote = extractString("clarificationNote"),
                doctorName = extractString("doctorName") ?: "Doctor"
            )
        }
    }
}
