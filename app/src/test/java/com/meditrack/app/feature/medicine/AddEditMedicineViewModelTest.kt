package com.meditrack.app.feature.medicine

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditMedicineViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val saveMedicineUseCase: SaveMedicineUseCase = mockk(relaxed = true)
    private val userPreferences: com.meditrack.app.data.prefs.UserPreferences = mockk(relaxed = true)

    private lateinit var viewModel: AddEditMedicineViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = AddEditMedicineViewModel(medicineRepository, saveMedicineUseCase, userPreferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun validation_failure_sets_error_and_stays_on_form() {
        viewModel.onNameChange("")
        assertFalse(viewModel.validateForm())
        assertEquals(AddEditStep.FORM, viewModel.uiState.value.currentStep)
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun onContinueToPhoto_advances_to_photo_step_when_valid() {
        viewModel.onNameChange("Ibuprofen")
        viewModel.onDosageChange("400mg")
        viewModel.onPillsPerDoseTextChange("1")
        viewModel.onInitialStockTextChange("20")

        viewModel.onContinueToPhoto()

        assertEquals(AddEditStep.ENROLLMENT_PHOTO, viewModel.uiState.value.currentStep)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun onPhotoCaptured_records_photo_path() {
        viewModel.onPhotoCaptured("/app/storage/baseline/photo_123.jpg")
        assertEquals("/app/storage/baseline/photo_123.jpg", viewModel.uiState.value.baselinePhotoPath)
    }

    @Test
    fun onSkipPhoto_clears_photo_path_and_saves() = runTest {
        viewModel.onNameChange("Metformin")
        viewModel.onDosageChange("500mg")
        viewModel.onPhotoCaptured("/some/old/path.jpg")

        coEvery { saveMedicineUseCase.saveNewMedicine(any(), any(), any()) } returns listOf(
            DoseEvent(id = "d1", medicineId = "m1", scheduledAt = 1000L, windowEndsAt = 2000L, status = DoseStatus.PENDING)
        )

        viewModel.onSkipPhoto()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.baselinePhotoPath)
        assertEquals(AddEditStep.SUCCESS, viewModel.uiState.value.currentStep)
        assertEquals(1, viewModel.uiState.value.savedDoses.size)
    }

    @Test
    fun loadMedicine_populates_fields_for_editing() = runTest {
        val existingMed = Medicine(
            id = "med_edit_1",
            name = "Lisinopril",
            dosageText = "10mg",
            pillsPerDose = 2,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(7, 30)),
            startDate = 1700000000000L,
            endDate = 1705000000000L,
            foodRelation = FoodRelation.BEFORE,
            instructions = "Drink plenty of fluids",
            initialStock = 60,
            currentStock = 45,
            baselinePhotoPath = "/storage/photo_lisinopril.jpg"
        )

        coEvery { medicineRepository.getById("med_edit_1") } returns existingMed

        viewModel.loadMedicine("med_edit_1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isEditing)
        assertEquals("med_edit_1", state.editingMedicineId)
        assertEquals("Lisinopril", state.name)
        assertEquals("10mg", state.dosageText)
        assertEquals(2, state.pillsPerDose)
        assertEquals(FoodRelation.BEFORE, state.foodRelation)
        assertEquals("/storage/photo_lisinopril.jpg", state.baselinePhotoPath)
    }

    @Test
    fun saveDirectlyIfEditing_calls_update_and_transitions_to_success() = runTest {
        val existingMed = Medicine(
            id = "med_edit_2",
            name = "Atorvastatin",
            dosageText = "20mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(21, 0)),
            startDate = 1700000000000L,
            initialStock = 30,
            currentStock = 30
        )
        coEvery { medicineRepository.getById("med_edit_2") } returns existingMed
        coEvery { saveMedicineUseCase.updateMedicine(any(), any(), any()) } returns emptyList()

        viewModel.loadMedicine("med_edit_2")
        advanceUntilIdle()

        viewModel.onDosageChange("40mg")
        viewModel.saveDirectlyIfEditing()
        advanceUntilIdle()

        coVerify { saveMedicineUseCase.updateMedicine(match { it.dosageText == "40mg" }, any(), any()) }
        assertEquals(AddEditStep.SUCCESS, viewModel.uiState.value.currentStep)
    }

    @Test
    fun deleteMedicine_invokes_saveMedicineUseCase_and_calls_callback() = runTest {
        val existingMed = Medicine(
            id = "med_del_1",
            name = "Aspirin",
            dosageText = "81mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = 1000L,
            initialStock = 10,
            currentStock = 10
        )
        coEvery { medicineRepository.getById("med_del_1") } returns existingMed
        coEvery { saveMedicineUseCase.deleteMedicine("med_del_1") } returns Unit

        viewModel.loadMedicine("med_del_1")
        advanceUntilIdle()

        var deletedCalled = false
        viewModel.deleteMedicine { deletedCalled = true }
        advanceUntilIdle()

        coVerify { saveMedicineUseCase.deleteMedicine("med_del_1") }
        assertTrue(deletedCalled)
    }
}
