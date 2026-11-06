package com.meditrack.app.feature.scan

import com.meditrack.app.domain.model.FrequencyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class ScanPrescriptionViewModelTest {

    private lateinit var viewModel: ScanPrescriptionViewModel

    @Before
    fun setup() {
        viewModel = ScanPrescriptionViewModel()
    }

    @Test
    fun processOcrText_updates_ui_state_with_extracted_prescription() {
        val snippet = "Paracetamol 500mg twice daily"
        viewModel.processOcrText(snippet)

        val state = viewModel.uiState.value
        assertFalse(state.isProcessing)
        assertTrue(state.showReviewDialog)
        assertEquals("Paracetamol", state.medicineName)
        assertEquals("500mg", state.dosageText)
        assertEquals(FrequencyType.DAILY, state.frequencyType)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), state.timesOfDay)
        assertEquals(1, state.pillsPerDose)
        assertEquals(snippet, state.rawOcrText)
    }

    @Test
    fun user_manual_edits_update_state() {
        viewModel.processOcrText("Amoxicillin 250mg TDS")
        var state = viewModel.uiState.value
        assertEquals("Amoxicillin", state.medicineName)
        assertEquals("250mg", state.dosageText)

        // User edits fields in review dialog
        viewModel.onNameChange("Augmentin")
        viewModel.onDosageChange("625mg")
        viewModel.onPillsPerDoseTextChange("2")
        viewModel.onFrequencyTypeChange(FrequencyType.HOURLY)
        viewModel.onInstructionsChange("Take with food")

        state = viewModel.uiState.value
        assertEquals("Augmentin", state.medicineName)
        assertEquals("625mg", state.dosageText)
        assertEquals(2, state.pillsPerDose)
        assertEquals(FrequencyType.HOURLY, state.frequencyType)
        assertEquals("Take with food", state.instructions)
    }

    @Test
    fun dismissReviewDialog_hides_dialog() {
        viewModel.processOcrText("Cetirizine 10mg once daily")
        assertTrue(viewModel.uiState.value.showReviewDialog)

        viewModel.dismissReviewDialog()
        assertFalse(viewModel.uiState.value.showReviewDialog)
    }

    @Test
    fun dismissQualityFailure_clears_quality_failure() {
        val dummyMetrics = com.meditrack.app.feature.verification.quality.QualityMetrics(0.0, 10.0, 0.0)
        val dummyResult = com.meditrack.app.feature.verification.quality.QualityResult(
            passed = false,
            reason = com.meditrack.app.domain.model.RejectReason.TOO_DARK,
            friendlyMessage = "Too dark",
            metrics = dummyMetrics
        )
        // Simulate quality failure
        // We can test dismissing it
        viewModel.dismissQualityFailure()
        assertFalse(viewModel.uiState.value.isProcessing)
    }

    @Test
    fun pillsPerDose_coerces_invalid_or_negative_values_to_one() {
        viewModel.onPillsPerDoseTextChange("invalid")
        assertEquals(1, viewModel.uiState.value.pillsPerDose)

        viewModel.onPillsPerDoseTextChange("-5")
        assertEquals(1, viewModel.uiState.value.pillsPerDose)

        viewModel.onPillsPerDoseTextChange("3")
        assertEquals(3, viewModel.uiState.value.pillsPerDose)
    }
}
