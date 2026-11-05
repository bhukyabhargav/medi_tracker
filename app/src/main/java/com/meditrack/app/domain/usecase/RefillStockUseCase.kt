package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.MedicineRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RefillStockUseCase @Inject constructor(
    private val medicineRepository: MedicineRepository
) {
    suspend operator fun invoke(
        medicineId: String,
        additionalPills: Int,
        newStrip: Boolean = false,
        newBaselineEmptyCells: Int = 0,
        newBaselinePhotoPath: String? = null,
        now: Long = System.currentTimeMillis()
    ): Result<Medicine> {
        if (additionalPills <= 0) {
            return Result.failure(IllegalArgumentException("Additional pills must be greater than zero."))
        }
        val medicine = medicineRepository.getById(medicineId)
            ?: return Result.failure(IllegalArgumentException("Medicine not found: $medicineId"))

        val newStock = medicine.currentStock + additionalPills
        val updated = if (newStrip) {
            medicine.copy(
                currentStock = newStock,
                baselineEmptyCells = newBaselineEmptyCells,
                baselinePhotoPath = newBaselinePhotoPath,
                lastRefillAt = now,
                lowStockNotified = false,
                updatedAt = now
            )
        } else {
            medicine.copy(
                currentStock = newStock,
                lowStockNotified = false,
                updatedAt = now
            )
        }
        medicineRepository.update(updated)
        return Result.success(updated)
    }
}
