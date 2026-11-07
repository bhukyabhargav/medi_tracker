package com.meditrack.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonPayloadTest {

    @Test
    fun builds_simple_object_with_numbers_and_booleans() {
        val json = JsonPayload.build("a" to "x", "n" to 5, "t" to 12L, "ok" to true, "nil" to null)
        assertEquals("""{"a":"x","n":5,"t":12,"ok":true,"nil":null}""", json)
    }

    @Test
    fun escapes_quotes_and_backslashes() {
        val json = JsonPayload.build("name" to """Vitamin "C" \ 500""")
        assertEquals("""{"name":"Vitamin \"C\" \\ 500"}""", json)
    }

    @Test
    fun escapes_newlines_tabs_and_control_characters() {
        val json = JsonPayload.build("name" to "a\nb\tc\u0001")
        assertEquals("""{"name":"a\nb\tc\u0001"}""", json)
    }

    @Test
    fun keeps_unicode_untouched() {
        val json = JsonPayload.build("name" to "पैरासिटामोल 💊")
        assertEquals("""{"name":"पैरासिटामोल 💊"}""", json)
    }
}
