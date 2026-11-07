package com.meditrack.app.feature.reminder.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import java.time.LocalTime
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
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private lateinit var scheduler: AlarmSchedulerImpl
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)
        scheduler = AlarmSchedulerImpl(context, doseRepository, medicineRepository)
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

    private fun medicine(reminder: Boolean) = Medicine(
        id = "m1", patientId = "u1", name = "Aspirin", dosageText = "75mg", pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY, timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = 0L, initialStock = 10, currentStock = 10, reminderEnabled = reminder
    )

    @Test
    fun scheduleDoseAlarm_beyondHorizon_doesNotArmAlarmManager() {
        val now = System.currentTimeMillis()
        val far = now + 5L * 24 * 60 * 60 * 1000L
        val dose = DoseEvent(
            id = "d_far", medicineId = "m1", scheduledAt = far, windowEndsAt = far + 60_000L,
            status = DoseStatus.PENDING, updatedAt = now
        )

        scheduler.scheduleDoseAlarm(dose)

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun rescheduleAll_armsAlarmsOnlyForMedicinesWithRemindersEnabled() = runTest {
        val now = System.currentTimeMillis()
        val dose = DoseEvent(
            id = "d_on", medicineId = "m1", scheduledAt = now + 60_000L, windowEndsAt = now + 120_000L,
            status = DoseStatus.PENDING, updatedAt = now
        )
        coEvery { doseRepository.getPendingDueDoses(any()) } returns emptyList()
        coEvery { doseRepository.getFuturePendingDoses(any()) } returns listOf(dose)

        coEvery { medicineRepository.getById("m1") } returns medicine(reminder = true)
        scheduler.rescheduleAllPendingAlarms()
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun rescheduleAll_remindersOff_armsNoAlarm() = runTest {
        val now = System.currentTimeMillis()
        val dose = DoseEvent(
            id = "d_off", medicineId = "m1", scheduledAt = now + 60_000L, windowEndsAt = now + 120_000L,
            status = DoseStatus.PENDING, updatedAt = now
        )
        coEvery { doseRepository.getPendingDueDoses(any()) } returns emptyList()
        coEvery { doseRepository.getFuturePendingDoses(any()) } returns listOf(dose)
        coEvery { medicineRepository.getById("m1") } returns medicine(reminder = false)

        scheduler.rescheduleAllPendingAlarms()

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun rescheduleAll_skipsDeletedMedicine() = runTest {
        val now = System.currentTimeMillis()
        val dose = DoseEvent(
            id = "d_del", medicineId = "m1", scheduledAt = now + 60_000L, windowEndsAt = now + 120_000L,
            status = DoseStatus.PENDING, updatedAt = now
        )
        coEvery { doseRepository.getPendingDueDoses(any()) } returns emptyList()
        coEvery { doseRepository.getFuturePendingDoses(any()) } returns listOf(dose)
        coEvery { medicineRepository.getById("m1") } returns medicine(reminder = true).copy(isDeleted = true)

        scheduler.rescheduleAllPendingAlarms()

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }
}
