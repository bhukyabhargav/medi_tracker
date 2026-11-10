package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordManualUnverifiedUseCaseTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)

    private lateinit var useCase: RecordManualUnverifiedUseCase

    private val missedDose = DoseEvent(
        id = "dose_missed_1",
        medicineId = "med_1",
        scheduledAt = 2000L,
        windowEndsAt = 5600L,
        status = DoseStatus.MISSED,
        updatedAt = 5600L
    )

    @Before
    fun setup() {
        useCase = RecordManualUnverifiedUseCase(doseRepository, alarmScheduler)
    }

    @Test
    fun manualUnverified_transitionsToMissed_withManualUnverifiedFlag() = runTest {
        coEvery { doseRepository.getById("dose_missed_1") } returns missedDose

        val updatedSlot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(updatedSlot)) } returns Unit

        val result = useCase("dose_missed_1", reportedAt = 6000L)

        assertTrue("Manual unverified report must succeed for missed dose", result.isSuccess)
        val saved = updatedSlot.captured
        assertEquals("Status must remain MISSED", DoseStatus.MISSED, saved.status)
        assertTrue("manualUnverified must be set to true", saved.manualUnverified)
        assertFalse("lateEntry must NOT be set to true", saved.lateEntry)
        assertEquals(6000L, saved.updatedAt)

        coVerify(exactly = 1) { alarmScheduler.cancelDoseAlarm("dose_missed_1") }
    }

    @Test
    fun manualUnverified_onPendingDose_failsStateTransition() = runTest {
        val pendingDose = missedDose.copy(status = DoseStatus.PENDING)
        coEvery { doseRepository.getById("dose_missed_1") } returns pendingDose

        val result = useCase("dose_missed_1", reportedAt = 3000L)

        assertTrue("Manual report on PENDING dose must fail state transition", result.isFailure)
        coVerify(exactly = 0) { doseRepository.update(any()) }
    }

    @Test
    fun manualUnverified_onTakenDose_failsStateTransition() = runTest {
        val takenDose = missedDose.copy(status = DoseStatus.TAKEN)
        coEvery { doseRepository.getById("dose_missed_1") } returns takenDose

        val result = useCase("dose_missed_1", reportedAt = 6000L)

        assertTrue("Manual report on already TAKEN dose must fail", result.isFailure)
        coVerify(exactly = 0) { doseRepository.update(any()) }
    }

    @Test
    fun manualUnverified_whenDoseNotFound_fails() = runTest {
        coEvery { doseRepository.getById("missing") } returns null

        val result = useCase("missing")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { doseRepository.update(any()) }
    }
}
