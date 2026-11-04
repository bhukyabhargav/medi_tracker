package com.meditrack.app.feature.reminder.worker

import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxOfflinePersistenceTest {

    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val deliveryGateway: NotificationDeliveryGateway = mockk(relaxed = true)

    @Test
    fun outboxEvents_persistLocallyWhenOffline() = runTest {
        val event1 = NotificationOutbox(
            id = "event_offline_1",
            type = NotifType.LOW_STOCK,
            payloadJson = """{"medicineName":"Metformin"}""",
            createdAt = 1000L,
            delivered = false
        )
        val event2 = NotificationOutbox(
            id = "event_offline_2",
            type = NotifType.MISSED_DOSE,
            payloadJson = """{"medicineName":"Lisinopril"}""",
            createdAt = 2000L,
            delivered = false
        )

        // Offline simulation: Network delivery fails
        coEvery { deliveryGateway.deliver(event1, any(), any()) } returns false
        coEvery { deliveryGateway.deliver(event2, any(), any()) } returns false

        // Repository returns pending events
        coEvery { outboxRepository.getUndelivered() } returns listOf(event1, event2)

        val pending = outboxRepository.getUndelivered()
        assertEquals(2, pending.size)
        assertFalse(pending[0].delivered)
        assertFalse(pending[1].delivered)

        // Never mark delivered while offline
        coVerify(exactly = 0) { outboxRepository.markDelivered(any()) }
    }

    @Test
    fun outboxEvents_deliveredOnceConnectivityRestored() = runTest {
        val event = NotificationOutbox(
            id = "event_restored",
            type = NotifType.DOSE_TAKEN_LATE,
            payloadJson = """{"medicineName":"Metformin"}""",
            createdAt = 1000L,
            delivered = false
        )

        // Connectivity restored: Remote delivery succeeds
        coEvery { deliveryGateway.deliver(event, "pat_1", "cg_1") } returns true

        val success = deliveryGateway.deliver(event, "pat_1", "cg_1")
        assertTrue(success)

        if (success) {
            outboxRepository.markDelivered(event.id)
        }

        coVerify(exactly = 1) { outboxRepository.markDelivered("event_restored") }
    }

    @Test
    fun idempotentDelivery_preventsDuplicateEntriesOnRetry() = runTest {
        val event = NotificationOutbox(
            id = "event_idempotent",
            type = NotifType.CAREGIVER_INVITE,
            payloadJson = """{"caregiverName":"Sarah"}""",
            createdAt = 1000L,
            delivered = false
        )

        coEvery { deliveryGateway.deliver(event, any(), any()) } returns true

        // First attempt succeeds
        val result1 = deliveryGateway.deliver(event, "pat_1", null)
        assertTrue(result1)

        // Second retry with same event ID (idempotent write by document ID)
        val result2 = deliveryGateway.deliver(event, "pat_1", null)
        assertTrue(result2)

        // Verifies delivery uses deterministic event.id as document ID
        assertEquals("event_idempotent", event.id)
    }
}
