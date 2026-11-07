package com.meditrack.app.core.util

/**
 * Tiny pure-Kotlin JSON object builder for outbox payloads.
 *
 * org.json is only a stub in plain JVM unit tests, and string interpolation breaks as soon as a
 * medicine or person name contains a quote or backslash. This helper escapes everything correctly
 * and has no Android dependency.
 */
object JsonPayload {

    fun build(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in pairs) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(key)).append("\":")
            when (value) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(value.toString())
                else -> sb.append('"').append(escape(value.toString())).append('"')
            }
        }
        return sb.append('}').toString()
    }

    fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
