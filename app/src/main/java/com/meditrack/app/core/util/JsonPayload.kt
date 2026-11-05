package com.meditrack.app.core.util

/**
 * Pure-Kotlin JSON serializer and simple reader for notification outbox payloads and data models.
 * Avoids dependency on org.json.JSONObject so JVM unit tests run without Android framework mocks.
 */
object JsonPayload {

    fun build(vararg pairs: Pair<String, Any?>): String = build(pairs.toList())

    fun build(pairs: List<Pair<String, Any?>>): String {
        val entries = pairs.map { (key, value) ->
            "\"${escape(key)}\":${formatValue(value)}"
        }
        return "{${entries.joinToString(",")}}"
    }

    fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code in 0..0x1F) {
                        val hex = c.code.toString(16).padStart(4, '0')
                        sb.append("\\u$hex")
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            is String -> "\"${escape(value)}\""
            is List<*> -> {
                val items = value.joinToString(",") { formatValue(it) }
                "[$items]"
            }
            is Map<*, *> -> {
                val entries = value.entries.joinToString(",") { (k, v) ->
                    "\"${escape(k.toString())}\":${formatValue(v)}"
                }
                "{$entries}"
            }
            else -> "\"${escape(value.toString())}\""
        }
    }

    /**
     * Extracts a top-level string field from a JSON object string.
     */
    fun getString(json: String, key: String): String? {
        val pattern = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val match = pattern.find(json) ?: return null
        return unescape(match.groupValues[1])
    }

    /**
     * Extracts a top-level number field as Long.
     */
    fun getLong(json: String, key: String): Long? {
        val pattern = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+)")
        val match = pattern.find(json) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    fun getInt(json: String, key: String): Int? = getLong(json, key)?.toInt()

    fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c)
                        i++
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
