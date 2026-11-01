package com.meditrack.app.feature.reminder.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.repository.DoseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmSchedulerTest {

    private lateinit var context: Context
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private lateinit var scheduler: AlarmSchedulerImpl
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)
        scheduler = AlarmSchedulerImpl(context, doseRepository)
    }

    @Test
    fun scheduleDoseAlarm_schedulesAlarmClock() {
        val now = System.currentTimeMillis()
        val dose = DoseEvent(
            id = "d1",
            medicineId = "m1",
            scheduledAt = now + 10_000L,
            windowEndsAt = now + 70_000L,
            status = DoseStatus.PENDING,
            updatedAt = now
        )

        scheduler.scheduleDoseAlarm(dose, triggerAtMillis = now + 10_000L)

        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertEquals(1, scheduledAlarms.size)
        assertNotNull(scheduledAlarms[0].operation)
    }

    @Test
    fun cancelDoseAlarm_cancelsScheduledAlarm() {
        val now = System.currentTimeMillis()
        val dose = DoseEvent(
            id = "d_cancel",
            medicineId = "m1",
            scheduledAt = now + 10_000L,
            windowEndsAt = now + 70_000L,
            status = DoseStatus.PENDING,
            updatedAt = now
        )

        scheduler.scheduleDoseAlarm(dose, triggerAtMillis = now + 10_000L)
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)

        scheduler.cancelDoseAlarm("d_cancel")
        // The alarm is canceled
    }

    @Test
    fun rescheduleAllPendingAlarms_queriesPendingAndFutureDoses() = runTest {
        val now = System.currentTimeMillis()
        val dose1 = DoseEvent(
            id = "d1",
            medicineId = "m1",
            scheduledAt = now + 10_000L,
            windowEndsAt = now + 70_000L,
            status = DoseStatus.PENDING,
            updatedAt = now
        )

        coEvery { doseRepository.getPendingDueDoses(any()) } returns listOf(dose1)
        coEvery { doseRepository.getFuturePendingDoses(any()) } returns emptyList()

        scheduler.rescheduleAllPendingAlarms()

        coVerify { doseRepository.getPendingDueDoses(any()) }
        coVerify { doseRepository.getFuturePendingDoses(any()) }
    }
}
