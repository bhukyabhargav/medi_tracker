package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveMedicineUseCase @Inject constructor(
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository
) {

    /**
     * Inserts a new medicine, sets currentStock = initialStock, and generates
     * upcoming DoseEvents for a rolling 7-day window.
     */
    suspend fun saveNewMedicine(
        medicine: Medicine,
        rollingDaysAhead: Long = 7L,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DoseEvent> {
        val medicineToSave = medicine.copy(
            currentStock = medicine.initialStock
        )
        medicineRepository.insert(medicineToSave)

        val fromMillis = medicineToSave.startDate
        val toMillis = medicineToSave.startDate + (rollingDaysAhead * 24 * 60 * 60 * 1000L)
        val boundedToMillis = if (medicineToSave.endDate != null) {
            minOf(toMillis, medicineToSave.endDate)
        } else {
            toMillis
        }

        val doses = DoseScheduler.generateDoses(
            medicine = medicineToSave,
            fromEpochMillis = fromMillis,
            toEpochMillis = boundedToMillis,
            zoneId = zoneId
        )

        if (doses.isNotEmpty()) {
            doseRepository.insertAll(doses)
        }

        return doses
    }

    /**
     * Updates an existing medicine, deletes future PENDING doses only (preserving past history),
     * and regenerates future doses for the rolling window.
     */
    suspend fun updateMedicine(
        updatedMedicine: Medicine,
        rollingDaysAhead: Long = 7L,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DoseEvent> {
        medicineRepository.update(updatedMedicine)

        val now = System.currentTimeMillis()
        // Removes future PENDING doses; keeps all past doses and verified history
        doseRepository.deleteFuturePendingDoses(updatedMedicine.id, now)

        if (updatedMedicine.stoppedByDoctor) {
            return emptyList()
        }

        val fromMillis = maxOf(now, updatedMedicine.startDate)
        val toMillis = now + (rollingDaysAhead * 24 * 60 * 60 * 1000L)
        val boundedToMillis = if (updatedMedicine.endDate != null) {
            minOf(toMillis, updatedMedicine.endDate)
        } else {
            toMillis
        }

        val newFutureDoses = DoseScheduler.generateDoses(
            medicine = updatedMedicine,
            fromEpochMillis = fromMillis,
            toEpochMillis = boundedToMillis,
            zoneId = zoneId
        )

        if (newFutureDoses.isNotEmpty()) {
            doseRepository.insertAll(newFutureDoses)
        }

        return newFutureDoses
    }

    /**
     * Deletes a medicine: removes future PENDING doses while preserving past history,
     * and removes the medicine record.
     */
    suspend fun deleteMedicine(medicineId: String) {
        val now = System.currentTimeMillis()
        doseRepository.deleteFuturePendingDoses(medicineId, now)
        val medicine = medicineRepository.getById(medicineId)
        if (medicine != null) {
            medicineRepository.delete(medicine)
        }
    }
}
