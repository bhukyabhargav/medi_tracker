package com.meditrack.app.domain.usecase

import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SnoozeDoseUseCaseTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private lateinit var useCase: SnoozeDoseUseCase

    private val baseTime = 100_000L
    private val windowEnd = baseTime + (60 * 60 * 1000L) // 1 hour window

    private val sampleDose = DoseEvent(
        id = "dose_snooze_1",
        medicineId = "med_1",
        scheduledAt = baseTime,
        windowEndsAt = windowEnd,
        status = DoseStatus.PENDING,
        snoozeCount = 0,
        updatedAt = baseTime
    )

    @Before
    fun setup() {
        useCase = SnoozeDoseUseCase(doseRepository)
    }

    @Test
    fun snoozeDose_incrementsSnoozeCount_andReturnsTriggerTime() = runTest {
        coEvery { doseRepository.getById("dose_snooze_1") } returns sampleDose

        val updatedSlot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(updatedSlot)) } returns Unit

        val result = useCase("dose_snooze_1", snoozeMinutes = 15, now = baseTime)

        assertTrue(result.isSuccess)
        val expectedTrigger = baseTime + (15 * 60 * 1000L)
        assertEquals(expectedTrigger, result.getOrNull())

        assertEquals(1, updatedSlot.captured.snoozeCount)
        assertEquals(baseTime, updatedSlot.captured.updatedAt)
    }

    @Test
    fun snoozeDose_failsWhenCappedAtMaxSnoozes() = runTest {
        val maxedDose = sampleDose.copy(snoozeCount = AppConfig.MAX_SNOOZES_PER_DOSE)
        coEvery { doseRepository.getById("dose_snooze_1") } returns maxedDose

        val result = useCase("dose_snooze_1", snoozeMinutes = 15, now = baseTime)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Maximum of 3 snoozes reached") == true)
    }

    @Test
    fun snoozeDose_failsWhenTargetPastWindowEnd() = runTest {
        coEvery { doseRepository.getById("dose_snooze_1") } returns sampleDose

        // 50 minutes after baseTime + 15 min snooze = 65 minutes > 60 min window
        val nearEndTime = baseTime + (50 * 60 * 1000L)
        val result = useCase("dose_snooze_1", snoozeMinutes = 15, now = nearEndTime)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("past the intake window") == true)
    }
}
