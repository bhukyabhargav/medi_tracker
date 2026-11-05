package com.meditrack.app.feature.reminder.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.MediTrackApp
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = MediTrackApp::class, sdk = [34])
class BootReceiverTest {

    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private lateinit var context: Context
    private lateinit var receiver: BootReceiver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        receiver = BootReceiver().apply {
            this.alarmScheduler = this@BootReceiverTest.alarmScheduler
        }
    }

    @Test
    fun processAction_bootCompleted_triggersReschedule() = runTest {
        receiver.processAction(Intent.ACTION_BOOT_COMPLETED, scheduler = alarmScheduler, scope = this)
        testScheduler.advanceUntilIdle()

        coVerify { alarmScheduler.rescheduleAllPendingAlarms() }
    }

    @Test
    fun processAction_packageReplaced_triggersReschedule() = runTest {
        receiver.processAction(Intent.ACTION_MY_PACKAGE_REPLACED, scheduler = alarmScheduler, scope = this)
        testScheduler.advanceUntilIdle()

        coVerify { alarmScheduler.rescheduleAllPendingAlarms() }
    }

    @Test
    fun processAction_timezoneChanged_regeneratesDoses() = runTest {
        val medRepo: com.meditrack.app.domain.repository.MedicineRepository = mockk(relaxed = true)
        val saveUseCase: com.meditrack.app.domain.usecase.SaveMedicineUseCase = mockk(relaxed = true)
        val sampleMed = com.meditrack.app.domain.model.Medicine(
            id = "med_tz",
            name = "Thyroxine",
            dosageText = "50mcg",
            pillsPerDose = 1,
            frequencyType = com.meditrack.app.domain.model.FrequencyType.DAILY,
            timesOfDay = emptyList(),
            startDate = 1000L,
            initialStock = 30,
            currentStock = 30
        )
        io.mockk.every { medRepo.observeActive() } returns kotlinx.coroutines.flow.flowOf(listOf(sampleMed))

        receiver.processAction(
            Intent.ACTION_TIMEZONE_CHANGED,
            scheduler = alarmScheduler,
            medRepo = medRepo,
            saveUseCase = saveUseCase,
            scope = this
        )
        testScheduler.advanceUntilIdle()

        coVerify { saveUseCase.updateMedicine(sampleMed, zoneId = any()) }
    }

    @Test
    fun processAction_unrelatedAction_ignores() = runTest {
        receiver.processAction("android.intent.action.AIRPLANE_MODE", scheduler = alarmScheduler, scope = this)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { alarmScheduler.rescheduleAllPendingAlarms() }
    }
}
