package com.meditrack.app.domain.usecase

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

class SkipDoseUseCaseTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private lateinit var useCase: SkipDoseUseCase

    private val sampleDose = DoseEvent(
        id = "dose_skip_1",
        medicineId = "med_1",
        scheduledAt = 2000L,
        windowEndsAt = 5600L,
        status = DoseStatus.PENDING,
        updatedAt = 1000L
    )

    @Before
    fun setup() {
        useCase = SkipDoseUseCase(doseRepository)
    }

    @Test
    fun skipDose_transitionsPendingToSkipped() = runTest {
        coEvery { doseRepository.getById("dose_skip_1") } returns sampleDose

        val updatedSlot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(updatedSlot)) } returns Unit

        val result = useCase("dose_skip_1", now = 3000L)

        assertTrue(result.isSuccess)
        assertEquals(DoseStatus.SKIPPED, updatedSlot.captured.status)
        assertEquals(3000L, updatedSlot.captured.updatedAt)
    }

    @Test
    fun skipDose_failsWhenAlreadyMissed() = runTest {
        val missedDose = sampleDose.copy(status = DoseStatus.MISSED)
        coEvery { doseRepository.getById("dose_skip_1") } returns missedDose

        val result = useCase("dose_skip_1")

        assertTrue(result.isFailure)
    }

    @Test
    fun skipDose_failsWhenNotFound() = runTest {
        coEvery { doseRepository.getById("not_found") } returns null

        val result = useCase("not_found")

        assertTrue(result.isFailure)
    }
}
