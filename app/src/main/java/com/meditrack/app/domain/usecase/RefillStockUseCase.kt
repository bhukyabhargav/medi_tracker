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
        newBaselineEmptyCells: Int = 0,
        newBaselinePhotoPath: String? = null,
        startedNewStrip: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): Result<Medicine> {
        if (additionalPills <= 0) {
            return Result.failure(IllegalArgumentException("Additional pills must be greater than zero."))
        }
        val medicine = medicineRepository.getById(medicineId)
            ?: return Result.failure(IllegalArgumentException("Medicine not found: $medicineId"))

        val newStock = medicine.currentStock + additionalPills
        val updated = if (startedNewStrip) {
            // README 3.6: a NEW strip resets the pill-count ledger (new baseline + lastRefillAt marker).
            medicine.copy(
                currentStock = newStock,
                baselineEmptyCells = newBaselineEmptyCells,
                baselinePhotoPath = newBaselinePhotoPath ?: medicine.baselinePhotoPath,
                lastRefillAt = now,
                lowStockNotified = false,
                updatedAt = now
            )
        } else {
            // Plain top-up: only the stock changes; the strip ledger must stay intact.
            medicine.copy(
                currentStock = newStock,
                lowStockNotified = false, // Resets low stock flag so future drops trigger alert
                updatedAt = now
            )
        }
        medicineRepository.update(updated)
        return Result.success(updated)
    }
}
