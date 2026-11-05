package com.meditrack.app.feature.medicine.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyDoseExtensionWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)

    private lateinit var worker: DailyDoseExtensionWorker
    private val zoneId = ZoneId.of("UTC")

    @Before
    fun setup() {
        worker = DailyDoseExtensionWorker(
            appContext = context,
            workerParams = workerParams,
            medicineRepository = medicineRepository,
            doseRepository = doseRepository,
            alarmScheduler = alarmScheduler
        )
    }

    @Test
    fun doWork_extends_doses_for_active_medicines_and_schedules_alarms() = runTest {
        val now = System.currentTimeMillis()
        val activeMed = Medicine(
            id = "med_active_1",
            name = "Metformin",
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = now,
            endDate = now + (3 * 24 * 60 * 60 * 1000L),
            foodRelation = FoodRelation.AFTER,
            reminderEnabled = true,
            initialStock = 30,
            currentStock = 30
        )

        every { medicineRepository.observeActive() } returns flowOf(listOf(activeMed))
        coEvery { doseRepository.getLatestScheduledAt("med_active_1") } returns null
        coEvery { doseRepository.insertAll(any()) } returns Unit

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { doseRepository.insertAll(match { it.isNotEmpty() }) }
        coVerify { alarmScheduler.scheduleDoseAlarm(any(), any()) }
    }

    @Test
    fun doWork_whenIdleMoreThan7Days_generatesFromNowNotPast() = runTest {
        val now = System.currentTimeMillis()
        val tenDaysAgo = now - (10 * 24 * 60 * 60 * 1000L)
        val activeMed = Medicine(
            id = "med_idle",
            name = "Amlodipine",
            dosageText = "5mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            startDate = tenDaysAgo,
            endDate = now + (3 * 24 * 60 * 60 * 1000L),
            foodRelation = FoodRelation.AFTER,
            reminderEnabled = true,
            initialStock = 30,
            currentStock = 30
        )

        every { medicineRepository.observeActive() } returns flowOf(listOf(activeMed))
        coEvery { doseRepository.getLatestScheduledAt("med_idle") } returns (tenDaysAgo + 1000L)
        val insertedSlot = slot<List<DoseEvent>>()
        coEvery { doseRepository.insertAll(capture(insertedSlot)) } returns Unit

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // No inserted dose should be before now
        assertTrue(insertedSlot.captured.all { it.scheduledAt >= now })
    }
}
