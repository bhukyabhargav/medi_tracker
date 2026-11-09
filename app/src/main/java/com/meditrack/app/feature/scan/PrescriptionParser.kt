package com.meditrack.app.feature.scan

import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import java.time.LocalTime
import java.util.Locale

/**
 * Parsed information extracted from prescription text or medicine strip OCR.
 * Unknown or uncertain fields are represented as null or explicit review reasons,
 * requiring explicit user review before a schedule is generated.
 */
data class ParsedPrescription(
    val medicineName: String? = null,
    val dosageText: String? = null,
    val frequencyType: FrequencyType? = null, // null if unknown; NEVER defaulted silently
    val timesOfDay: List<LocalTime> = emptyList(),
    val intervalHours: Int? = null,
    val daysOfWeek: List<Int>? = null,
    val pillsPerDose: Int = 1,
    val initialStock: Int? = null, // null if unknown; NEVER defaulted to 10
    val foodRelation: FoodRelation = FoodRelation.ANY,
    val instructions: String = "",
    val durationDays: Int? = null,
    val isAsNeeded: Boolean = false, // PRN / SOS
    val requiresManualReview: Boolean = false,
    val reviewReasons: List<String> = emptyList(),
    val rawText: String = ""
)

data class FrequencyExtractionResult(
    val frequencyType: FrequencyType?,
    val timesOfDay: List<LocalTime>,
    val intervalHours: Int?,
    val daysOfWeek: List<Int>?,
    val pillsPerDose: Int?
)

/**
 * Pure Kotlin parser that extracts medicine fields from scanned OCR text.
 * Handles Indian and international prescription conventions, sig codes (OD, BD, TID, etc.),
 * 1-0-1 / 2-0-2 notation, dosage units, stock quantities, and food instructions.
 * Strictly avoids silent assumptions: missing frequencies and stocks are returned as null.
 */
object PrescriptionParser {

    // Supports units including % (e.g. 1% or 0.5%) without trailing word boundary breaking on non-word %
    private val DOSAGE_REGEX = Regex(
        """(?i)\b(\d+(?:\.\d+)?)\s*(mg|mcg|g|ml|iu|mEq|%)(?!\w)"""
    )

    private val PILLS_PER_DOSE_REGEX = Regex(
        """(?i)\btake\s*(\d+)\s*(?:tab|tablet|cap|capsule|pill)?\b|\b(\d+)\s*(?:tab|tablet|cap|capsule|pill)s?\s*each\s*time\b"""
    )

    // Known common medication keywords to assist extraction when headers/noise are present
    private val KNOWN_DRUG_NAMES = listOf(
        "Paracetamol", "Acetaminophen", "Metformin", "Amoxicillin", "Atorvastatin",
        "Pantoprazole", "Omeprazole", "Azithromycin", "Cetirizine", "Ibuprofen",
        "Aspirin", "Telmisartan", "Amlodipine", "Losartan", "Ciprofloxacin",
        "Doxycycline", "Metoprolol", "Gabapentin", "Levothyroxine", "Rosuvastatin",
        "Clopidogrel", "Montelukast", "Escitalopram", "Sertraline", "Alprazolam",
        "Diclofenac", "Ranitidine", "Domperidone", "Furosemide", "Hydrochlorothiazide",
        "Methotrexate", "Dolo", "Omez"
    )

    private val HEADER_PREFIXES = setOf(
        "dr", "doctor", "clinic", "hospital", "patient", "date", "name",
        "age", "sex", "reg", "mci", "signature", "pharmacy", "address",
        "phone", "tel", "email", "rx#"
    )

