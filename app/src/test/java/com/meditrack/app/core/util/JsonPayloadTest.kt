package com.meditrack.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonPayloadTest {

    @Test
    fun buildAndParseSimpleFields() {
        val json = JsonPayload.build(
            "name" to "Metformin",
            "count" to 10,
            "enabled" to true
        )
        assertEquals("Metformin", JsonPayload.getString(json, "name"))
        assertEquals(10L, JsonPayload.getLong(json, "count"))
        assertEquals(10, JsonPayload.getInt(json, "count"))
    }

    @Test
    fun handleQuotesBackslashesAndNewlines() {
        val complex = "Line 1\nLine 2 with \"quotes\" and \\backslash\\ and \t tab"
        val json = JsonPayload.build("key" to complex)
        val extracted = JsonPayload.getString(json, "key")
        assertEquals(complex, extracted)
    }

    @Test
    fun handleUnicodeCharacters() {
        val unicode = "Paracétamol 500mg — dosage: 💊 / 50µg"
        val json = JsonPayload.build("unicode_field" to unicode)
        val extracted = JsonPayload.getString(json, "unicode_field")
        assertEquals(unicode, extracted)
    }

    @Test
    fun missingFieldReturnsNull() {
        val json = JsonPayload.build("foo" to "bar")
        assertNull(JsonPayload.getString(json, "missing"))
        assertNull(JsonPayload.getLong(json, "missing"))
    }
}
