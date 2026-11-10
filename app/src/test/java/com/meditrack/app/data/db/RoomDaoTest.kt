package com.meditrack.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoctorLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.db.dao.MedicineProposalDao
import com.meditrack.app.data.db.dao.NotificationOutboxDao
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.db.dao.VerificationAttemptDao
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.LinkStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.model.RejectReason
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.model.VerificationAttempt
import com.meditrack.app.domain.model.VerificationResultType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var userDao: UserDao
    private lateinit var medicineDao: MedicineDao
    private lateinit var doseEventDao: DoseEventDao
    private lateinit var verificationAttemptDao: VerificationAttemptDao
    private lateinit var caregiverLinkDao: CaregiverLinkDao
    private lateinit var doctorLinkDao: DoctorLinkDao
    private lateinit var medicineProposalDao: MedicineProposalDao
    private lateinit var notificationOutboxDao: NotificationOutboxDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        userDao = db.userDao()
        medicineDao = db.medicineDao()
        doseEventDao = db.doseEventDao()
        verificationAttemptDao = db.verificationAttemptDao()
        caregiverLinkDao = db.caregiverLinkDao()
        doctorLinkDao = db.doctorLinkDao()
        medicineProposalDao = db.medicineProposalDao()
        notificationOutboxDao = db.notificationOutboxDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // --- USER DAO TESTS ---

    @Test
    fun userDao_insertAndRetrieveUser() = runTest {
        val user = User(
            id = "user_1",
            name = "John Doe",
            email = "john@example.com",
            phone = "+1234567890",
            role = UserRole.PATIENT
        )
        userDao.insertUser(user)

        val retrieved = userDao.getUserById("user_1")
        assertNotNull(retrieved)
        assertEquals("John Doe", retrieved?.name)
        assertEquals(UserRole.PATIENT, retrieved?.role)
    }

    @Test
    fun userDao_observeCurrentUserAndClear() = runTest {
        val user = User(id = "user_2", name = "Dr. Smith", role = UserRole.DOCTOR)
        userDao.observeCurrentUser().test {
            assertNull(awaitItem())

            userDao.insertUser(user)
            val updated = awaitItem()
            assertEquals("Dr. Smith", updated?.name)

            userDao.clearUsers()
            assertNull(awaitItem())
        }
    }

    // --- MEDICINE DAO TESTS ---

    private fun createSampleMedicine(
        id: String = "med_1",
        name: String = "Metformin",
        stoppedByDoctor: Boolean = false,
        isDeleted: Boolean = false,
        stock: Int = 30
    ): Medicine {
        return Medicine(
            id = id,
            name = name,
            dosageText = "500mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
            startDate = 1700000000000L,
            foodRelation = FoodRelation.AFTER,
            initialStock = 30,
            currentStock = stock,
            stoppedByDoctor = stoppedByDoctor,
            isDeleted = isDeleted
        )
    }

    @Test
    fun medicineDao_insertRetrieveUpdateDelete() = runTest {
        val med = createSampleMedicine()
        medicineDao.insert(med)

        var retrieved = medicineDao.getById("med_1")
        assertNotNull(retrieved)
        assertEquals(30, retrieved?.currentStock)

        // Update stock
        medicineDao.updateStock("med_1", 29)
        retrieved = medicineDao.getById("med_1")
        assertEquals(29, retrieved?.currentStock)

        // Delete
        medicineDao.delete(retrieved!!)
        assertNull(medicineDao.getById("med_1"))
    }

    @Test
    fun medicineDao_observeActiveExcludesStoppedByDoctor() = runTest {
        val activeMed = createSampleMedicine("med_active", "Paracetamol", stoppedByDoctor = false)
        val stoppedMed = createSampleMedicine("med_stopped", "Aspirin", stoppedByDoctor = true)

        medicineDao.observeActive().test {
            assertTrue(awaitItem().isEmpty())

            medicineDao.insert(activeMed)
            var activeList = awaitItem()
            assertEquals(1, activeList.size)
            assertEquals("Paracetamol", activeList[0].name)

            medicineDao.insert(stoppedMed)
            // Table changed so Room re-emits; observeActive must still only contain the active medicine
            val updatedList = awaitItem()
            assertEquals(1, updatedList.size)
            assertEquals("Paracetamol", updatedList[0].name)
            activeList = medicineDao.getById("med_stopped")?.let { listOf(it) } ?: emptyList()
            assertEquals(1, activeList.size)
            assertTrue(activeList[0].stoppedByDoctor)
        }
    }

    @Test
    fun medicineDao_observeActiveExcludesDeletedMedicines() = runTest {
        val activeMed = createSampleMedicine("med_active_2", "Ibuprofen", isDeleted = false)
        val deletedMed = createSampleMedicine("med_deleted", "Cough Syrup", isDeleted = true)

        medicineDao.insert(activeMed)
        medicineDao.insert(deletedMed)

        medicineDao.observeActive().test {
            val list = awaitItem()
            assertTrue(list.any { it.id == "med_active_2" })
            assertFalse(list.any { it.id == "med_deleted" })
        }

        medicineDao.observeAll().test {
            val allList = awaitItem()
            assertTrue(allList.any { it.id == "med_active_2" })
            assertTrue(allList.any { it.id == "med_deleted" })
        }
    }

    // --- DOSE EVENT DAO TESTS ---

    private fun createSampleDose(
        id: String = "dose_1",
        medicineId: String = "med_1",
        scheduledAt: Long = 1700000000000L,
        status: DoseStatus = DoseStatus.PENDING
    ): DoseEvent {
        return DoseEvent(
            id = id,
            medicineId = medicineId,
            scheduledAt = scheduledAt,
            windowEndsAt = scheduledAt + (60 * 60 * 1000L),
            status = status
        )
    }

    @Test
    fun doseEventDao_insertAndQueryByRange() = runTest {
        medicineDao.insert(createSampleMedicine("med_1"))

        val dose1 = createSampleDose("dose_1", "med_1", scheduledAt = 1000L)
        val dose2 = createSampleDose("dose_2", "med_1", scheduledAt = 2000L)
        val dose3 = createSampleDose("dose_3", "med_1", scheduledAt = 5000L)

        doseEventDao.insertAll(listOf(dose1, dose2, dose3))

        doseEventDao.observeBetween(500L, 2500L).test {
            val dosesInRange = awaitItem()
            assertEquals(2, dosesInRange.size)
            assertEquals("dose_1", dosesInRange[0].id)
            assertEquals("dose_2", dosesInRange[1].id)
        }
    }

    @Test
    fun doseEventDao_getPendingDueAndFutureDoses() = runTest {
        medicineDao.insert(createSampleMedicine("med_1"))

        val pastDueDose = createSampleDose("dose_past", "med_1", scheduledAt = 1000L, status = DoseStatus.PENDING)
        val futureDose = createSampleDose("dose_future", "med_1", scheduledAt = 5000L, status = DoseStatus.PENDING)
        doseEventDao.insertAll(listOf(pastDueDose, futureDose))

        val due = doseEventDao.getPendingDueDoses(now = 2000L)
        assertEquals(1, due.size)
        assertEquals("dose_past", due[0].id)

        val future = doseEventDao.getFuturePendingDoses(now = 2000L)
        assertEquals(1, future.size)
        assertEquals("dose_future", future[0].id)
    }

    @Test
    fun doseEventDao_getLatestVerifiedAndNextScheduled() = runTest {
        medicineDao.insert(createSampleMedicine("med_1"))

        val verifiedDose = createSampleDose("dose_v", "med_1", scheduledAt = 1000L, status = DoseStatus.TAKEN)
        val pendingDose = createSampleDose("dose_next", "med_1", scheduledAt = 3000L, status = DoseStatus.PENDING)
        doseEventDao.insertAll(listOf(verifiedDose, pendingDose))

        val latestVerified = doseEventDao.getLatestVerifiedDose("med_1")
        assertNotNull(latestVerified)
        assertEquals("dose_v", latestVerified?.id)
        assertEquals(DoseStatus.TAKEN, latestVerified?.status)

        val nextScheduled = doseEventDao.getNextScheduledDose("med_1", scheduledAt = 1000L)
        assertNotNull(nextScheduled)
        assertEquals("dose_next", nextScheduled?.id)
    }

    @Test
    fun doseEventDao_deleteFuturePendingDoses() = runTest {
        medicineDao.insert(createSampleMedicine("med_1"))

        val pastDose = createSampleDose("dose_past", "med_1", scheduledAt = 1000L, status = DoseStatus.PENDING)
        val futureDose1 = createSampleDose("dose_fut1", "med_1", scheduledAt = 4000L, status = DoseStatus.PENDING)
        val futureDose2 = createSampleDose("dose_fut2", "med_1", scheduledAt = 6000L, status = DoseStatus.PENDING)
        doseEventDao.insertAll(listOf(pastDose, futureDose1, futureDose2))

        doseEventDao.deleteFuturePendingDoses("med_1", afterTimestamp = 2000L)

        val remainingPast = doseEventDao.getById("dose_past")
        val deletedFut1 = doseEventDao.getById("dose_fut1")
        val deletedFut2 = doseEventDao.getById("dose_fut2")

        assertNotNull(remainingPast)
        assertNull(deletedFut1)
        assertNull(deletedFut2)
    }

    // --- VERIFICATION ATTEMPT DAO TESTS ---

    @Test
    fun verificationAttemptDao_insertAndQuery() = runTest {
        medicineDao.insert(createSampleMedicine("med_1"))
        doseEventDao.insert(createSampleDose("dose_1", "med_1"))

        val attempt1 = VerificationAttempt(
            id = "att_1",
            doseEventId = "dose_1",
            photoPath = "/proofs/p1.jpg",
            result = VerificationResultType.REJECTED,
            rejectReason = RejectReason.BLURRY,
            perceptualHash = 12345L,
            createdAt = 1000L
        )
        val attempt2 = VerificationAttempt(
            id = "att_2",
            doseEventId = "dose_1",
            photoPath = "/proofs/p2.jpg",
            result = VerificationResultType.PASSED,
            rejectReason = null,
            perceptualHash = 67890L,
            createdAt = 2000L
        )

        verificationAttemptDao.insert(attempt1)
        verificationAttemptDao.insert(attempt2)

        val attempts = verificationAttemptDao.getAttemptsForDose("dose_1")
        assertEquals(2, attempts.size)
        assertEquals(VerificationResultType.REJECTED, attempts[0].result)
        assertEquals(RejectReason.BLURRY, attempts[0].rejectReason)
        assertEquals(VerificationResultType.PASSED, attempts[1].result)

        val recentForMed = verificationAttemptDao.getRecentAttemptsForMedicine("med_1")
        assertEquals(2, recentForMed.size)
    }

    // --- CAREGIVER LINK DAO TESTS ---

    @Test
    fun caregiverLinkDao_insertUpdateAndGetAccepted() = runTest {
        val link = CaregiverLink(
            id = "cg_1",
            patientId = "patient_1",
            caregiverName = "Jane Doe",
            caregiverContact = "jane@example.com",
            relationship = "Spouse",
            status = LinkStatus.PENDING
        )
        caregiverLinkDao.insert(link)

        var accepted = caregiverLinkDao.getByStatus("patient_1", LinkStatus.ACCEPTED)
        assertTrue(accepted.isEmpty())

        // Update to accepted
        caregiverLinkDao.update(link.copy(status = LinkStatus.ACCEPTED))
        accepted = caregiverLinkDao.getByStatus("patient_1", LinkStatus.ACCEPTED)
        assertEquals(1, accepted.size)
        assertEquals("Jane Doe", accepted[0].caregiverName)
    }

    // --- DOCTOR LINK DAO TESTS ---

    @Test
    fun doctorLinkDao_insertAndObserveStatus() = runTest {
        val doctorLink = DoctorLink(
            id = "doc_link_1",
            patientId = "patient_1",
            doctorUid = "doc_uid_1",
            doctorName = "Dr. Alice",
            doctorContact = "alice@clinic.com",
            status = DoctorLinkStatus.PENDING
        )
        doctorLinkDao.insert(doctorLink)

        var accepted = doctorLinkDao.getByStatus("patient_1", DoctorLinkStatus.ACCEPTED)
        assertTrue(accepted.isEmpty())

        doctorLinkDao.update(doctorLink.copy(status = DoctorLinkStatus.ACCEPTED))
        accepted = doctorLinkDao.getByStatus("patient_1", DoctorLinkStatus.ACCEPTED)
        assertEquals(1, accepted.size)
        assertEquals("Dr. Alice", accepted[0].doctorName)
    }

    // --- MEDICINE PROPOSAL DAO TESTS ---

    @Test
    fun medicineProposalDao_insertAndQuery() = runTest {
        val proposal = MedicineProposal(
            id = "prop_1",
            patientId = "patient_1",
            doctorUid = "doc_uid_1",
            type = ProposalType.ADD,
            payloadJson = "{\"name\":\"Atorvastatin\",\"dosage\":\"10mg\"}",
            status = ProposalStatus.PENDING
        )
        medicineProposalDao.insert(proposal)

        val retrieved = medicineProposalDao.getById("prop_1")
        assertNotNull(retrieved)
        assertEquals(ProposalType.ADD, retrieved?.type)
        assertEquals(ProposalStatus.PENDING, retrieved?.status)

        medicineProposalDao.update(retrieved!!.copy(status = ProposalStatus.ACCEPTED, resolvedAt = 2000L))
        val updated = medicineProposalDao.getById("prop_1")
        assertEquals(ProposalStatus.ACCEPTED, updated?.status)
    }

    // --- NOTIFICATION OUTBOX DAO TESTS ---

    @Test
    fun notificationOutboxDao_enqueueAndMarkDelivered() = runTest {
        val outboxItem = NotificationOutbox(
            id = "outbox_1",
            type = NotifType.MISSED_DOSE,
            payloadJson = "{\"doseId\":\"dose_1\",\"medicineName\":\"Metformin\"}",
            delivered = false
        )
        notificationOutboxDao.insert(outboxItem)

        var undelivered = notificationOutboxDao.getUndelivered()
        assertEquals(1, undelivered.size)
        assertEquals(NotifType.MISSED_DOSE, undelivered[0].type)

        notificationOutboxDao.markDelivered("outbox_1")
        undelivered = notificationOutboxDao.getUndelivered()
        assertTrue(undelivered.isEmpty())
    }

    // --- TYPE CONVERTERS TESTS ---

    @Test
    fun converters_rejectReasonRoundTrip_andUnknownValuesHandledSafely() {
        val converters = Converters()
        for (reason in RejectReason.values()) {
            val stringVal = converters.fromRejectReason(reason)
            assertEquals(reason, converters.toRejectReason(stringVal))
        }
        assertNull(converters.toRejectReason(null))
        assertNull(converters.toRejectReason("FUTURE_UNKNOWN_REASON"))
    }
}