    fun parse(rawText: String): ParsedPrescription {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lowerText = rawText.lowercase(Locale.ROOT)
        val reasons = mutableListOf<String>()
        var manualReviewForced = false

        // B3: Extract dosage, detecting multiple strengths
        val dosageResults = extractDosages(rawText)
        val dosage = dosageResults.firstOrNull()
        if (dosage == null) {
            reasons.add("Dosage/strength could not be identified")
        } else if (dosageResults.distinct().size > 1) {
            reasons.add("Multiple strengths detected")
            manualReviewForced = true
        }

        // B2: Extract medicine name
        val nameResult = extractMedicineName(lines, dosage)
        val name = nameResult?.first
        if (name == null) {
            reasons.add("Medicine name could not be identified")
        } else if (nameResult.second) {
            reasons.add("Medicine name is a guess: please confirm")
            manualReviewForced = true
        }

        // B6: Multiple medicines in one text check
        val distinctKnownDrugs = KNOWN_DRUG_NAMES.filter { drug ->
            Regex("""(?i)\b${Regex.escape(drug)}\b""").containsMatchIn(rawText)
        }
        val medicineLineCount = lines.count { line ->
            Regex("""(?i)^\s*(?:(?:\d+[\.\)])|(?:(?:tab|cap|rx|syrup|inj)\b[\.:]?))""").containsMatchIn(line)
        }
        if (distinctKnownDrugs.size >= 2 || medicineLineCount >= 2) {
            reasons.add("Multiple medicines detected: scan one medicine at a time or confirm these values are for the first one only")
            manualReviewForced = true
        }

        // A2: Fractional doses check
        val fractionMatch = Regex("""(?i)\b(?:1/2|half|1/4|quarter)\s*(?:tablet|tab|pill|cap|capsule)?\b""").find(rawText)
        val hasFraction = fractionMatch != null
        if (fractionMatch != null) {
            val matchedVal = fractionMatch.value
            val fractionText = if (matchedVal.contains("1/4") || matchedVal.contains("quarter", ignoreCase = true)) "1/4 tablet" else "1/2 tablet"
            reasons.add("Fractional dose detected ($fractionText): the app counts whole pills; confirm how this should be tracked")
            manualReviewForced = true
        }

        // Check for PRN / SOS first (as-needed)
        val isPrnSos = Regex("""\b(?:prn|sos|as\s*needed|emergency\s*use|when\s*required)\b""").containsMatchIn(lowerText)

        val freqResult = if (isPrnSos) {
            reasons.add("As-needed medication (PRN/SOS): manual schedule decision required")
            FrequencyExtractionResult(null, emptyList(), null, null, null)
        } else {
            val res = extractFrequency(lowerText, rawText, reasons)
            if (res.pillsPerDose == null && reasons.any { it.startsWith("Different pill counts per time slot") }) {
                manualReviewForced = true
            }
            res
        }

        val foodRelation = extractFoodRelation(lowerText)
        val instructions = extractInstructions(lowerText, foodRelation, isPrnSos, hasFraction, fractionMatch?.value)
        val stock = extractStock(rawText, lowerText)
        if (stock == null) {
            reasons.add("Stock quantity not detected; please enter quantity")
        }

        // B5: Course duration
        val durationDays = extractCourseDuration(lowerText)

        val pillsPerDose = freqResult.pillsPerDose ?: extractPillsPerDose(lowerText)

        val manualReview = reasons.isNotEmpty() ||
                isPrnSos ||
                manualReviewForced ||
                freqResult.frequencyType == null ||
                (freqResult.frequencyType == FrequencyType.SPECIFIC_DAYS && freqResult.daysOfWeek.isNullOrEmpty()) ||
                (freqResult.frequencyType == FrequencyType.HOURLY && freqResult.intervalHours == null) ||
                stock == null

        return ParsedPrescription(
            medicineName = name,
            dosageText = dosage,
            frequencyType = freqResult.frequencyType,
            timesOfDay = freqResult.timesOfDay,
            intervalHours = freqResult.intervalHours,
            daysOfWeek = freqResult.daysOfWeek,
            pillsPerDose = pillsPerDose,
            initialStock = stock,
            foodRelation = foodRelation,
            instructions = instructions,
            durationDays = durationDays,
            isAsNeeded = isPrnSos,
            requiresManualReview = manualReview,
            reviewReasons = reasons,
            rawText = rawText
        )
    }

    private fun extractDosages(text: String): List<String> {
        val matches = DOSAGE_REGEX.findAll(text)
        return matches.map { match ->
            val num = match.groupValues[1]
            val unit = match.groupValues[2].lowercase(Locale.ROOT)
            "$num$unit"
        }.toList()
    }

