package com.meditrack.app.feature.scan

import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import java.time.LocalTime
import java.util.Locale

data class ParsedPrescription(
    val medicineName: String? = null,
    val dosageText: String? = null,
    val frequencyType: FrequencyType = FrequencyType.DAILY,
    val timesOfDay: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    val pillsPerDose: Int = 1,
    val intervalHours: Int? = null,
    val daysOfWeek: List<Int>? = null,
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val instructions: String = "",
    val rawText: String = ""
)

object PrescriptionParser {

    private val PREFIX_CLEANUP_REGEX = Regex(
        """(?i)^(?:sig[:.]?|take\s+\d+\s+(?:tablets?|capsules?|pills?|tabs?|caps?)|take\s+|rx[:.]?|tab(?:let)?[:.]?|cap(?:sule)?[:.]?|syp(?:rup)?[:.]?|inj(?:ection)?[:.]?|\d+[\.\-\)]\s*)\s*"""
    )

    private val DOSAGE_REGEX = Regex(
        """(?i)\b(\d+(?:\.\d+)?\s*(?:mg|mcg|g|ml|iu))\b"""
    )

    private val INTERVAL_HOURS_REGEX = Regex(
        """(?i)(?:every|q)\s*(\d+)\s*(?:hours|hrs|hr|h)\b"""
    )

    private val PILLS_PER_DOSE_REGEX = Regex(
        """(?i)(?:take\s+(\d+)\s*(?:tablets?|capsules?|tabs?|caps?|pills?)\b|(?<![a-zA-Z\d])(\d+)\s*(?:tablets?|capsules?|tabs?|caps?|pills?)\b)"""
    )

    private val DURATION_REGEX = Regex(
        """(?i)\b(for\s+\d+\s+(?:days?|weeks?|months?))\b"""
    )

    private val META_LINE_KEYWORDS = listOf(
        "doctor", "dr.", "dr ", "clinic", "hospital", "patient", "date:", "date :",
        "phone", "signature", "reg.", "reg no", "qty", "quantity", "address",
        "rx prescription", "prescription", "age:", "sex:", "gender:"
    )

    private val FREQUENCY_KEYWORDS = listOf(
        "once daily", "twice daily", "thrice daily", "three times daily", "four times daily",
        "once a day", "twice a day", "three times a day", "four times a day",
        "daily", "bid", "b.i.d", "bd", "tid", "t.i.d", "tds", "qid", "q.i.d", "od", "qd",
        "1-0-1", "1-1-1", "1-1-1-1", "1-0-0", "0-1-0", "0-0-1"
    )

    fun parse(rawText: String): ParsedPrescription {
        if (rawText.isBlank()) {
            return ParsedPrescription(rawText = rawText)
        }

        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // 1. Extract dosage strength (e.g. 500mg, 250 mg)
        val dosageMatch = DOSAGE_REGEX.find(rawText)
        val dosageText = dosageMatch?.value?.trim()

        // 2. Extract frequency and times
        val (frequencyType, timesOfDay, intervalHours) = parseFrequency(rawText)

        // 3. Extract food relation
        val foodRelation = parseFoodRelation(rawText)

        // 4. Extract pills per dose
        val pillsPerDose = parsePillsPerDose(rawText)

        // 5. Extract additional instructions
        val instructions = parseInstructions(rawText)

        // 6. Extract medicine name
        val medicineName = parseMedicineName(lines, dosageText)

        return ParsedPrescription(
            medicineName = medicineName,
            dosageText = dosageText,
            frequencyType = frequencyType,
            timesOfDay = timesOfDay,
            pillsPerDose = pillsPerDose,
            intervalHours = intervalHours,
            foodRelation = foodRelation,
            instructions = instructions,
            rawText = rawText
        )
    }

