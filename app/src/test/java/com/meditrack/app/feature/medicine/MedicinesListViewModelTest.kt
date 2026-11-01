package com.meditrack.app.feature.medicine

import app.cash.turbine.test
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class MedicinesListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val saveMedicineUseCase: SaveMedicineUseCase = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun medicines_flow_emits_active_medicines() = runTest {
        val sampleMed = Medicine(
            id = "med_1",
            name = "Aspirin",
            dosageText = "100mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = 1700000000000L,
            foodRelation = FoodRelation.AFTER,
            initialStock = 30,
            currentStock = 30
        )

        every { medicineRepository.observeActive() } returns flowOf(listOf(sampleMed))

        val viewModel = MedicinesListViewModel(medicineRepository, saveMedicineUseCase)

        viewModel.medicines.test {
            // initial value or first emission
            val list = awaitItem()
            if (list.isEmpty()) {
                val nextList = awaitItem()
                assertEquals(1, nextList.size)
                assertEquals("Aspirin", nextList[0].name)
            } else {
                assertEquals(1, list.size)
                assertEquals("Aspirin", list[0].name)
            }
        }
    }

    @Test
    fun deleteMedicine_invokes_saveMedicineUseCase_delete() = runTest {
        every { medicineRepository.observeActive() } returns flowOf(emptyList())
        coEvery { saveMedicineUseCase.deleteMedicine(any()) } returns Unit

        val viewModel = MedicinesListViewModel(medicineRepository, saveMedicineUseCase)
        viewModel.deleteMedicine("med_to_delete")
        advanceUntilIdle()

        coVerify { saveMedicineUseCase.deleteMedicine("med_to_delete") }
    }
}