    /**
     * Returns Pair<MedicineName, IsWeakFallback>
     */
    private fun extractMedicineName(lines: List<String>, dosage: String?): Pair<String, Boolean>? {
        fun isHeaderLine(line: String): Boolean {
            val stripped = line.trim().lowercase(Locale.ROOT).replace(Regex("""^[^a-z0-9]+"""), "")
            val firstToken = stripped.split(Regex("""[\s\.:\-_]+""")).firstOrNull() ?: ""
            return firstToken in HEADER_PREFIXES || stripped.startsWith("dr.") || stripped.startsWith("dr ")
        }

        fun isAllergyOrHistoryLine(line: String): Boolean {
            val lower = line.lowercase(Locale.ROOT)
            return lower.contains("allergic") || lower.contains("allergy") ||
                    lower.contains("history") || lower.contains("avoid") ||
                    lower.contains("not to take")
        }

        // 1. Check known drugs first (ignoring allergy/history/header lines)
        for (line in lines) {
            if (isAllergyOrHistoryLine(line) || isHeaderLine(line)) continue
            for (drug in KNOWN_DRUG_NAMES) {
                if (Regex("""(?i)\b${Regex.escape(drug)}\b""").containsMatchIn(line)) {
                    return Pair(drug, false)
                }
            }
        }

        // 2. Look for line with full words: Tablet, Tablets, Capsule, Capsules, Tab, Cap, Syrup, Inj, Injection, Rx
        val rxPrefixRegex = Regex(
            """(?i)^\s*(?:rx|tablets?|capsules?|tabs?|caps?|syrups?|injections?|inj|medicines?|drugs?)\b\s*[:.-]?\s*([A-Za-z0-9\s-]+)"""
        )
        for (line in lines) {
            if (isAllergyOrHistoryLine(line) || isHeaderLine(line)) continue
            val m = rxPrefixRegex.find(line)
            if (m != null) {
                val candidatePart = m.groupValues[1].split(Regex("""\d"""))[0].trim()
                val candidate = candidatePart.split(Regex("""\s+""")).firstOrNull()?.trim() ?: ""
                val cleanWord = candidate.replace(Regex("""[^A-Za-z-]"""), "")
                if (cleanWord.length >= 3 && cleanWord.lowercase(Locale.ROOT) !in HEADER_PREFIXES) {
                    return Pair(cleanWord.replaceFirstChar { it.uppercase() }, false)
                }
            }
        }

        // 3. Look around dosage if found (search with optional whitespace between number and unit)
        if (dosage != null) {
            val num = dosage.filter { it.isDigit() || it == '.' }
            val unit = dosage.filter { it.isLetter() || it == '%' }
            val dosagePattern = Regex("""(?i)\b${Regex.escape(num)}\s*${Regex.escape(unit)}(?!\w)""")

            for (line in lines) {
                if (isAllergyOrHistoryLine(line) || isHeaderLine(line)) continue
                val match = dosagePattern.find(line)
                if (match != null) {
                    val idx = match.range.first
                    val before = line.substring(0, idx).trim()
                        .replace(Regex("""(?i)^\s*(?:rx|tablets?|capsules?|tabs?|caps?|syrups?|injections?|inj)\b\s*[:.-]?"""), "")
                        .trim()
                    val candidateWords = before.split(Regex("""\s+""")).filter { it.isNotBlank() }
                    if (candidateWords.isNotEmpty()) {
                        val word = candidateWords.last().replace(Regex("""[^A-Za-z-]"""), "")
                        if (word.length >= 3 && word.lowercase(Locale.ROOT) !in HEADER_PREFIXES) {
                            return Pair(word.replaceFirstChar { it.uppercase() }, false)
                        }
                    }
                }
            }
        }

        // 4. Weak fallback: line that has alphabetical characters and isn't a header/allergy
        for (line in lines) {
            if (isAllergyOrHistoryLine(line) || isHeaderLine(line)) continue
            val clean = line.replace(Regex("""[^A-Za-z\s]"""), "").trim()
            val firstWord = clean.split(Regex("""\s+""")).firstOrNull()?.lowercase(Locale.ROOT) ?: ""
            if (clean.length in 3..25 && firstWord !in HEADER_PREFIXES && !line.lowercase(Locale.ROOT).contains("dr.")) {
                val word = clean.split(Regex("""\s+""")).first()
                if (word.length >= 3) {
                    return Pair(word.replaceFirstChar { it.uppercase() }, true)
                }
            }
        }

        return null
    }

