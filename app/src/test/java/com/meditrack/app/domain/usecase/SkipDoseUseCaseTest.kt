package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.OutboxRepository
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

    @Test
    fun skipDose_withConnectedCaregiver_enqueuesDoseSkippedEvent() = runTest {
        val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
        val outboxRepository: OutboxRepository = mockk(relaxed = true)
        val dose = sampleDose.copy(patientId = "patient_1")
        coEvery { doseRepository.getById("dose_skip_1") } returns dose
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_1") } returns true
        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        val result = SkipDoseUseCase(doseRepository, caregiverRepository, outboxRepository)("dose_skip_1", now = 3000L)

        assertTrue(result.isSuccess)
        assertEquals(NotifType.DOSE_SKIPPED, outboxSlot.captured.type)
        assertTrue(outboxSlot.captured.payloadJson.contains("\"doseId\":\"dose_skip_1\""))
    }

    @Test
    fun skipDose_withoutCaregiver_enqueuesNothing() = runTest {
        val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
        val outboxRepository: OutboxRepository = mockk(relaxed = true)
        coEvery { doseRepository.getById("dose_skip_1") } returns sampleDose.copy(patientId = "patient_1")
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_1") } returns false

        val result = SkipDoseUseCase(doseRepository, caregiverRepository, outboxRepository)("dose_skip_1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { outboxRepository.enqueue(any()) }
    }

    @Test
    fun skipDose_notificationFailure_doesNotFailTheSkip() = runTest {
        val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
        val outboxRepository: OutboxRepository = mockk(relaxed = true)
        coEvery { doseRepository.getById("dose_skip_1") } returns sampleDose.copy(patientId = "patient_1")
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_1") } throws RuntimeException("db closed")

        val result = SkipDoseUseCase(doseRepository, caregiverRepository, outboxRepository)("dose_skip_1")

        assertTrue(result.isSuccess)
    }
}
