package com.meditrack.app.feature.reminder.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.MediTrackApp
import com.meditrack.app.R
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.ProcessStockDecrementUseCase
import com.meditrack.app.domain.usecase.RecordVerifiedDoseUseCase
import com.meditrack.app.feature.reminder.NotificationChannels
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(application = MediTrackApp::class, sdk = [34])
class AlarmAndNotificationLifecycleTest {

    private lateinit var context: Context
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val processStockDecrementUseCase: ProcessStockDecrementUseCase = mockk(relaxed = true)
    private lateinit var scheduler: AlarmSchedulerImpl
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    private val sampleMedicine = Medicine(
        id = "med_1",
        patientId = "patient_alice",
        name = "Metformin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        startDate = 1000L,
        foodRelation = FoodRelation.AFTER,
        reminderEnabled = true,
        initialStock = 20,
        currentStock = 20
    )

    private val sampleDose = DoseEvent(
        id = "dose_303",
        patientId = "patient_alice",
        medicineId = "med_1",
        scheduledAt = 2000L,
        windowEndsAt = 8000L,
        status = DoseStatus.PENDING
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = shadowOf(nm)
        scheduler = AlarmSchedulerImpl(context, doseRepository, medicineRepository)

        coEvery { medicineRepository.getById("med_1") } returns sampleMedicine
        coEvery { doseRepository.getById("dose_303") } returns sampleDose
    }

    @Test
    fun cancelDoseAlarm_clearsActiveNotificationFromDrawer() {
        NotificationChannels.createChannels(context)
        val notifId = NotificationChannels.getNotificationIdForDose("dose_303")

        // Post a reminder notification in the system drawer
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_DOSE_REMINDERS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Time to take Metformin")
            .setContentText("500mg • 1 pill(s)")
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)

        assertNotNull("Notification should be active", shadowNotificationManager.getNotification(notifId))

        // Call cancelDoseAlarm
        scheduler.cancelDoseAlarm("dose_303")

        assertNull("Notification must be cleared from notification drawer when dose alarm is cancelled", shadowNotificationManager.getNotification(notifId))
    }

    @Test
    fun recordVerifiedDose_clearsActiveNotificationWhenDoseIsTaken() = runTest {
        NotificationChannels.createChannels(context)
        val notifId = NotificationChannels.getNotificationIdForDose("dose_303")

        // Post notification
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_DOSE_REMINDERS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Time to take Metformin")
            .setContentText("500mg • 1 pill(s)")
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)
        assertNotNull(shadowNotificationManager.getNotification(notifId))

        // Take dose via RecordVerifiedDoseUseCase
        val recordUseCase = RecordVerifiedDoseUseCase(
            doseRepository = doseRepository,
            medicineRepository = medicineRepository,
            alarmScheduler = scheduler,
            processStockDecrementUseCase = processStockDecrementUseCase
        )

        val result = recordUseCase("dose_303", photoPath = "proof.jpg", emptyCellsAfter = 1, takenAt = 5000L)
        assert(result.isSuccess)

        // Notification must be dismissed immediately!
        assertNull("Notification must be dismissed automatically when dose is recorded as taken", shadowNotificationManager.getNotification(notifId))
    }
}
