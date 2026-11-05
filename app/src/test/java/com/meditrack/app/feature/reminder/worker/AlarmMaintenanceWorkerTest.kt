package com.meditrack.app.feature.reminder.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.meditrack.app.feature.reminder.alarm.AlarmScheduler
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmMaintenanceWorkerTest {

    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private lateinit var context: Context
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private lateinit var worker: AlarmMaintenanceWorker

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        worker = AlarmMaintenanceWorker(context, workerParams, alarmScheduler)
    }

    @Test
    fun doWork_callsRescheduleAllPendingAlarms() = runTest {
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { alarmScheduler.rescheduleAllPendingAlarms() }
    }
}
