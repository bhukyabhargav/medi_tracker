package com.meditrack.app.feature.verification.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatcherTest {

    @Test
    fun exactMatch_returnsTrue() {
        assertTrue(NameMatcher.matches("Paracetamol", "Paracetamol"))
        assertTrue(NameMatcher.matches("paracetamol", "PARACETAMOL"))
    }

    @Test
    fun containsMedicineName_returnsTrue() {
        assertTrue(NameMatcher.matches("Batch No 1234 Paracetamol 500mg Exp 2028", "Paracetamol"))
        assertTrue(NameMatcher.matches("Atorvastatin Tablets IP 10mg", "Atorvastatin"))
    }

    @Test
    fun minorTypoAboveThreshold_returnsTrue() {
        // "Paracetmol" (missing 'a') vs "Paracetamol" -> dist=1, len=11 -> sim ~0.91 >= 0.6
        assertTrue(NameMatcher.matches("Paracetmol", "Paracetamol"))
        // "Metformn" vs "Metformin" -> dist=1, len=9 -> sim ~0.89 >= 0.6
        assertTrue(NameMatcher.matches("Metformn", "Metformin"))
    }

    @Test
    fun completelyDifferentMedicine_returnsFalse() {
        assertFalse(NameMatcher.matches("Ibuprofen 400mg", "Metformin"))
        assertFalse(NameMatcher.matches("Aspirin", "Paracetamol"))
        assertFalse(NameMatcher.matches("Amoxicillin", "Lisinopril"))
    }

    @Test
    fun blankOrEmptyStrings_returnsFalse() {
        assertFalse(NameMatcher.matches("", "Paracetamol"))
        assertFalse(NameMatcher.matches("   ", "Paracetamol"))
        assertFalse(NameMatcher.matches("Paracetamol", ""))
        assertFalse(NameMatcher.matches("Paracetamol", "   "))
    }

    @Test
    fun multiWordMedicineMatch() {
        assertTrue(NameMatcher.matches("Contains Amoxicillin Clavulanate 625mg", "Amoxicillin Clavulanate"))
        // Minor typo in one of the words
        assertTrue(NameMatcher.matches("Amoxcillin Clavulanate", "Amoxicillin Clavulanate"))
    }
}
