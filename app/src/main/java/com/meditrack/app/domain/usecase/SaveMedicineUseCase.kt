package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
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
     * upcoming DoseEvents for a rolling 7-day window, and schedules alarms if reminders are enabled.
     * Prevents generating past doses if startDate is in the past.
     */
    suspend fun saveNewMedicine(
        medicine: Medicine,
        rollingDaysAhead: Long = 7L,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DoseEvent> {
        val now = System.currentTimeMillis()
        val medicineToSave = medicine.copy(
            currentStock = medicine.initialStock,
            updatedAt = now
        )
        medicineRepository.insert(medicineToSave)

        val fromMillis = maxOf(now, medicineToSave.startDate)
        val toMillis = fromMillis + (rollingDaysAhead * 24 * 60 * 60 * 1000L)
        val boundedToMillis = if (medicineToSave.endDate != null) {
            minOf(toMillis, medicineToSave.endDate)
        } else {
            toMillis
        }

        val rawDoses = DoseScheduler.generateDoses(
            medicine = medicineToSave,
            fromEpochMillis = fromMillis,
            toEpochMillis = boundedToMillis,
            zoneId = zoneId
        )

        val doses = rawDoses.map { it.copy(patientId = medicineToSave.patientId) }

        if (doses.isNotEmpty()) {
            doseRepository.insertAll(doses)
            val maxAlarmHorizon = now + (48 * 60 * 60 * 1000L)
            for (dose in doses) {
                if (medicineToSave.reminderEnabled && dose.scheduledAt <= maxAlarmHorizon) {
                    alarmScheduler.scheduleDoseAlarm(dose)
                } else {
                    alarmScheduler.scheduleMissedCheck(dose)
                }
            }
        }

        return doses
    }

    /**
     * Updates an existing medicine, deletes only future pending doses (scheduledAt > now,
     * preserving the open in-window pending dose and past history), regenerates future doses
     * for the rolling window, and schedules alarms/missed checks.
     */
    suspend fun updateMedicine(
        updatedMedicine: Medicine,
        rollingDaysAhead: Long = 7L,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DoseEvent> {
        val now = System.currentTimeMillis()
        val medicineToUpdate = updatedMedicine.copy(updatedAt = now)
        medicineRepository.update(medicineToUpdate)

        // Cancel alarms only for future pending doses before deleting
        val existingFuturePending = doseRepository.getFuturePendingDoses(now).filter { it.medicineId == medicineToUpdate.id }
        for (oldDose in existingFuturePending) {
            alarmScheduler.cancelDoseAlarm(oldDose.id)
        }

        // Delete ONLY future pending doses (scheduledAt > now); keep open in-window pending dose
        doseRepository.deleteFuturePendingDoses(medicineToUpdate.id, now)

        if (medicineToUpdate.stoppedByDoctor) {
            return emptyList()
        }

        val fromMillis = maxOf(now + 1000L, medicineToUpdate.startDate)
        val toMillis = now + (rollingDaysAhead * 24 * 60 * 60 * 1000L)
        val boundedToMillis = if (medicineToUpdate.endDate != null) {
            minOf(toMillis, medicineToUpdate.endDate)
        } else {
            toMillis
        }

        val rawFutureDoses = DoseScheduler.generateDoses(
            medicine = medicineToUpdate,
            fromEpochMillis = fromMillis,
            toEpochMillis = boundedToMillis,
            zoneId = zoneId
        )

        val newFutureDoses = rawFutureDoses.map { it.copy(patientId = medicineToUpdate.patientId) }

        if (newFutureDoses.isNotEmpty()) {
            doseRepository.insertAll(newFutureDoses)
            val maxAlarmHorizon = now + (48 * 60 * 60 * 1000L)
            for (dose in newFutureDoses) {
                if (medicineToUpdate.reminderEnabled && dose.scheduledAt <= maxAlarmHorizon) {
                    alarmScheduler.scheduleDoseAlarm(dose)
                } else {
                    alarmScheduler.scheduleMissedCheck(dose)
                }
            }
        }

        return newFutureDoses
    }

    /**
     * Deletes a medicine: cancels alarms, removes all PENDING doses while preserving past history,
     * and flags the medicine as deleted.
     */
    suspend fun deleteMedicine(medicineId: String) {
        val now = System.currentTimeMillis()
        val existingPending = doseRepository.getPendingDueDoses(now) + doseRepository.getFuturePendingDoses(now)
        val medicinePending = existingPending.filter { it.medicineId == medicineId }
        for (oldDose in medicinePending) {
            alarmScheduler.cancelDoseAlarm(oldDose.id)
        }
        // Delete all PENDING doses (even if inside current window)
        doseRepository.deleteAllPendingForMedicine(medicineId)

        val medicine = medicineRepository.getById(medicineId)
        if (medicine != null) {
            medicineRepository.update(medicine.copy(isDeleted = true, updatedAt = now))
        }
    }
}
