package com.meditrack.app.feature.verification.pipeline

import com.meditrack.app.core.util.JsonPayload
import android.graphics.Bitmap
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.domain.model.VerificationAttempt
import com.meditrack.app.domain.model.VerificationResultType
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.VerificationRepository
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import com.meditrack.app.domain.usecase.LateEntryPolicy
import com.meditrack.app.domain.usecase.ProcessMissedDoseUseCase
import com.meditrack.app.domain.usecase.RecordVerifiedDoseUseCase
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import com.meditrack.app.feature.verification.quality.PerceptualHasher
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CoordinatorResult {
    data class Success(
        val emptyCellsAfter: Int,
        val isLate: Boolean = false,
        val message: String
    ) : CoordinatorResult

    data class Failed(
        val reason: RejectReason,
        val attemptsRemaining: Int,
        val friendlyMessage: String? = null,
        val retryHint: String? = null,
        val failureReason: VerificationFailureReason? = null
    ) : CoordinatorResult

    data class Exhausted(
        val reason: RejectReason,
        val friendlyMessage: String,
        val retryHint: String? = null,
        val failureReason: VerificationFailureReason? = null
    ) : CoordinatorResult

    data class WindowExpired(
        val message: String
    ) : CoordinatorResult

    data class Error(
        val message: String
    ) : CoordinatorResult
}

