package com.meditrack.app.feature.verification.pipeline

import com.meditrack.app.core.config.AppConfig

object NameMatcher {

    /**
     * Checks if the OCR text matches the expected medicine name with at least
     * NAME_MATCH_MIN_SIMILARITY (0.6) similarity.
     */
    fun matches(
        ocrText: String,
        expectedMedicineName: String,
        threshold: Double = AppConfig.NAME_MATCH_MIN_SIMILARITY
    ): Boolean {
        if (ocrText.isBlank() || expectedMedicineName.isBlank()) return false

        val cleanOcr = normalize(ocrText)
        val cleanExpected = normalize(expectedMedicineName)

        // 1. Direct contains check
        if (cleanOcr.contains(cleanExpected)) return true

        // 2. Token-level similarity: check each token and window of tokens in the OCR text
        val ocrWords = cleanOcr.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val expectedWords = cleanExpected.split("\\s+".toRegex()).filter { it.isNotBlank() }

        var maxSimilarity = computeLevenshteinSimilarity(cleanOcr, cleanExpected)

        for (word in ocrWords) {
            val sim = computeLevenshteinSimilarity(word, cleanExpected)
            if (sim > maxSimilarity) {
                maxSimilarity = sim
            }
        }

        // Window-based match if multi-word name
        if (expectedWords.size > 1 && ocrWords.size >= expectedWords.size) {
            for (i in 0..ocrWords.size - expectedWords.size) {
                val window = ocrWords.subList(i, i + expectedWords.size).joinToString(" ")
                val sim = computeLevenshteinSimilarity(window, cleanExpected)
                if (sim > maxSimilarity) {
                    maxSimilarity = sim
                }
            }
        }

        return maxSimilarity >= threshold
    }

    fun computeLevenshteinSimilarity(s1: String, s2: String): Double {
        val clean1 = normalize(s1)
        val clean2 = normalize(s2)

        if (clean1 == clean2) return 1.0
        if (clean1.isEmpty() || clean2.isEmpty()) return 0.0

        val maxLen = maxOf(clean1.length, clean2.length)
        val dist = levenshteinDistance(clean1, clean2)
        return (1.0 - (dist.toDouble() / maxLen)).coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[s1.length][s2.length]
    }

    private fun normalize(str: String): String {
        return str.lowercase()
            .replace("[^a-z0-9\\s]".toRegex(), "")
            .trim()
    }
}