    private fun parseFrequency(text: String): Triple<FrequencyType, List<LocalTime>, Int?> {
        val lower = text.lowercase(Locale.ROOT)

        // Interval: every 8 hours, q8h, etc.
        val intervalMatch = INTERVAL_HOURS_REGEX.find(lower)
        if (intervalMatch != null) {
            val hours = intervalMatch.groupValues[1].toIntOrNull() ?: 6
            return Triple(FrequencyType.HOURLY, emptyList(), hours)
        }

        // QID / 4 times daily / 1-1-1-1
        if (lower.contains("1-1-1-1") ||
            Regex("""(?i)\b(?:qid|q\.i\.d|four times daily|four times a day|4 times daily|4 times a day)\b""").containsMatchIn(text)
        ) {
            return Triple(
                FrequencyType.DAILY,
                listOf(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(16, 0), LocalTime.of(20, 0)),
                null
            )
        }

        // TDS / TID / 3 times daily / 1-1-1
        if (lower.contains("1-1-1") ||
            Regex("""(?i)\b(?:tds|tid|t\.i\.d|thrice daily|three times daily|three times a day|3 times daily|3 times a day)\b""").containsMatchIn(text)
        ) {
            return Triple(
                FrequencyType.DAILY,
                listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)),
                null
            )
        }

        // BD / BID / twice daily / 1-0-1
        if (lower.contains("1-0-1") ||
            Regex("""(?i)\b(?:bd|bid|b\.i\.d|twice daily|twice a day|two times daily|2 times daily|2 times a day)\b""").containsMatchIn(text)
        ) {
            return Triple(
                FrequencyType.DAILY,
                listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                null
            )
        }

        // OD / QD / once daily / 1-0-0 / 0-1-0 / 0-0-1
        if (lower.contains("1-0-0") || lower.contains("0-1-0") || lower.contains("0-0-1") ||
            Regex("""(?i)\b(?:od|qd|q\.d|once daily|once a day|daily)\b""").containsMatchIn(text)
        ) {
            val time = when {
                Regex("""(?i)\b(?:bedtime|night|hs|h\.s|at night)\b""").containsMatchIn(text) || lower.contains("0-0-1") ->
                    LocalTime.of(20, 0)
                lower.contains("0-1-0") || Regex("""(?i)\b(?:afternoon|lunch)\b""").containsMatchIn(text) ->
                    LocalTime.of(14, 0)
                else ->
                    LocalTime.of(8, 0)
            }
            return Triple(FrequencyType.DAILY, listOf(time), null)
        }

        // Default daily 8:00
        return Triple(FrequencyType.DAILY, listOf(LocalTime.of(8, 0)), null)
    }

    private fun parseFoodRelation(text: String): FoodRelation {
        if (Regex("""(?i)\b(?:before\s+(?:food|meals?|breakfast|lunch|dinner)|empty\s+stomach|ac|ante\s+cibum)\b""").containsMatchIn(text)) {
            return FoodRelation.BEFORE
        }
        if (Regex("""(?i)\b(?:after\s+(?:food|meals?|breakfast|lunch|dinner)|with\s+(?:food|meals?)|pc|post\s+cibum)\b""").containsMatchIn(text)) {
            return FoodRelation.AFTER
        }
        return FoodRelation.ANY
    }

    private fun parsePillsPerDose(text: String): Int {
        val match = PILLS_PER_DOSE_REGEX.find(text)
        if (match != null) {
            val countStr = match.groupValues[1].ifBlank { match.groupValues[2] }
            val count = countStr.toIntOrNull()
            if (count != null && count in 1..10) {
                return count
            }
        }
        return 1
    }

    private fun parseInstructions(text: String): String {
        val parts = mutableListOf<String>()

        val durationMatch = DURATION_REGEX.find(text)
        if (durationMatch != null) {
            parts.add(durationMatch.value.trim())
        }

        if (Regex("""(?i)\b(?:at bedtime|bedtime|at night)\b""").containsMatchIn(text)) {
            parts.add("At bedtime")
        }

        if (Regex("""(?i)\bwith (?:plenty of )?water\b""").containsMatchIn(text)) {
            parts.add("Take with water")
        }

        return parts.distinct().joinToString(", ")
    }

    private fun parseMedicineName(lines: List<String>, dosageText: String?): String? {
        // Strategy A: Find line containing dosageText and take text preceding it
        if (!dosageText.isNullOrBlank()) {
            for (line in lines) {
                val lower = line.lowercase(Locale.ROOT)
                if (isMetaLine(lower)) continue

                val idx = line.indexOf(dosageText, ignoreCase = true)
                if (idx > 0) {
                    val candidate = line.substring(0, idx).trim()
                    val cleaned = cleanMedicineName(candidate)
                    if (cleaned.isNotBlank() && !isMetaWord(cleaned)) {
                        return cleaned
                    }
                } else if (idx == 0) {
                    // Dosage is at the start of line, candidate name follows it
                    val candidateAfter = line.substring(dosageText.length).trim()
                    val strippedAfter = removeFrequencyAndFoodWords(candidateAfter)
                    val cleaned = cleanMedicineName(strippedAfter)
                    if (cleaned.isNotBlank() && !isMetaWord(cleaned)) {
                        return cleaned
                    }
                }
            }
        }

        // Strategy B: If dosage is on its own line or following line, check previous non-meta line
        for (i in lines.indices) {
            val line = lines[i]
            val lower = line.lowercase(Locale.ROOT)
            if (isMetaLine(lower)) continue

            // If this line has dosage and previous line exists and is non-meta
            if (!dosageText.isNullOrBlank() && line.contains(dosageText, ignoreCase = true) && i > 0) {
                val prevLine = lines[i - 1]
                if (!isMetaLine(prevLine.lowercase(Locale.ROOT))) {
                    val cleaned = cleanMedicineName(prevLine)
                    if (cleaned.isNotBlank() && !isMetaWord(cleaned)) {
                        return cleaned
                    }
                }
            }
        }

        // Strategy C: First non-meta line that has alphanumeric content
        for (line in lines) {
            val lower = line.lowercase(Locale.ROOT)
            if (isMetaLine(lower)) continue

            val stripped = removeFrequencyAndFoodWords(line)
            val cleaned = cleanMedicineName(stripped)
            if (cleaned.isNotBlank() && !isMetaWord(cleaned) && cleaned.length >= 3) {
                return cleaned
            }
        }

        return null
    }

    private fun isMetaLine(lower: String): Boolean {
        return META_LINE_KEYWORDS.any { lower.contains(it) } ||
                lower.startsWith("qty:")
    }

    private fun isMetaWord(word: String): Boolean {
        val lower = word.lowercase(Locale.ROOT)
        return META_LINE_KEYWORDS.any { lower == it || lower.startsWith(it) } ||
                lower in FREQUENCY_KEYWORDS
    }

    private fun removeFrequencyAndFoodWords(text: String): String {
        var result = text
        for (kw in FREQUENCY_KEYWORDS) {
            result = result.replace(Regex("""(?i)\b\Q$kw\E\b"""), "")
        }
        result = result.replace(Regex("""(?i)\b(?:after|before|with)\s+(?:meals?|food|breakfast|lunch|dinner)\b"""), "")
        result = result.replace(Regex("""(?i)\b(?:for\s+\d+\s+(?:days?|weeks?|months?))\b"""), "")
        return result.trim()
    }

    private fun cleanMedicineName(rawName: String): String {
        var cleaned = rawName.trim()
        cleaned = PREFIX_CLEANUP_REGEX.replace(cleaned, "").trim()
        // Remove trailing non-word punctuation
        cleaned = cleaned.replace(Regex("""[^\w\s]+$"""), "").trim()
        // Remove words like Tablet or Capsule inside
        cleaned = cleaned.replace(Regex("""(?i)\b(?:tablets?|capsules?|syrup|drops?)\b"""), "").trim()
        // Collapse multiple spaces
        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }
}
