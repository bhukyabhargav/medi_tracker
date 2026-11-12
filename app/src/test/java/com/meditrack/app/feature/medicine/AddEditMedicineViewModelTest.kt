package com.meditrack.app.feature.medicine

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.AcceptProposalUseCase
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
    private val userPreferences: UserPreferences = mockk(relaxed = true)

    private lateinit var viewModel: AddEditMedicineViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { userPreferences.currentUserId } returns flowOf("user_1")
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
        viewModel.onPillsPerDoseChange("1")
        viewModel.onInitialStockChange("20")

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
        viewModel.onInitialStockChange("20")
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
    fun prefillFromScan_populatesDaysOfWeekAndDurationCorrectly() {
        viewModel.prefillFromScan(
            name = "Amoxicillin",
            dosage = "500mg",
            pillsPerDose = "1",
            initialStock = "21",
            frequency = FrequencyType.SPECIFIC_DAYS.name,
            times = listOf("08:00", "20:00"),
            intervalHours = null,
            daysOfWeek = "1,3,5",
            durationDays = "7",
            foodRelation = FoodRelation.AFTER.name,
            instructions = "Take after food"
        )

        val state = viewModel.uiState.value
        assertEquals("Amoxicillin", state.name)
        assertEquals("500mg", state.dosageText)
        assertEquals("21", state.initialStockText)
        assertEquals(FrequencyType.SPECIFIC_DAYS, state.frequencyType)
        assertEquals(listOf(1, 3, 5), state.daysOfWeek)
        assertNotNull(state.endDate)
        assertTrue(state.endDate!! > state.startDate)
    }

    @Test
    fun prefillFromScan_prefillConsumed_preventsConfigurationChangeOverwritingUserEdits() {
        viewModel.prefillFromScan(
            name = "Amoxicillin",
            dosage = "500mg",
            pillsPerDose = "1",
            initialStock = "21",
            frequency = FrequencyType.DAILY.name,
            times = listOf("08:00"),
            intervalHours = null,
            daysOfWeek = null,
            durationDays = null,
            foodRelation = null,
            instructions = null
        )

        assertEquals("Amoxicillin", viewModel.uiState.value.name)

        // User edits the name
        viewModel.onNameChange("Amoxicillin Clavulanate")
        assertEquals("Amoxicillin Clavulanate", viewModel.uiState.value.name)

        // Configuration change triggers LaunchedEffect / prefill again
        viewModel.prefillFromScan(
            name = "Amoxicillin",
            dosage = "500mg",
            pillsPerDose = "1",
            initialStock = "21",
            frequency = FrequencyType.DAILY.name,
            times = listOf("08:00"),
            intervalHours = null,
            daysOfWeek = null,
            durationDays = null,
            foodRelation = null,
            instructions = null
        )

        // User edit must be preserved!
        assertEquals("Amoxicillin Clavulanate", viewModel.uiState.value.name)
    }

    @Test
    fun defaultInitialStock_isEmptyString_andFailsValidationUntilEntered() {
        val newVm = AddEditMedicineViewModel(medicineRepository, saveMedicineUseCase, userPreferences)
        assertEquals("", newVm.uiState.value.initialStockText)

        newVm.onNameChange("Paracetamol")
        newVm.onDosageChange("500mg")
        assertFalse(newVm.validateForm())
        assertTrue(newVm.uiState.value.errorMessage?.contains("strip/pack", ignoreCase = true) == true)
    }

    @Test
    fun onPhotoCapturedWithLedger_storesBaselinePathAndEmptyCells() {
        viewModel.onPhotoCapturedWithLedger("file:///path/to/baseline.jpg", 2)
        assertEquals("file:///path/to/baseline.jpg", viewModel.uiState.value.baselinePhotoPath)
        assertEquals(2, viewModel.uiState.value.baselineEmptyCells)
    }

    @Test
    fun saveMedicine_whenNoUserSession_blocksSavingWithClearError() = runTest {
        every { userPreferences.currentUserId } returns flowOf("")
        val noUserVm = AddEditMedicineViewModel(medicineRepository, saveMedicineUseCase, userPreferences)
        noUserVm.onNameChange("Metformin")
        noUserVm.onDosageChange("500mg")
        noUserVm.onInitialStockChange("20")

        noUserVm.onSkipPhoto()
        advanceUntilIdle()

        assertEquals("No active user session. Please sign in to save medicines.", noUserVm.uiState.value.errorMessage)
        assertFalse(noUserVm.uiState.value.isLoading)
        assertEquals(AddEditStep.FORM, noUserVm.uiState.value.currentStep)
        coVerify(exactly = 0) { saveMedicineUseCase.saveNewMedicine(any(), any(), any()) }
    }

    @Test
    fun `saveMedicine with acceptProposalId marks proposal accepted and preserves doctor metadata`() = runTest(testDispatcher) {
        val acceptProposalUseCase: AcceptProposalUseCase = mockk(relaxed = true)
        coEvery { acceptProposalUseCase.canAccept("prop_123") } returns true
        coEvery { acceptProposalUseCase.markProposalAccepted(any(), any()) } returns true
        val vmWithProposal = AddEditMedicineViewModel(
            medicineRepository = medicineRepository,
            saveMedicineUseCase = saveMedicineUseCase,
            userPreferences = userPreferences,
            acceptProposalUseCase = acceptProposalUseCase
        )

        vmWithProposal.prefillFromScan(
            name = "Amoxicillin",
            dosage = "500mg",
            pillsPerDose = "2",
            initialStock = null,
            frequency = "DAILY",
            times = listOf("09:00"),
            intervalHours = null,
            daysOfWeek = null,
            durationDays = null,
            foodRelation = "AFTER",
            instructions = "Take after breakfast",
            acceptProposalId = "prop_123",
            prescribedByDoctorUid = "doc_999",
            prescribedByDoctorName = "Dr. House"
        )

        // Patient fills in their real stock
        vmWithProposal.onInitialStockChange("30")

        // Skipping the baseline photo is not allowed for a doctor's prescription
        vmWithProposal.onSkipPhoto()
        advanceUntilIdle()
        coVerify(exactly = 0) { saveMedicineUseCase.saveNewMedicine(any(), any(), any()) }

        // Patient enrolls the baseline strip photo and saves
        vmWithProposal.onPhotoCapturedWithLedger("/data/baseline/strip.jpg", 0)
        vmWithProposal.onConfirmPhotoAndSave()
        advanceUntilIdle()

        val slot = slot<Medicine>()
        coVerify { saveMedicineUseCase.saveNewMedicine(capture(slot), any(), any()) }
        assertEquals("Amoxicillin", slot.captured.name)
        assertEquals("500mg", slot.captured.dosageText)
        assertEquals(2, slot.captured.pillsPerDose)
        assertEquals(30, slot.captured.initialStock)
        assertEquals("doc_999", slot.captured.prescribedByDoctorUid)
        assertEquals("Dr. House", slot.captured.prescribedByDoctorName)

        coVerify { acceptProposalUseCase.markProposalAccepted("prop_123", slot.captured.id) }
        assertEquals(AddEditStep.SUCCESS, vmWithProposal.uiState.value.currentStep)
    }

    @Test
    fun `saveMedicine with a proposal that can no longer be accepted does not create a medicine`() = runTest(testDispatcher) {
        val acceptProposalUseCase: AcceptProposalUseCase = mockk(relaxed = true)
        coEvery { acceptProposalUseCase.canAccept("prop_declined") } returns false
        val vm = AddEditMedicineViewModel(
            medicineRepository = medicineRepository,
            saveMedicineUseCase = saveMedicineUseCase,
            userPreferences = userPreferences,
            acceptProposalUseCase = acceptProposalUseCase
        )

        vm.prefillFromScan(
            name = "Amoxicillin",
            dosage = "500mg",
            pillsPerDose = "1",
            initialStock = null,
            frequency = "DAILY",
            times = listOf("09:00"),
            intervalHours = null,
            foodRelation = "AFTER",
            instructions = "",
            acceptProposalId = "prop_declined"
        )
        vm.onInitialStockChange("30")
        vm.onPhotoCapturedWithLedger("/data/baseline/strip.jpg", 0)
        vm.onConfirmPhotoAndSave()
        advanceUntilIdle()

        coVerify(exactly = 0) { saveMedicineUseCase.saveNewMedicine(any(), any(), any()) }
        coVerify(exactly = 0) { acceptProposalUseCase.markProposalAccepted(any(), any()) }
        assertEquals(AddEditStep.FORM, vm.uiState.value.currentStep)
    }
}
