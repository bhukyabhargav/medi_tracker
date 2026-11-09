package com.meditrack.app.feature.scan

import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class PrescriptionParserTest {

    @Test
    fun testCase1_paracetamolTwiceDailyAfterFood() {
        val snippet = "Paracetamol 500mg twice daily after food Qty: 20"
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Paracetamol", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(FoodRelation.AFTER, parsed.foodRelation)
        assertEquals(20, parsed.initialStock)
    }

    @Test
    fun testCase2_metforminIndianMatrixNotation_1_0_1() {
        val snippet = """
            Rx
            Tab. Metformin 500 mg
            1-0-1 after meals
            Strip of 10
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Metformin", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(1, parsed.pillsPerDose)
        assertEquals(FoodRelation.AFTER, parsed.foodRelation)
        assertEquals(10, parsed.initialStock)
    }

    @Test
    fun testCase2b_indianMatrixNotation_2_0_2() {
        val snippet = """
            Rx
            Tab. Metformin 500mg
            2-0-2 after food
            Qty: 40
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Metformin", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(2, parsed.pillsPerDose)
        assertEquals(40, parsed.initialStock)
    }

    @Test
    fun testCase3_amoxicillinTidBeforeFood() {
        val snippet = """
            Amoxicillin 250mg
            Sig: 1 cap TID before meals
            Total: 15 capsules
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Amoxicillin", parsed.medicineName)
        assertEquals("250mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(FoodRelation.BEFORE, parsed.foodRelation)
        assertEquals(15, parsed.initialStock)
    }

    @Test
    fun testCase4_atorvastatinOnceDailyBedtime_OD() {
        val snippet = """
            Tab Atorvastatin 10mg
            OD at bedtime
            Count: 30
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Atorvastatin", parsed.medicineName)
        assertEquals("10mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(21, 0)), parsed.timesOfDay)
        assertEquals(30, parsed.initialStock)
    }

    @Test
    fun testCase5_pantoprazoleEmptyStomachMorning_1_0_0() {
        val snippet = """
            Pantoprazole 40mg
            1-0-0 empty stomach
            Qty: 10 tablets
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Pantoprazole", parsed.medicineName)
        assertEquals("40mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0)), parsed.timesOfDay)
        assertEquals(FoodRelation.BEFORE, parsed.foodRelation)
        assertEquals(10, parsed.initialStock)
    }

    @Test
    fun testCase6_ibuprofenIntervalEvery8Hours() {
        val snippet = """
            Ibuprofen 400mg
            Take 1 tablet every 8 hours with food
            Quantity: 24
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Ibuprofen", parsed.medicineName)
        assertEquals("400mg", parsed.dosageText)
        assertEquals(FrequencyType.HOURLY, parsed.frequencyType)
        assertEquals(8, parsed.intervalHours)
        assertEquals(24, parsed.initialStock)
        assertEquals("Take with food", parsed.instructions)
    }

    @Test
    fun testCase7_cetirizineNightOnly_0_0_1() {
        val snippet = """
            Cetirizine 10mg
            0-0-1 at night
            Strip of 10 tablets
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Cetirizine", parsed.medicineName)
        assertEquals("10mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(10, parsed.initialStock)
    }

    @Test
    fun testCase8_azithromycinOnceDailyFiveDays() {
        val snippet = """
            Azithromycin 500mg
            Once daily 1 hour before food
            Total 5 tabs
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Azithromycin", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0)), parsed.timesOfDay)
        assertEquals(FoodRelation.BEFORE, parsed.foodRelation)
        assertEquals(5, parsed.initialStock)
    }

    @Test
    fun testCase9_fourTimesDaily_QID() {
        val snippet = """
            Amoxicillin 500mg
            QID after food
            Qty: 28
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Amoxicillin", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(4, parsed.timesOfDay.size)
        assertEquals(LocalTime.of(8, 0), parsed.timesOfDay[0])
        assertEquals(LocalTime.of(12, 0), parsed.timesOfDay[1])
        assertEquals(LocalTime.of(16, 0), parsed.timesOfDay[2])
        assertEquals(LocalTime.of(20, 0), parsed.timesOfDay[3])
        assertEquals(FoodRelation.AFTER, parsed.foodRelation)
        assertEquals(28, parsed.initialStock)
    }

    @Test
    fun testCase9b_fourTimesDaily_matrix_1_1_1_1() {
        val snippet = """
            Amoxicillin 500mg
            1-1-1-1 after food
            Qty: 28
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(4, parsed.timesOfDay.size)
        assertEquals(LocalTime.of(8, 0), parsed.timesOfDay[0])
        assertEquals(LocalTime.of(12, 0), parsed.timesOfDay[1])
        assertEquals(LocalTime.of(16, 0), parsed.timesOfDay[2])
        assertEquals(LocalTime.of(20, 0), parsed.timesOfDay[3])
        assertEquals(1, parsed.pillsPerDose)
    }

    @Test
    fun testCase10_telmisartanMorningWithWater() {
        val snippet = """
            Telmisartan 40mg
            OD in the morning with water
            Pack of 30 tablets
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Telmisartan", parsed.medicineName)
        assertEquals("40mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0)), parsed.timesOfDay)
        assertEquals(30, parsed.initialStock)
        assertEquals("With plenty of water", parsed.instructions)
    }

    @Test
    fun testCase11_threeTimesDaily_1_1_1() {
        val snippet = """
            Paracetamol 650mg
            1-1-1 after meals
            15 tablets
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Paracetamol", parsed.medicineName)
        assertEquals("650mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(FoodRelation.AFTER, parsed.foodRelation)
        assertEquals(15, parsed.initialStock)
    }

    @Test
    fun testCase12_intervalQ6h() {
        val snippet = """
            Paracetamol 500mg
            q6h for fever
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Paracetamol", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.HOURLY, parsed.frequencyType)
        assertEquals(6, parsed.intervalHours)
    }

    @Test
    fun testCase13_prnAsNeeded_neverSilentlyAssumedDaily() {
        val snippet = "Paracetamol 500mg PRN for pain Qty: 10"
        val parsed = PrescriptionParser.parse(snippet)

        assertTrue("PRN must be flagged as as-needed", parsed.isAsNeeded)
        assertNull("PRN must never silently assign a daily frequency", parsed.frequencyType)
        assertTrue("PRN must require manual review", parsed.requiresManualReview)
    }

    @Test
    fun testCase14_sosEmergency_requiresManualReview() {
        val snippet = "Aspirin 300mg SOS for severe chest pain"
        val parsed = PrescriptionParser.parse(snippet)

        assertTrue(parsed.isAsNeeded)
        assertNull(parsed.frequencyType)
        assertTrue(parsed.requiresManualReview)
    }

    @Test
    fun testCase15_onceWeeklySchedule() {
        val snippet = "Methotrexate 10mg once weekly on Monday Qty: 4"
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals(FrequencyType.SPECIFIC_DAYS, parsed.frequencyType)
        assertNotNull(parsed.daysOfWeek)
    }

    @Test
    fun testCase16_halfTabletInstruction() {
        val snippet = "Amlodipine 5mg Take 1/2 tablet once daily Qty: 30"
        val parsed = PrescriptionParser.parse(snippet)

        assertTrue(parsed.instructions.contains("1/2 tablet"))
    }

    @Test
    fun testCase17_mcgUnitPreserved() {
        val snippet = "Levothyroxine 50 mcg once daily before breakfast Qty: 100"
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("50mcg", parsed.dosageText)
    }

    @Test
    fun testCase18_missingFrequencyAndStock_neverSilentlyDefaulted() {
        val snippet = "Paracetamol 500mg"
        val parsed = PrescriptionParser.parse(snippet)

        assertEquals("Paracetamol", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertNull("Missing frequency must be null, never defaulted to daily", parsed.frequencyType)
        assertNull("Missing stock must be null, never defaulted to 10", parsed.initialStock)
        assertTrue(parsed.requiresManualReview)
    }

    @Test
    fun partB1_totalPriceNotStock_qty28Works_twiceDailyNotStock() {
        val snippetBill = """
            Dolo 650mg once daily
            Total: 245.00
            Amount: Rs 245
        """.trimIndent()
        val parsedBill = PrescriptionParser.parse(snippetBill)
        assertNull("Total bill amount must not become stock", parsedBill.initialStock)

        val snippetDose = """
            Amoxicillin 500mg
            2 tablets twice daily
            Qty: 28
        """.trimIndent()
        val parsedDose = PrescriptionParser.parse(snippetDose)
        assertEquals("Qty: 28 must be parsed as stock, not 2 tablets", 28, parsedDose.initialStock)
    }

    @Test
    fun partB2_medicineNameExtraction_doloOmezAndHeadersSkipped() {
        val snippetDolo = "Tablet Dolo 650 1-0-1 Qty: 10"
        val parsedDolo = PrescriptionParser.parse(snippetDolo)
        assertEquals("Dolo", parsedDolo.medicineName)

        val snippetOmez = "Capsule Omez 20 once daily Qty: 14"
        val parsedOmez = PrescriptionParser.parse(snippetOmez)
        assertEquals("Omez", parsedOmez.medicineName)

        val snippetDr = """
            Dr. Sharma Clinic
            Patient: John Doe Date: 2026-11-01
            Paracetamol 500 mg
            OD after food
            Count: 10
        """.trimIndent()
        val parsedDr = PrescriptionParser.parse(snippetDr)
        assertEquals("Paracetamol", parsedDr.medicineName)
        assertFalse(parsedDr.medicineName?.contains("Dr", ignoreCase = true) == true)
        assertFalse(parsedDr.medicineName?.contains("Clinic", ignoreCase = true) == true)
    }

    @Test
    fun partB3_percentageDosageAndMultiStrengths() {
        val snippet1Percent = "Diclofenac Gel 1% apply twice daily Qty: 1"
        val parsed1 = PrescriptionParser.parse(snippet1Percent)
        assertEquals("1%", parsed1.dosageText)

        val snippetPointFive = "Hydrocortisone cream 0.5 % OD Qty: 1"
        val parsedPointFive = PrescriptionParser.parse(snippetPointFive)
        assertEquals("0.5%", parsedPointFive.dosageText)

        val snippetMultiStrength = "Amoxicillin 500mg and 125mg Clavulanic Acid Qty: 10"
        val parsedMulti = PrescriptionParser.parse(snippetMultiStrength)
        assertEquals("500mg", parsedMulti.dosageText)
        assertTrue("Multiple strengths must add review reason", parsedMulti.reviewReasons.any { it.contains("Multiple strengths detected") })
    }

    @Test
    fun partB4_monthsDoesNotAddBedtimeInstruction() {
        val snippet = "Amlodipine 5mg once daily for 3 months Qty: 90"
        val parsed = PrescriptionParser.parse(snippet)
        assertFalse("for 3 months must not trigger bedtime instruction", parsed.instructions.contains("bedtime", ignoreCase = true))
    }

    @Test
    fun partA3_matrixUnequalCounts_andRejectDates() {
        // Unequal counts 1-0-2
        val snippetUnequal = "Metformin 500mg 1-0-2 after food Qty: 30"
        val parsedUnequal = PrescriptionParser.parse(snippetUnequal)
        assertEquals(FrequencyType.DAILY, parsedUnequal.frequencyType)
        assertEquals("Unequal matrix non-zero counts must leave pillsPerDose at default 1", 1, parsedUnequal.pillsPerDose)
        assertTrue(parsedUnequal.requiresManualReview)
        assertTrue(parsedUnequal.reviewReasons.any { it.contains("Different pill counts per time slot") })

        // Date 5/6/7 must not be treated as a matrix
        val snippetDate = "Paracetamol 500mg Date: 5/6/7 Qty: 10"
        val parsedDate = PrescriptionParser.parse(snippetDate)
        assertNull("Date format with slashes must not be treated as daily matrix", parsedDate.frequencyType)
    }

    @Test
    fun partA1_weeklySchedulesWithNamedDayAndWithout_andAlternateDays() {
        val snippetSunday = "Methotrexate 10mg once weekly on Sunday Qty: 4"
        val parsedSunday = PrescriptionParser.parse(snippetSunday)
        assertEquals(FrequencyType.SPECIFIC_DAYS, parsedSunday.frequencyType)
        assertEquals(listOf(7), parsedSunday.daysOfWeek)

        val snippetNoDay = "Methotrexate 10mg weekly Qty: 4"
        val parsedNoDay = PrescriptionParser.parse(snippetNoDay)
        assertEquals(FrequencyType.SPECIFIC_DAYS, parsedNoDay.frequencyType)
        assertNull("Weekly with no day named must have daysOfWeek = null", parsedNoDay.daysOfWeek)
        assertTrue(parsedNoDay.requiresManualReview)
        assertTrue(parsedNoDay.reviewReasons.any { it.contains("Weekly medicine: choose the day of the week") })

        val snippetAlternate = "Prednisolone 20mg alternate days Qty: 10"
        val parsedAlt = PrescriptionParser.parse(snippetAlternate)
        assertEquals(FrequencyType.SPECIFIC_DAYS, parsedAlt.frequencyType)
        assertNull("Alternate days must NOT map to Mon/Wed/Fri; daysOfWeek must be null", parsedAlt.daysOfWeek)
        assertTrue(parsedAlt.requiresManualReview)
        assertTrue(parsedAlt.reviewReasons.any { it.contains("Alternate-day dosing cannot be represented exactly") })
    }

    @Test
    fun partA2_fractionalDose_requiresManualReview() {
        val snippet = "Take 1/2 tablet once daily Atenolol 25mg Qty: 30"
        val parsed = PrescriptionParser.parse(snippet)
        assertTrue(parsed.requiresManualReview)
        assertTrue(parsed.reviewReasons.any { it.contains("Fractional dose detected") })
        assertTrue(parsed.instructions.contains("1/2 tablet"))
    }

    @Test
    fun partB6_multipleMedicinesDetected() {
        val snippet = """
            Rx
            1. Paracetamol 500mg 1-0-1 Qty: 10
            2. Cetirizine 10mg 0-0-1 Qty: 10
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)
        assertTrue(parsed.requiresManualReview)
        assertTrue(parsed.reviewReasons.any { it.contains("Multiple medicines detected") })
    }

    @Test
    fun partB7_ambiguousIntervalRangeDetected() {
        val snippet = "Paracetamol 500mg every 6 to 8 hours for fever Qty: 20"
        val parsed = PrescriptionParser.parse(snippet)
        assertEquals(FrequencyType.HOURLY, parsed.frequencyType)
        assertNull("Ambiguous range must not silently pick a value", parsed.intervalHours)
        assertTrue(parsed.requiresManualReview)
        assertTrue(parsed.reviewReasons.any { it.contains("Ambiguous interval range detected") })
    }

    @Test
    fun partB5_courseDurationExtraction() {
        val snippetDays = "Amoxicillin 500mg TID for 5 days Qty: 15"
        val parsedDays = PrescriptionParser.parse(snippetDays)
        assertEquals(5, parsedDays.durationDays)

        val snippetWeeks = "Omeprazole 20mg OD x 2 weeks Qty: 14"
        val parsedWeeks = PrescriptionParser.parse(snippetWeeks)
        assertEquals(14, parsedWeeks.durationDays)
    }

    @Test
    fun d4_emptyAndWhitespaceInputs_neverThrowsAndRequiresManualReview() {
        val emptyResult = PrescriptionParser.parse("")
        assertTrue(emptyResult.requiresManualReview)
        assertNull(emptyResult.medicineName)
        assertNull(emptyResult.dosageText)
        assertNull(emptyResult.frequencyType)

        val whitespaceResult = PrescriptionParser.parse("   \n\t  \r\n   ")
        assertTrue(whitespaceResult.requiresManualReview)
        assertNull(whitespaceResult.medicineName)
        assertNull(whitespaceResult.dosageText)
        assertNull(whitespaceResult.frequencyType)
    }

    @Test
    fun d4_fractionalDoses_quarterAndHalf_safelyExtractedAndFlagged() {
        // Quarter tablet
        val snippetQuarter = "Take 1/4 tablet daily Atenolol 25mg Qty: 30"
        val parsedQuarter = PrescriptionParser.parse(snippetQuarter)
        assertTrue(parsedQuarter.requiresManualReview)
        assertTrue(parsedQuarter.reviewReasons.any { it.contains("1/4 tablet") })
        assertTrue(parsedQuarter.instructions.contains("1/4 tablet"))

        // Word 'quarter tab'
        val snippetQuarterWord = "Atenolol 25mg quarter tablet once daily Qty: 30"
        val parsedQuarterWord = PrescriptionParser.parse(snippetQuarterWord)
        assertTrue(parsedQuarterWord.requiresManualReview)
        assertTrue(parsedQuarterWord.reviewReasons.any { it.contains("1/4 tablet") })
        assertTrue(parsedQuarterWord.instructions.contains("1/4 tablet"))

        // Half tablet
        val snippetHalf = "Metformin 500mg half tab twice daily after food Qty: 20"
        val parsedHalf = PrescriptionParser.parse(snippetHalf)
        assertTrue(parsedHalf.requiresManualReview)
        assertTrue(parsedHalf.reviewReasons.any { it.contains("1/2 tablet") })
        assertTrue(parsedHalf.instructions.contains("1/2 tablet"))
    }

    @Test
    fun d4_multiMedicinePrescription_requiresManualReviewAndWarnsUser() {
        val snippet = """
            Rx
            1. Metformin 500mg 1-0-1 Qty: 30
            2. Atorvastatin 10mg OD bedtime Qty: 30
        """.trimIndent()
        val parsed = PrescriptionParser.parse(snippet)
        assertTrue(parsed.requiresManualReview)
        assertTrue(parsed.reviewReasons.any { it.contains("Multiple medicines detected") })
    }

    @Test
    fun d4_prnAndSos_flagsAsNeededWithoutSilentDailySchedule() {
        val snippetPrn = "Paracetamol 500mg PRN for headache Qty: 10"
        val parsedPrn = PrescriptionParser.parse(snippetPrn)
        assertTrue(parsedPrn.isAsNeeded)
        assertNull(parsedPrn.frequencyType)
        assertTrue(parsedPrn.requiresManualReview)
        assertTrue(parsedPrn.instructions.contains("PRN/SOS"))

        val snippetSos = "Aspirin 300mg SOS severe pain Qty: 5"
        val parsedSos = PrescriptionParser.parse(snippetSos)
        assertTrue(parsedSos.isAsNeeded)
        assertNull(parsedSos.frequencyType)
        assertTrue(parsedSos.requiresManualReview)
        assertTrue(parsedSos.instructions.contains("PRN/SOS"))
    }
}
