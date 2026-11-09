package com.meditrack.app.feature.reminder.receiver

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meditrack.app.MediTrackApp
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.feature.reminder.NotificationChannels
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
class ReminderReceiverTest {

    private lateinit var context: Context
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private lateinit var receiver: ReminderReceiver
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    private val sampleMedicine = Medicine(
        id = "med_amox",
        patientId = "patient_alice",
        name = "Amoxicillin",
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

    private val samplePendingDose = DoseEvent(
        id = "dose_101",
        patientId = "patient_alice",
        medicineId = "med_amox",
        scheduledAt = 2000L,
        windowEndsAt = 8000L,
        status = DoseStatus.PENDING
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = shadowOf(nm)

        every { userPreferences.currentUserId } returns flowOf("patient_alice")
        coEvery { medicineRepository.getById("med_amox") } returns sampleMedicine
        coEvery { doseRepository.getById("dose_101") } returns samplePendingDose

        receiver = ReminderReceiver().apply {
            this.doseRepository = this@ReminderReceiverTest.doseRepository
            this.medicineRepository = this@ReminderReceiverTest.medicineRepository
            this.userPreferences = this@ReminderReceiverTest.userPreferences
        }
    }

    @Test
    fun processReminder_whenPendingDose_postsNotificationWithActions() = runTest {
        receiver.processReminder(context, "dose_101")

        val notifId = NotificationChannels.getNotificationIdForDose("dose_101")
        val notification = shadowNotificationManager.getNotification(notifId)
        assertNotNull("Reminder notification must be posted", notification)

        val shadowNotif = shadowOf(notification)
        assertEquals("Title should state medication name", "Time to take Amoxicillin", shadowNotif.contentTitle)
        assertTrue("Content should mention dosage", shadowNotif.contentText.toString().contains("500mg"))

        // Must have 3 actionable options: Taken, Snooze, Skip
        assertEquals("Must offer Taken, Snooze, and Skip actions", 3, notification.actions?.size ?: 0)
        val actionTitles = notification.actions.map { it.title.toString() }
        assertTrue("Must have Taken action", actionTitles.contains("Taken"))
        assertTrue("Must have Snooze action", actionTitles.any { it.contains("Snooze") })
        assertTrue("Must have Skip action", actionTitles.contains("Skip"))
    }

    @Test
    fun processReminder_whenDoseNotPending_skipsNotification() = runTest {
        coEvery { doseRepository.getById("dose_101") } returns samplePendingDose.copy(status = DoseStatus.TAKEN)

        receiver.processReminder(context, "dose_101")

        val notifId = NotificationChannels.getNotificationIdForDose("dose_101")
        val notification = shadowNotificationManager.getNotification(notifId)
        assertEquals("No notification should be posted for closed dose", null, notification)
    }

    @Test
    fun processReminder_whenDifferentUser_skipsNotificationForPrivacy() = runTest {
        // Active user is Bob, dose belongs to Alice
        every { userPreferences.currentUserId } returns flowOf("patient_bob")

        receiver.processReminder(context, "dose_101")

        val notifId = NotificationChannels.getNotificationIdForDose("dose_101")
        val notification = shadowNotificationManager.getNotification(notifId)
        assertEquals("Cross-account notification must never be posted", null, notification)
    }

    @Test
    fun processReminder_whenMedicineReminderDisabled_skipsNotification() = runTest {
        coEvery { medicineRepository.getById("med_amox") } returns sampleMedicine.copy(reminderEnabled = false)

        receiver.processReminder(context, "dose_101")

        val notifId = NotificationChannels.getNotificationIdForDose("dose_101")
        val notification = shadowNotificationManager.getNotification(notifId)
        assertEquals("No notification when reminderEnabled is false", null, notification)
    }

    @Test
    fun processReminder_whenMedicineDeleted_skipsNotification() = runTest {
        coEvery { medicineRepository.getById("med_amox") } returns sampleMedicine.copy(isDeleted = true)

        receiver.processReminder(context, "dose_101")

        val notifId = NotificationChannels.getNotificationIdForDose("dose_101")
        val notification = shadowNotificationManager.getNotification(notifId)
        assertEquals("No notification for deleted medicine", null, notification)
    }
}
