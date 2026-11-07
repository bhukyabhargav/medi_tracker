package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordVerifiedDoseUseCase @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository,
    private val alarmScheduler: AlarmScheduler,
    private val processStockDecrementUseCase: ProcessStockDecrementUseCase
) {

    /**
     * Single authority for recording verified dose intake (both on-time and late):
     * - On-time: Transitions PENDING -> TAKEN via DoseTransitionEvent.VerifyPassed
     * - Late: Transitions MISSED -> TAKEN_LATE via DoseTransitionEvent.LateVerifyPassed
     *   (If dose was PENDING and window expired mid-verification, transitions PENDING -> MISSED -> TAKEN_LATE)
     * - Prevents duplicate decrements: DoseStateMachine rejects already closed doses (TAKEN/TAKEN_LATE)
     *   before any stock decrement is performed.
     * - Decrements medicine stock exactly once via ProcessStockDecrementUseCase
     * - Cancels dose alarm
     */
    suspend operator fun invoke(
        doseId: String,
        photoPath: String? = null,
        emptyCellsAfter: Int? = null,
        takenAt: Long = System.currentTimeMillis(),
        isLate: Boolean = false
    ): Result<Unit> = mutex.withLock {
        record(doseId, photoPath, emptyCellsAfter, takenAt, isLate)
    }

    // Serialises recording: a double tap or "Taken" notification action + in-app confirm must not
    // both pass the state-machine check and decrement stock twice.
    private val mutex = Mutex()

    private suspend fun record(
        doseId: String,
        photoPath: String?,
        emptyCellsAfter: Int?,
        takenAt: Long,
        isLate: Boolean
    ): Result<Unit> {
        val dose = doseRepository.getById(doseId)
            ?: return Result.failure(IllegalArgumentException("Dose not found: $doseId"))

        var isLateVerification = isLate || dose.status == DoseStatus.MISSED

        // Check if window has expired for a PENDING dose
        if (!isLateVerification && takenAt > dose.windowEndsAt) {
            val nextDose = doseRepository.getNextScheduledDose(dose.medicineId, dose.scheduledAt)
            val canLate = LateEntryPolicy.canLogLate(dose.copy(status = DoseStatus.MISSED), nextDose?.scheduledAt, takenAt)
            if (!canLate) {
                return Result.failure(IllegalStateException("Intake window expired and late entry cutoff has passed."))
            }
            isLateVerification = true
        }

        // If late verification on a PENDING dose whose window expired:
        val effectiveCurrentStatus = if (isLateVerification && dose.status == DoseStatus.PENDING) {
            val missedResult = DoseStateMachine.transition(dose.status, DoseTransitionEvent.WindowExpired)
            if (missedResult.isFailure) return Result.failure(missedResult.exceptionOrNull()!!)
            missedResult.getOrThrow()
        } else {
            dose.status
        }

        val event = if (isLateVerification) {
            DoseTransitionEvent.LateVerifyPassed(
                photoPath = photoPath ?: "",
                emptyCellsAfter = emptyCellsAfter ?: 0,
                takenAt = takenAt
            )
        } else {
            DoseTransitionEvent.VerifyPassed(
                photoPath = photoPath ?: "",
                emptyCellsAfter = emptyCellsAfter ?: 0,
                takenAt = takenAt
            )
        }

        val transitionResult = DoseStateMachine.transition(
            currentStatus = effectiveCurrentStatus,
            event = event
        )

        if (transitionResult.isFailure) {
            return Result.failure(transitionResult.exceptionOrNull()!!)
        }

        val newStatus = transitionResult.getOrThrow()
        val updatedDose = dose.copy(
            status = newStatus,
            takenAt = takenAt,
            verifiedPhotoPath = photoPath,
            emptyCellsAfter = emptyCellsAfter,
            lateEntry = (newStatus == DoseStatus.TAKEN_LATE),
            updatedAt = takenAt
        )
        doseRepository.update(updatedDose)
        alarmScheduler.cancelDoseAlarm(doseId)

        // Decrement stock & evaluate low stock transition atomically
        val medicine = medicineRepository.getById(dose.medicineId)
        if (medicine != null) {
            processStockDecrementUseCase(medicine.id, medicine.pillsPerDose, takenAt)
        }

        return Result.success(Unit)
    }
}
