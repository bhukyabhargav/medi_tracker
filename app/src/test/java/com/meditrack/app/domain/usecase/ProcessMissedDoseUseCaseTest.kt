package com.meditrack.app.domain.usecase

import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProcessMissedDoseUseCaseTest {

    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)

    private lateinit var useCase: ProcessMissedDoseUseCase

    private val sampleDose = DoseEvent(
        id = "dose_missed_1",
        medicineId = "med_1",
        scheduledAt = 1000L,
        windowEndsAt = 4600L,
        status = DoseStatus.PENDING,
        updatedAt = 1000L
    )

    @Before
    fun setup() {
        useCase = ProcessMissedDoseUseCase(
            doseRepository = doseRepository,
            caregiverRepository = caregiverRepository,
            outboxRepository = outboxRepository,
            userPreferences = userPreferences
        )
    }

    @Test
    fun processMissedDose_marksMissed_andEnqueuesOutboxWhenCaregiverConnected() = runTest {
        coEvery { doseRepository.getById("dose_missed_1") } returns sampleDose
        every { userPreferences.currentUserId } returns flowOf("patient_123")
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_123") } returns true

        val doseSlot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(doseSlot)) } returns Unit

        val outboxSlot = slot<NotificationOutbox>()
        coEvery { outboxRepository.enqueue(capture(outboxSlot)) } returns Unit

        val result = useCase("dose_missed_1", now = 5000L)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
        assertEquals(DoseStatus.MISSED, doseSlot.captured.status)
        assertEquals(5000L, doseSlot.captured.updatedAt)

        coVerify { outboxRepository.enqueue(any()) }
        val outbox = outboxSlot.captured
        assertEquals(NotifType.MISSED_DOSE, outbox.type)
        assertFalse(outbox.delivered)
        assertTrue(outbox.payloadJson.contains("dose_missed_1"))
    }

    @Test
    fun processMissedDose_marksMissed_skipsOutboxWhenNoCaregiverConnected() = runTest {
        coEvery { doseRepository.getById("dose_missed_1") } returns sampleDose
        every { userPreferences.currentUserId } returns flowOf("patient_123")
        coEvery { caregiverRepository.hasConnectedCaregiver("patient_123") } returns false

        val doseSlot = slot<DoseEvent>()
        coEvery { doseRepository.update(capture(doseSlot)) } returns Unit

        val result = useCase("dose_missed_1", now = 5000L)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
        assertEquals(DoseStatus.MISSED, doseSlot.captured.status)

        // No outbox event written when no caregiver is connected
        coVerify(exactly = 0) { outboxRepository.enqueue(any()) }
    }

    @Test
    fun processMissedDose_ignoresAlreadyTakenDose() = runTest {
        val takenDose = sampleDose.copy(status = DoseStatus.TAKEN)
        coEvery { doseRepository.getById("dose_missed_1") } returns takenDose

        val result = useCase("dose_missed_1", now = 5000L)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull() == true) // Returns false: no transition needed

        coVerify(exactly = 0) { doseRepository.update(any()) }
        coVerify(exactly = 0) { outboxRepository.enqueue(any()) }
    }

    @Test
    fun processMissedDose_doesNothingBeforeWindowEnds() = runTest {
        coEvery { doseRepository.getById("dose_missed_1") } returns sampleDose
        val result = useCase("dose_missed_1", now = 4000L) // window ends at 4600
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
        coVerify(exactly = 0) { doseRepository.update(any()) }
    }
}
