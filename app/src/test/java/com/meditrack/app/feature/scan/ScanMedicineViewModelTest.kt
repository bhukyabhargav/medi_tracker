package com.meditrack.app.feature.scan

import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class ScanMedicineViewModelTest {

    private lateinit var viewModel: ScanMedicineViewModel

    @Before
    fun setUp() {
        viewModel = ScanMedicineViewModel()
    }

    @Test
    fun initialState_hasNoUnsafeDefaults() {
        val state = viewModel.uiState.value
        assertTrue(state.isScanning)
        assertFalse(state.isProcessing)
        assertEquals("", state.initialStockText) // Never defaulted to 10
        assertNull(state.frequencyType) // Never defaulted silently
        assertFalse("Cannot proceed until fields are verified", state.isReadyToProceed)
    }

    @Test
    fun onTextExtracted_withMissingStock_blocksContinuationUntilStockEntered() {
        val ocrText = "Aspirin 100mg once daily after food"
        viewModel.onTextExtracted(ocrText)

        val state = viewModel.uiState.value
        assertEquals("Aspirin", state.name)
        assertEquals("100mg", state.dosageText)
        assertEquals(FrequencyType.DAILY, state.frequencyType)
        assertEquals("", state.initialStockText) // Missing stock not defaulted
        assertFalse("Cannot proceed without entering stock quantity", state.isReadyToProceed)

        // Enter valid stock
        viewModel.onStockChange("30")
        assertTrue("Can proceed after valid stock is entered", viewModel.uiState.value.isReadyToProceed)
    }

    @Test
    fun onTextExtracted_withMissingFrequency_blocksContinuationUntilSelected() {
        val ocrText = "Metformin 500mg Qty: 20"
        viewModel.onTextExtracted(ocrText)

        val state = viewModel.uiState.value
        assertEquals("Metformin", state.name)
        assertEquals("500mg", state.dosageText)
        assertEquals("20", state.initialStockText)
        assertNull("Missing frequency must be null", state.frequencyType)
        assertFalse("Cannot proceed without frequency selection", state.isReadyToProceed)

        // Select frequency and time
        viewModel.onFrequencyChange(FrequencyType.DAILY)
        viewModel.addTimeOfDay(LocalTime.of(8, 0))
        assertTrue("Can proceed after frequency and time are chosen", viewModel.uiState.value.isReadyToProceed)
    }

    @Test
    fun readinessValidation_rejectsZeroOrNegativeStock() {
        viewModel.onNameChange("Paracetamol")
        viewModel.onDosageChange("500mg")
        viewModel.onFrequencyChange(FrequencyType.DAILY)
        viewModel.addTimeOfDay(LocalTime.of(8, 0))

        viewModel.onStockChange("0")
        assertFalse("Stock of 0 must be rejected", viewModel.uiState.value.isReadyToProceed)

        viewModel.onStockChange("-5")
        assertFalse("Negative stock must be rejected", viewModel.uiState.value.isReadyToProceed)

        viewModel.onStockChange("15")
        assertTrue("Positive stock is accepted", viewModel.uiState.value.isReadyToProceed)
    }

    @Test
    fun rescan_resetsScanningState() {
        viewModel.onTextExtracted("Amoxicillin 250mg once daily Qty: 10")
        assertFalse(viewModel.uiState.value.isScanning)

        viewModel.rescan()
        assertTrue(viewModel.uiState.value.isScanning)
        assertNull(viewModel.uiState.value.rawText)
    }

    @Test
    fun readinessValidation_specificDays_blockedUntilDaysChosen() {
        viewModel.onNameChange("Methotrexate")
        viewModel.onDosageChange("10mg")
        viewModel.onStockChange("10")
        viewModel.onFrequencyChange(FrequencyType.SPECIFIC_DAYS)
        viewModel.addTimeOfDay(LocalTime.of(8, 0))

        // No days selected yet
        assertEquals(emptyList<Int>(), viewModel.uiState.value.daysOfWeek)
        assertFalse("SPECIFIC_DAYS must be blocked when daysOfWeek is empty", viewModel.uiState.value.isReadyToProceed)

        // Select Monday (1)
        viewModel.onToggleDay(1)
        assertEquals(listOf(1), viewModel.uiState.value.daysOfWeek)
        assertTrue("SPECIFIC_DAYS can proceed when at least one day is selected", viewModel.uiState.value.isReadyToProceed)

        // Unselect Monday (1)
        viewModel.onToggleDay(1)
        assertEquals(emptyList<Int>(), viewModel.uiState.value.daysOfWeek)
        assertFalse("SPECIFIC_DAYS must be blocked again when daysOfWeek is emptied", viewModel.uiState.value.isReadyToProceed)
    }

    @Test
    fun removeTimeOfDay_allowsEmptyList_andBlocksDailyUntilTimeAdded() {
        viewModel.onNameChange("Dolo")
        viewModel.onDosageChange("650mg")
        viewModel.onStockChange("15")
        viewModel.onFrequencyChange(FrequencyType.DAILY)
        viewModel.addTimeOfDay(LocalTime.of(8, 0))
        assertTrue(viewModel.uiState.value.isReadyToProceed)

        // Remove the only time
        viewModel.removeTimeOfDay(LocalTime.of(8, 0))
        assertTrue("Times must be empty, not silently re-adding 08:00", viewModel.uiState.value.timesOfDay.isEmpty())
        assertFalse("DAILY with empty times must not proceed", viewModel.uiState.value.isReadyToProceed)
    }

    @Test
    fun onCaptureError_setsErrorMessageAndClearsProcessing() {
        viewModel.onCaptureError("Camera hardware unavailable")
        assertEquals("Camera hardware unavailable", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isProcessing)
    }
}
