package com.meditrack.app.feature.reminder.worker

import android.content.Context
import androidx.work.WorkerParameters
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.LinkStatus
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
            userPreferences = userPreferences
        )
        coEvery { userPreferences.currentUserId } returns flowOf("patient_1")
        coEvery { caregiverRepository.observeByPatientId("patient_1") } returns flowOf(emptyList())
    }

    @Test
    fun doWork_whenEmptyOutbox_returnsSuccessImmediately() = runTest {
        coEvery { outboxRepository.getUndelivered() } returns emptyList()

        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { outboxRepository.markDelivered(any()) }
    }

    @Test
    fun doWork_whenFirebaseUnavailable_retainsPendingEvents() = runTest {
        coEvery { outboxRepository.getUndelivered() } returns listOf(sampleEvent)

        val result = worker.doWork()

        // Firebase is unavailable in JVM unit tests -> must retry and retain pending events
        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { outboxRepository.markDelivered(any()) }
    }

    @Test
    fun doWork_whenCaregiverRevoked_doesNotDeliverOrMarkDelivered() = runTest {
        val revokedLink = CaregiverLink(
            id = "link_1",
            patientId = "patient_1",
            caregiverName = "Jane",
            caregiverContact = "jane@example.com",
            relationship = "Daughter",
            status = LinkStatus.REVOKED,
            caregiverUid = "cg_revoked"
        )
        coEvery { caregiverRepository.observeByPatientId("patient_1") } returns flowOf(listOf(revokedLink))
        coEvery { outboxRepository.getUndelivered() } returns listOf(sampleEvent)

        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { outboxRepository.markDelivered(any()) }
    }
}
