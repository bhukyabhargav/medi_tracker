package com.meditrack.app.feature.reminder.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OutboxDeliveryWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)
    private val caregiverRepository: CaregiverRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val deliveryGateway: NotificationDeliveryGateway = mockk(relaxed = true)

    private lateinit var worker: OutboxDeliveryWorker

    private val sampleEvent = NotificationOutbox(
        id = "event_123",
        type = NotifType.MISSED_DOSE,
        payloadJson = """{"doseId":"dose_1","medicineName":"Aspirin"}""",
        createdAt = 1000L,
        delivered = false
    )

    @Before
    fun setUp() {
        worker = OutboxDeliveryWorker(
            appContext = context,
            workerParams = workerParams,
            outboxRepository = outboxRepository,
            caregiverRepository = caregiverRepository,
            userPreferences = userPreferences,
            deliveryGateway = deliveryGateway
        )
        coEvery { userPreferences.currentUserId } returns flowOf("patient_1")
        coEvery { caregiverRepository.observeByPatientId("patient_1") } returns flowOf(emptyList())
    }

    @Test
    fun doWork_whenEmptyOutbox_returnsSuccessImmediately() = runTest {
        coEvery { outboxRepository.getUndelivered() } returns emptyList()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { outboxRepository.markDelivered(any()) }
    }

    @Test
    fun doWork_deliversUndeliveredEvent_andMarksDeliveredOnSuccess() = runTest {
        coEvery { outboxRepository.getUndelivered() } returns listOf(sampleEvent)
        coEvery { deliveryGateway.deliver(sampleEvent, "patient_1", null) } returns true

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { outboxRepository.markDelivered("event_123") }
    }

    @Test
    fun doWork_whenDeliveryFailsOrOffline_preservesPendingEventAndRetries() = runTest {
        coEvery { outboxRepository.getUndelivered() } returns listOf(sampleEvent)
        coEvery { deliveryGateway.deliver(sampleEvent, "patient_1", null) } returns false

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        // Verifies invariant: Pending events are preserved during offline operation and never marked delivered
        coVerify(exactly = 0) { outboxRepository.markDelivered("event_123") }
    }
}
