package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordVerifiedDoseUseCase @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository,
    private val alarmScheduler: AlarmScheduler
) {

    /**
     * Records a verified dose intake:
     * - Transitions dose status from PENDING to TAKEN via DoseStateMachine
     * - Records takenAt, verifiedPhotoPath, emptyCellsAfter
     * - Decreases medicine currentStock by pillsPerDose
     */
    suspend operator fun invoke(
        doseId: String,
        photoPath: String? = null,
        emptyCellsAfter: Int? = null,
        takenAt: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val dose = doseRepository.getById(doseId)
            ?: return Result.failure(IllegalArgumentException("Dose not found: $doseId"))

        val transitionResult = DoseStateMachine.transition(
            currentStatus = dose.status,
            event = DoseTransitionEvent.VerifyPassed(
                photoPath = photoPath ?: "",
                emptyCellsAfter = emptyCellsAfter ?: 0,
                takenAt = takenAt
            )
        )

        if (transitionResult.isFailure) {
            return Result.failure(transitionResult.exceptionOrNull()!!)
        }

        val updatedDose = dose.copy(
            status = DoseStatus.TAKEN,
            takenAt = takenAt,
            verifiedPhotoPath = photoPath,
            emptyCellsAfter = emptyCellsAfter,
            updatedAt = takenAt
        )
        doseRepository.update(updatedDose)
        alarmScheduler.cancelDoseAlarm(doseId)

        // Decrement stock
        val medicine = medicineRepository.getById(dose.medicineId)
        if (medicine != null) {
            val newStock = maxOf(0, medicine.currentStock - medicine.pillsPerDose)
            medicineRepository.updateStock(medicine.id, newStock)
        }

        return Result.success(Unit)
    }
}