    private fun extractFrequency(lowerText: String, rawText: String, reasons: MutableList<String>): FrequencyExtractionResult {
        // Hourly / Interval range check (e.g. "every 6 to 8 hours", "every 4-6 hours")
        val intervalRangeRegex = Regex("""\b(?:every|q)\s*(\d{1,2})\s*(?:to|-|or)\s*(\d{1,2})\s*(?:hours?|hrs?|h)\b""")
        val rangeMatch = intervalRangeRegex.find(lowerText)
        if (rangeMatch != null) {
            reasons.add("Ambiguous interval range detected; please select interval hours")
            return FrequencyExtractionResult(FrequencyType.HOURLY, listOf(LocalTime.of(8, 0)), null, null, null)
        }

        // Single interval pattern (e.g. "every 8 hours", "every 6 hours", "q8h", "q6h")
        val intervalRegex = Regex("""\b(?:every|q)\s*(\d{1,2})\s*(?:hours?|hrs?|h)\b""")
        val intervalMatch = intervalRegex.find(lowerText)
        if (intervalMatch != null) {
            val hours = intervalMatch.groupValues[1].toIntOrNull()
            return if (hours != null && hours in 1..24) {
                FrequencyExtractionResult(FrequencyType.HOURLY, listOf(LocalTime.of(8, 0)), hours, null, null)
            } else {
                reasons.add("Interval hours must be between 1 and 24 hours")
                FrequencyExtractionResult(FrequencyType.HOURLY, listOf(LocalTime.of(8, 0)), null, null, null)
            }
        }

        // A3: Indian Matrix notation: ONLY dash separators (- or – or —). NOT / (reject dates like 5/6/7)
        // Must not be preceded or followed by digits, / or .
        val matrixRegex = Regex("""(?<![\d/.])([0-9])\s*[-–—]\s*([0-9])\s*[-–—]\s*([0-9])(?:\s*[-–—]\s*([0-9]))?(?![\d/.])""")
        val matrixMatch = matrixRegex.find(rawText)
        if (matrixMatch != null) {
            val m = matrixMatch.groupValues[1].toIntOrNull() ?: 0
            val a = matrixMatch.groupValues[2].toIntOrNull() ?: 0
            val e = matrixMatch.groupValues[3].toIntOrNull() ?: 0
            val hasFourthSlot = matrixMatch.groupValues.getOrNull(4)?.isNotEmpty() == true
            val night = if (hasFourthSlot) matrixMatch.groupValues[4].toIntOrNull() ?: 0 else 0

            val times = mutableListOf<LocalTime>()
            if (hasFourthSlot) {
                // 4-slot case: 08:00, 12:00, 16:00, 20:00 per spec
                if (m > 0) times.add(LocalTime.of(8, 0))
                if (a > 0) times.add(LocalTime.of(12, 0))
                if (e > 0) times.add(LocalTime.of(16, 0))
                if (night > 0) times.add(LocalTime.of(20, 0))
            } else {
                // 3-slot case: 08:00, 14:00, 20:00
                if (m > 0) times.add(LocalTime.of(8, 0))
                if (a > 0) times.add(LocalTime.of(14, 0))
                if (e > 0) times.add(LocalTime.of(20, 0))
            }

            val nonZeroCounts = if (hasFourthSlot) {
                listOfNotNull(m.takeIf { it > 0 }, a.takeIf { it > 0 }, e.takeIf { it > 0 }, night.takeIf { it > 0 })
            } else {
                listOfNotNull(m.takeIf { it > 0 }, a.takeIf { it > 0 }, e.takeIf { it > 0 })
            }

            val pillsPerDose: Int
            if (nonZeroCounts.isNotEmpty() && nonZeroCounts.distinct().size == 1) {
                pillsPerDose = nonZeroCounts.first()
            } else {
                pillsPerDose = 1
                val matrixStr = matrixMatch.value.trim()
                reasons.add("Different pill counts per time slot ($matrixStr): the app supports one count per dose; confirm or split into separate medicines")
            }

            if (times.isNotEmpty()) {
                return FrequencyExtractionResult(
                    FrequencyType.DAILY,
                    times,
                    null,
                    null,
                    if (nonZeroCounts.distinct().size == 1) pillsPerDose else null
                )
            }
        }

        // A1: Weekly schedule
        if (Regex("""\b(?:once\s*weekly|weekly|1\s*time\s*a\s*week)\b""").containsMatchIn(lowerText)) {
            val dayOfWeek = extractNamedDayOfWeek(lowerText)
            return if (dayOfWeek != null) {
                FrequencyExtractionResult(
                    FrequencyType.SPECIFIC_DAYS,
                    listOf(LocalTime.of(8, 0)),
                    null,
                    listOf(dayOfWeek),
                    null
                )
            } else {
                reasons.add("Weekly medicine: choose the day of the week")
                FrequencyExtractionResult(
                    FrequencyType.SPECIFIC_DAYS,
                    listOf(LocalTime.of(8, 0)),
                    null,
                    null,
                    null
                )
            }
        }

        // A1: Alternate days: DO NOT map to Mon/Wed/Fri
        if (Regex("""\b(?:alternate\s*days?|every\s*other\s*day)\b""").containsMatchIn(lowerText)) {
            reasons.add("Alternate days schedule: please select specific days of week")
            reasons.add("Alternate-day dosing cannot be represented exactly (a 7-day week has an odd number of days); please select specific days of week")
            return FrequencyExtractionResult(
                FrequencyType.SPECIFIC_DAYS,
                listOf(LocalTime.of(8, 0)),
                null,
                null,
                null
            )
        }

        // QID / 4 times daily
        if (Regex("""\b(?:qid|4\s*times\s*(?:a\s*day|daily)|four\s*times\s*daily)\b""").containsMatchIn(lowerText)) {
            return FrequencyExtractionResult(
                FrequencyType.DAILY,
                listOf(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(16, 0), LocalTime.of(20, 0)),
                null, null, null
            )
        }

        // TID / TDS / 3 times daily
        if (Regex("""\b(?:tid|tds|3\s*times\s*(?:a\s*day|daily)|thrice\s*daily|three\s*times\s*daily)\b""").containsMatchIn(lowerText)) {
            return FrequencyExtractionResult(
                FrequencyType.DAILY,
                listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)),
                null, null, null
            )
        }

        // BD / BID / Twice daily
        if (Regex("""\b(?:bd|bid|twice\s*(?:a\s*day|daily)|2\s*times\s*(?:a\s*day|daily))\b""").containsMatchIn(lowerText)) {
            return FrequencyExtractionResult(
                FrequencyType.DAILY,
                listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                null, null, null
            )
        }

        // Bedtime / HS / Night (using word boundary)
        if (Regex("""\b(?:hs|hora\s*somni|at\s*bedtime|bedtime|night\s*only)\b""").containsMatchIn(lowerText)) {
            return FrequencyExtractionResult(
                FrequencyType.DAILY,
                listOf(LocalTime.of(21, 0)),
                null, null, null
            )
        }

        // OD / Once daily
        if (Regex("""\b(?:od|once\s*(?:a\s*day|daily)|1\s*time\s*(?:a\s*day|daily)|daily)\b""").containsMatchIn(lowerText)) {
            return FrequencyExtractionResult(
                FrequencyType.DAILY,
                listOf(LocalTime.of(8, 0)),
                null, null, null
            )
        }

        // No frequency detected: DO NOT SILENTLY DEFAULT
        reasons.add("Frequency could not be identified; please choose frequency and times")
        return FrequencyExtractionResult(null, emptyList(), null, null, null)
    }

    private fun extractNamedDayOfWeek(text: String): Int? {
        val match = Regex("""\b(monday|mon|tuesday|tue|wednesday|wed|thursday|thu|friday|fri|saturday|sat|sunday|sun)\b""")
            .find(text) ?: return null
        return when (match.groupValues[1]) {
            "monday", "mon" -> 1
            "tuesday", "tue" -> 2
            "wednesday", "wed" -> 3
            "thursday", "thu" -> 4
            "friday", "fri" -> 5
            "saturday", "sat" -> 6
            "sunday", "sun" -> 7
            else -> null
        }
    }

    private fun extractFoodRelation(text: String): FoodRelation {
        return when {
            Regex("""\b(?:after\s*(?:food|meals?)|pc|post\s*cibum)\b""").containsMatchIn(text) -> FoodRelation.AFTER
            Regex("""\b(?:before\s*(?:food|meals?)|ac|ante\s*cibum|empty\s*stomach)\b""").containsMatchIn(text) -> FoodRelation.BEFORE
            else -> FoodRelation.ANY
        }
    }

    private fun extractInstructions(
        text: String,
        foodRelation: FoodRelation,
        isPrn: Boolean,
        hasFraction: Boolean,
        fractionStr: String?
    ): String {
        val parts = mutableListOf<String>()
        if (isPrn) {
            parts.add("Take as needed (PRN/SOS)")
        }
        if (hasFraction) {
            if (fractionStr?.contains("1/4", ignoreCase = true) == true || fractionStr?.contains("quarter", ignoreCase = true) == true) {
                parts.add("Take 1/4 tablet")
            } else {
                parts.add("Take 1/2 tablet")
            }
        }
        when (foodRelation) {
            FoodRelation.AFTER -> parts.add("Take after food")
            FoodRelation.BEFORE -> parts.add("Take before food")
            FoodRelation.ANY -> {
                if (Regex("""\bwith\s*(?:food|meals?)\b""").containsMatchIn(text)) {
                    parts.add("Take with food")
                }
            }
        }
        // B4: Strict word boundary regexes for hs and bedtime (prevents "months" from matching "hs")
        if (Regex("""\b(?:hs|bedtime)\b""").containsMatchIn(text)) {
            parts.add("At bedtime")
        }
        if (Regex("""\b(?:water|warm\s*water)\b""").containsMatchIn(text)) {
            parts.add("With plenty of water")
        }
        return parts.joinToString(". ")
    }

    private fun extractStock(rawText: String, lowerText: String): Int? {
        // Explicit keywords: qty, quantity, strip of, pack of, count (REMOVED 'total')
        // Reject matches preceded or followed by currency or decimals
        val explicitKeywordRegex = Regex(
            """(?i)(?<![₹$]|rs\.?\s*)\b(?:qty|quantity|strip of|pack of|count)\s*[:=-]?\s*(\d{1,4})(?!\s*[\.\d]|(?:\s*(?:rs|inr|[₹$])))\b"""
        )
        val explicitMatch = explicitKeywordRegex.find(rawText)
        if (explicitMatch != null) {
            val digits = explicitMatch.groupValues[1].toIntOrNull()
            if (digits != null && digits in 1..1000) return digits
        }

        // Check general "N tablets" / "N capsules"
        // Skip matches preceded by take, give, each, or currency
        // Skip matches followed by decimal point or currency
        // Skip matches followed within 25 chars by frequency words (daily, twice, bd, od, tid, qid, times, every, once, hours)
        val generalRegex = Regex(
            """(?i)(?<!take\s)(?<!give\s)(?<!each\s)(?<![₹$])(?<!rs\.?\s*)\b(\d{1,3})\s*(?:tablets?|capsules?|tabs?|caps?)\b(?!\s*[\.\d]|(?:\s*(?:rs|inr|[₹$])))"""
        )
        for (match in generalRegex.findAll(lowerText)) {
            val startIdx = match.range.first
            val endIdx = match.range.last

            // Check preceding 15 chars for 'take' or 'each'
            val precText = lowerText.substring(maxOf(0, startIdx - 15), startIdx)
            if (precText.contains("take") || precText.contains("each") || precText.contains("give")) {
                continue
            }

            // Check subsequent 25 chars for frequency words
            val followText = lowerText.substring(endIdx + 1, minOf(lowerText.length, endIdx + 26))
            val isFrequencyFollow = Regex("""\b(?:daily|twice|bd|bid|od|tid|qid|times|every|once|hours?|hrs?)\b""").containsMatchIn(followText)
            if (isFrequencyFollow) {
                continue
            }

            val digits = match.groupValues[1].toIntOrNull()
            if (digits != null && digits in 1..500) {
                return digits
            }
        }
        return null
    }

    private fun extractCourseDuration(lowerText: String): Int? {
        val durationRegex = Regex(
            """(?i)\b(?:for|x)\s*(\d+)\s*(days?|weeks?|months?)\b|\b(\d+)\s*(days?|weeks?|months?)\s*course\b"""
        )
        val match = durationRegex.find(lowerText) ?: return null
        val numStr = match.groupValues[1].ifEmpty { match.groupValues[3] }
        val unitStr = match.groupValues[2].ifEmpty { match.groupValues[4] }.lowercase(Locale.ROOT)
        val num = numStr.toIntOrNull() ?: return null

        return when {
            unitStr.startsWith("day") -> num
            unitStr.startsWith("week") -> num * 7
            unitStr.startsWith("month") -> num * 30
            else -> num
        }
    }

    private fun extractPillsPerDose(text: String): Int {
        val match = PILLS_PER_DOSE_REGEX.find(text) ?: return 1
        val v1 = match.groupValues.getOrNull(1)
        val v2 = match.groupValues.getOrNull(2)
        val digits = (if (!v1.isNullOrBlank()) v1 else v2)?.toIntOrNull()
        return if (digits != null && digits in 1..10) digits else 1
    }
}
