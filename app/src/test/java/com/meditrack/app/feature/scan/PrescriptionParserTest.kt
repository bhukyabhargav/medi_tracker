package com.meditrack.app.feature.scan

import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class PrescriptionParserTest {

    @Test
    fun parse_paracetamol_500mg_twice_daily() {
        val input = "Paracetamol 500mg twice daily"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Paracetamol", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(1, parsed.pillsPerDose)
    }

    @Test
    fun parse_amoxicillin_250mg_tds() {
        val input = "Amoxicillin 250mg TDS"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Amoxicillin", parsed.medicineName)
        assertEquals("250mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
    }

    @Test
    fun parse_metformin_1000mg_1_0_1_after_meals() {
        val input = "Metformin 1000mg 1-0-1 after meals"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Metformin", parsed.medicineName)
        assertEquals("1000mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(FoodRelation.AFTER, parsed.foodRelation)
    }

    @Test
    fun parse_atorvastatin_20mg_od_at_bedtime() {
        val input = "Atorvastatin 20mg OD at bedtime"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Atorvastatin", parsed.medicineName)
        assertEquals("20mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(20, 0)), parsed.timesOfDay)
        assertTrue(parsed.instructions.contains("At bedtime"))
    }

    @Test
    fun parse_ibuprofen_400mg_every_8_hours() {
        val input = "Ibuprofen 400mg every 8 hours"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Ibuprofen", parsed.medicineName)
        assertEquals("400mg", parsed.dosageText)
        assertEquals(FrequencyType.HOURLY, parsed.frequencyType)
        assertEquals(8, parsed.intervalHours)
    }

    @Test
    fun parse_pantoprazole_40mg_before_breakfast_bd() {
        val input = "Pantoprazole 40mg before breakfast BD"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Pantoprazole", parsed.medicineName)
        assertEquals("40mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(FoodRelation.BEFORE, parsed.foodRelation)
    }

    @Test
    fun parse_cetirizine_10mg_once_a_day() {
        val input = "Cetirizine 10mg once a day"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Cetirizine", parsed.medicineName)
        assertEquals("10mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0)), parsed.timesOfDay)
    }

    @Test
    fun parse_azithromycin_500mg_daily_for_3_days() {
        val input = "Azithromycin 500mg daily for 3 days"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Azithromycin", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0)), parsed.timesOfDay)
        assertTrue(parsed.instructions.contains("for 3 days"))
    }

    @Test
    fun parse_ciprofloxacin_500mg_bid() {
        val input = "Ciprofloxacin 500mg BID"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Ciprofloxacin", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
    }

    @Test
    fun parse_tab_augmentin_625_mg_tid() {
        val input = "Tab. Augmentin 625 mg TID"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Augmentin", parsed.medicineName)
        assertEquals("625 mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
    }

    @Test
    fun parse_multiline_doctor_prescription() {
        val input = """
            Dr. Jane Doe, MD
            City Health Clinic
            Date: 12/10/2026
            Rx:
            Omeprazole 20mg
            Sig: 1 cap daily before breakfast
            Qty: 30
        """.trimIndent()
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Omeprazole", parsed.medicineName)
        assertEquals("20mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(FoodRelation.BEFORE, parsed.foodRelation)
    }

    @Test
    fun parse_dolo_650mg_1_1_1_after_food() {
        val input = "Dolo 650mg 1-1-1 after food"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Dolo", parsed.medicineName)
        assertEquals("650mg", parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)), parsed.timesOfDay)
        assertEquals(FoodRelation.AFTER, parsed.foodRelation)
    }

    @Test
    fun parse_take_2_tablets_per_dose() {
        val input = "Take 2 tablets Paracetamol 500mg BD"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Paracetamol", parsed.medicineName)
        assertEquals("500mg", parsed.dosageText)
        assertEquals(2, parsed.pillsPerDose)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
    }

    @Test
    fun parse_empty_or_blank_input_returns_safe_defaults() {
        val parsed = PrescriptionParser.parse("   ")
        assertNull(parsed.medicineName)
        assertNull(parsed.dosageText)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
        assertEquals(1, parsed.pillsPerDose)
    }

    @Test
    fun parse_vitamin_b6_tablet_does_not_parse_6_as_pill_count() {
        val input = "Vitamin B6 50mg daily 1 tablet"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Vitamin B6", parsed.medicineName)
        assertEquals("50mg", parsed.dosageText)
        assertEquals(1, parsed.pillsPerDose)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
    }

    @Test
    fun parse_b6_tablet_alone_defaults_to_1_pill_not_6() {
        val input = "B6 tablet 100mg OD"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("B6", parsed.medicineName)
        assertEquals("100mg", parsed.dosageText)
        assertEquals(1, parsed.pillsPerDose)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
    }

    @Test
    fun parse_take_2_tablets_vitamin_b6() {
        val input = "Take 2 tablets Vitamin B6 50mg daily"
        val parsed = PrescriptionParser.parse(input)

        assertEquals("Vitamin B6", parsed.medicineName)
        assertEquals("50mg", parsed.dosageText)
        assertEquals(2, parsed.pillsPerDose)
        assertEquals(FrequencyType.DAILY, parsed.frequencyType)
    }
}