@Singleton
class VerificationCoordinator @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository,
    private val verificationRepository: VerificationRepository,
    private val caregiverRepository: CaregiverRepository,
    private val outboxRepository: OutboxRepository,
    private val userPreferences: UserPreferences,
    private val verificationPipeline: VerificationPipeline,
    private val recordVerifiedDoseUseCase: RecordVerifiedDoseUseCase,
    private val processMissedDoseUseCase: ProcessMissedDoseUseCase,
    private val alarmScheduler: AlarmScheduler
) {

    suspend fun verify(
        doseId: String,
        photoPath: String,
        bitmap: Bitmap,
        now: Long = System.currentTimeMillis()
    ): CoordinatorResult {
        val dose = doseRepository.getById(doseId)
            ?: return CoordinatorResult.Error("Dose not found: $doseId")

        if (dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.TAKEN_LATE) {
            return CoordinatorResult.Error("This dose has already been verified as TAKEN.")
        }
        if (dose.status == DoseStatus.SKIPPED) {
            return CoordinatorResult.Error("This dose was marked as SKIPPED.")
        }
        if (dose.status == DoseStatus.UNVERIFIED) {
            return CoordinatorResult.Error("Verification failed after maximum attempts for this dose. Please contact your caregiver or healthcare provider.")
        }

        // Early verification guard: allow only a small tolerance before scheduledAt
        val earlyToleranceMillis = AppConfig.EARLY_VERIFY_TOLERANCE_MINUTES * 60_000L
        if (now < dose.scheduledAt - earlyToleranceMillis) {
            return CoordinatorResult.Failed(
                reason = RejectReason.TOO_EARLY,
                attemptsRemaining = AppConfig.MAX_VERIFICATION_ATTEMPTS - dose.verificationAttempts,
                friendlyMessage = "It is too early to take this dose. Please wait until your scheduled time."
            )
        }

        val medicine = medicineRepository.getById(dose.medicineId)
            ?: return CoordinatorResult.Error("Medicine not found for dose: ${dose.medicineId}")

        // 1. Gather historical pHashes for duplicate detection
        val recentAttempts = verificationRepository.getRecentAttemptsForMedicine(medicine.id, limit = 20)
        val existingHashes = recentAttempts.map { it.perceptualHash }.filter { it != 0L }

        // 2. Query last verified empty cell count (respecting strip refill ledger)
        val lastRefillAt = medicine.lastRefillAt ?: 0L
        val latestVerified = if (lastRefillAt > 0L) {
            doseRepository.getLatestVerifiedDoseAfter(medicine.id, lastRefillAt)
        } else {
            doseRepository.getLatestVerifiedDose(medicine.id)
        }
        val lastVerifiedEmptyCells = latestVerified?.emptyCellsAfter

        // 3. Compute pHash for this attempt
        val currentHash = PerceptualHasher.computeHash(bitmap)

        // 4. Run VerificationPipeline
        val pipelineResult = try {
            verificationPipeline.verify(
                bitmap = bitmap,
                medicine = medicine,
                lastVerifiedEmptyCells = lastVerifiedEmptyCells,
                existingHashes = existingHashes,
                currentHashOverride = currentHash
            )
        } catch (e: IllegalStateException) {
            return CoordinatorResult.Error("Verification temporarily unavailable. ${e.message ?: ""}".trim())
        } catch (e: Exception) {
            return CoordinatorResult.Error("Verification temporarily unavailable: ${e.message ?: "Unknown error"}")
        }

        // 5. Record attempt record in VerificationRepository
        val attemptRecord = VerificationAttempt(
            id = "attempt_${UUID.randomUUID()}",
            doseEventId = doseId,
            photoPath = photoPath,
            result = if (pipelineResult.passed) VerificationResultType.PASSED else VerificationResultType.REJECTED,
            rejectReason = pipelineResult.rejectReason,
            perceptualHash = currentHash,
            createdAt = now
        )
        verificationRepository.recordAttempt(attemptRecord)

        // 6. Handle Outcome
        val photoFile = java.io.File(photoPath)
        val captureTime = if (photoFile.exists() && photoFile.lastModified() > 0) photoFile.lastModified() else now

        if (pipelineResult.passed) {
            val emptyCells = pipelineResult.emptyCellsAfter ?: (lastVerifiedEmptyCells ?: medicine.baselineEmptyCells) + medicine.pillsPerDose

            // Check if intake window already expired mid-verification
            if (now > dose.windowEndsAt && dose.status != DoseStatus.MISSED) {
                // Window expired: check late entry policy
                val nextDose = doseRepository.getNextScheduledDose(medicine.id, dose.scheduledAt)
                val canLate = LateEntryPolicy.canLogLate(dose.copy(status = DoseStatus.MISSED), nextDose?.scheduledAt, now)
                return if (canLate) {
                    recordLateDose(dose, photoPath, emptyCells, captureTime, now)
                } else {
                    processMissedDoseUseCase(doseId, now)
                    CoordinatorResult.WindowExpired("The intake window expired before verification completed.")
                }
            }

            if (dose.status == DoseStatus.MISSED) {
                // Late dose flow
                val nextDose = doseRepository.getNextScheduledDose(medicine.id, dose.scheduledAt)
                val canLate = LateEntryPolicy.canLogLate(dose, nextDose?.scheduledAt, now)
                return if (canLate) {
                    recordLateDose(dose, photoPath, emptyCells, captureTime, now)
                } else {
                    CoordinatorResult.Error("Late entry cutoff passed. Dose remains MISSED.")
                }
            }

            // Standard on-time PASS: Call RecordVerifiedDoseUseCase
            val recordResult = recordVerifiedDoseUseCase(
                doseId = doseId,
                photoPath = photoPath,
                emptyCellsAfter = emptyCells,
                takenAt = captureTime
            )

            return if (recordResult.isSuccess) {
                CoordinatorResult.Success(
                    emptyCellsAfter = emptyCells,
                    isLate = false,
                    message = "Dose recorded ✔"
                )
            } else {
                CoordinatorResult.Error(recordResult.exceptionOrNull()?.message ?: "Failed to record verified dose")
            }
        } else {
            // Pipeline REJECTED: Count attempt strikes (quality gate rejections do not consume strikes)
            val reason = pipelineResult.rejectReason ?: RejectReason.WRONG_PILL_COUNT
            val failureReason = pipelineResult.failureReason
            val isQualityGate = failureReason?.isQualityGate ?: (reason in setOf(
                RejectReason.BLURRY,
                RejectReason.TOO_DARK,
                RejectReason.TOO_BRIGHT,
                RejectReason.GLARE,
                RejectReason.TOO_EARLY
            ))
            val newAttempts = if (isQualityGate) dose.verificationAttempts else dose.verificationAttempts + 1

            if (newAttempts < AppConfig.MAX_VERIFICATION_ATTEMPTS) {
                // Retake allowed
                val updatedDose = dose.copy(
                    verificationAttempts = newAttempts,
                    updatedAt = now
                )
                doseRepository.update(updatedDose)

                return CoordinatorResult.Failed(
                    reason = reason,
                    attemptsRemaining = AppConfig.MAX_VERIFICATION_ATTEMPTS - newAttempts,
                    friendlyMessage = pipelineResult.friendlyMessage,
                    retryHint = pipelineResult.retryHint,
                    failureReason = failureReason
                )
            } else {
                // 3 strikes exhausted: Transition via state machine
                val transitionResult = DoseStateMachine.transition(
                    currentStatus = dose.status,
                    event = DoseTransitionEvent.VerificationFailedExhausted
                )

                if (transitionResult.isSuccess) {
                    val newStatus = transitionResult.getOrThrow()
                    val updatedDose = dose.copy(
                        status = newStatus,
                        verificationAttempts = newAttempts,
                        updatedAt = now
                    )
                    doseRepository.update(updatedDose)
                    // Cancel reminder alarm but keep missed worker alive until window ends
                    alarmScheduler.cancelDoseAlarm(doseId, cancelMissedWorker = false)

                    // Enqueue DOSE_UNVERIFIED outbox event if caregiver connected
                    val currentUserId = userPreferences.currentUserId.first()
                    val hasCaregiver = currentUserId != null && caregiverRepository.hasConnectedCaregiver(currentUserId)
                    if (hasCaregiver) {
                        val payload = JsonPayload.build(
                            "doseId" to doseId,
                            "medicineId" to dose.medicineId,
                            "reason" to reason.name
                        )
                        val outboxEvent = NotificationOutbox(
                            id = "outbox_${UUID.randomUUID()}",
                            type = NotifType.DOSE_UNVERIFIED,
                            payloadJson = payload,
                            createdAt = now,
                            delivered = false
                        )
                        outboxRepository.enqueue(outboxEvent)
                    }

                    return CoordinatorResult.Exhausted(
                        reason = reason,
                        friendlyMessage = "We couldn't verify this dose. Your caregiver has been notified.",
                        retryHint = pipelineResult.retryHint,
                        failureReason = failureReason
                    )
                } else {
                    // For MISSED doses, VerificationFailedExhausted is illegal in state machine.
                    // Dose remains MISSED and no DOSE_UNVERIFIED outbox event is sent.
                    val updatedDose = dose.copy(
                        verificationAttempts = newAttempts,
                        updatedAt = now
                    )
                    doseRepository.update(updatedDose)
                    return CoordinatorResult.Exhausted(
                        reason = reason,
                        friendlyMessage = "Late dose verification attempts exhausted. Dose remains marked as missed."
                    )
                }
            }
        }
    }

    private suspend fun recordLateDose(
        dose: DoseEvent,
        photoPath: String,
        emptyCellsAfter: Int,
        captureTime: Long,
        now: Long
    ): CoordinatorResult {
        // Delegate to RecordVerifiedDoseUseCase as single authority (handles state machine, dose update, alarm cancel, and stock decrement)
        val recordResult = recordVerifiedDoseUseCase(
            doseId = dose.id,
            photoPath = photoPath,
            emptyCellsAfter = emptyCellsAfter,
            takenAt = captureTime,
            isLate = true
        )
        if (recordResult.isFailure) {
            return CoordinatorResult.Error(recordResult.exceptionOrNull()?.message ?: "Failed to record late dose")
        }

        // Enqueue DOSE_TAKEN_LATE outbox notification if caregiver connected
        val currentUserId = userPreferences.currentUserId.first()
        if (currentUserId != null && caregiverRepository.hasConnectedCaregiver(currentUserId)) {
            val payload = JsonPayload.build(
                "doseId" to dose.id,
                "medicineId" to dose.medicineId,
                "takenAt" to captureTime
            )
            val outboxEvent = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.DOSE_TAKEN_LATE,
                payloadJson = payload,
                createdAt = now,
                delivered = false
            )
            outboxRepository.enqueue(outboxEvent)
        }

        return CoordinatorResult.Success(
            emptyCellsAfter = emptyCellsAfter,
            isLate = true,
            message = "Late dose recorded ✔"
        )
    }
}
