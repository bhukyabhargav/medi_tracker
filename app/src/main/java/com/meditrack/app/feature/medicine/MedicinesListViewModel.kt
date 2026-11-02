package com.meditrack.app.feature.medicine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.RefillStockUseCase
import com.meditrack.app.domain.usecase.SaveMedicineUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MedicinesListViewModel @Inject constructor(
    medicineRepository: MedicineRepository,
    private val saveMedicineUseCase: SaveMedicineUseCase,
    private val refillStockUseCase: RefillStockUseCase
) : ViewModel() {

    val medicines: StateFlow<List<Medicine>> = medicineRepository
        .observeActive()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteMedicine(medicineId: String) {
        viewModelScope.launch {
            saveMedicineUseCase.deleteMedicine(medicineId)
        }
    }

    fun refillMedicine(medicineId: String, additionalPills: Int) {
        viewModelScope.launch {
            refillStockUseCase(medicineId, additionalPills)
        }
    }
}
