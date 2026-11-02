package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveMedicineUseCase @Inject constructor(
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val alarmScheduler: AlarmScheduler
) {

    /**
     * Inserts a new medicine, sets currentStock = initialStock, generates
     * upcoming DoseEvents for a rolling 7-day window, and schedules alarms.
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
            for (dose in doses) {
                alarmScheduler.scheduleDoseAlarm(dose)
            }
        }

        return doses
    }

    /**
     * Updates an existing medicine, deletes future PENDING doses only (preserving past history),
     * regenerates future doses for the rolling window, and schedules alarms.
     */
    suspend fun updateMedicine(
        updatedMedicine: Medicine,
        rollingDaysAhead: Long = 7L,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DoseEvent> {
        medicineRepository.update(updatedMedicine)

        val now = System.currentTimeMillis()
        // Cancel alarms for future pending doses before deleting
        val existingFuture = doseRepository.getFuturePendingDoses(now).filter { it.medicineId == updatedMedicine.id }
        for (oldDose in existingFuture) {
            alarmScheduler.cancelDoseAlarm(oldDose.id)
        }

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
            for (dose in newFutureDoses) {
                alarmScheduler.scheduleDoseAlarm(dose)
            }
        }

        return newFutureDoses
    }

    /**
     * Deletes a medicine: cancels alarms, removes future PENDING doses while preserving past history,
     * and removes the medicine record.
     */
    suspend fun deleteMedicine(medicineId: String) {
        val now = System.currentTimeMillis()
        val existingFuture = doseRepository.getFuturePendingDoses(now).filter { it.medicineId == medicineId }
        for (oldDose in existingFuture) {
            alarmScheduler.cancelDoseAlarm(oldDose.id)
        }
        doseRepository.deleteFuturePendingDoses(medicineId, now)
        val medicine = medicineRepository.getById(medicineId)
        if (medicine != null) {
            medicineRepository.update(medicine.copy(isDeleted = true))
        }
    }
}
