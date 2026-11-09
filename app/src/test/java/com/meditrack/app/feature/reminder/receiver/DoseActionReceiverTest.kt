package com.meditrack.app.feature.reminder.receiver

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.MediTrackApp
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.usecase.SkipDoseUseCase
import com.meditrack.app.domain.usecase.SnoozeDoseUseCase
import com.meditrack.app.feature.reminder.NotificationChannels
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(application = MediTrackApp::class, sdk = [34])
class DoseActionReceiverTest {

    private lateinit var context: Context
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private lateinit var snoozeDoseUseCase: SnoozeDoseUseCase
    private lateinit var skipDoseUseCase: SkipDoseUseCase
    private lateinit var receiver: DoseActionReceiver
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    private val now = System.currentTimeMillis()
    private val sampleDose = DoseEvent(
        id = "dose_202",
        patientId = "patient_alice",
        medicineId = "med_amox",
        scheduledAt = now,
        windowEndsAt = now + (60 * 60 * 1000L), // 1 hour window
        status = DoseStatus.PENDING
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = shadowOf(nm)

        snoozeDoseUseCase = SnoozeDoseUseCase(doseRepository)
        skipDoseUseCase = SkipDoseUseCase(doseRepository)

        coEvery { doseRepository.getById("dose_202") } returns sampleDose

        receiver = DoseActionReceiver().apply {
            this.snoozeDoseUseCase = this@DoseActionReceiverTest.snoozeDoseUseCase
            this.skipDoseUseCase = this@DoseActionReceiverTest.skipDoseUseCase
            this.alarmScheduler = this@DoseActionReceiverTest.alarmScheduler
            this.doseRepository = this@DoseActionReceiverTest.doseRepository
        }
    }

    @Test
    fun processAction_snooze_schedulesNewAlarmAndCancelsNotification() = runTest {
        receiver.processAction(context, "dose_202", DoseActionReceiver.ACTION_SNOOZE, snoozeMinutes = 15)

        // Verifies alarm reschedule
        verify { alarmScheduler.scheduleDoseAlarm(any(), any()) }

        // Verifies active notification is cancelled
        val notifId = NotificationChannels.getNotificationIdForDose("dose_202")
        assertNull("Notification must be dismissed upon snooze", shadowNotificationManager.getNotification(notifId))
    }

    @Test
    fun processAction_skip_callsSkipUseCaseAndCancelsAlarmAndNotification() = runTest {
        receiver.processAction(context, "dose_202", DoseActionReceiver.ACTION_SKIP)

        // Verifies alarm cancel
        verify { alarmScheduler.cancelDoseAlarm("dose_202") }

        // Verifies active notification is cancelled
        val notifId = NotificationChannels.getNotificationIdForDose("dose_202")
        assertNull("Notification must be dismissed upon skip", shadowNotificationManager.getNotification(notifId))
    }
}
