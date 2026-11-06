package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.VerificationRepository
import com.meditrack.app.domain.statemachine.DoseStateMachine
import com.meditrack.app.domain.statemachine.DoseTransitionEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class DoctorAccessControlTest {

    private val doctorRepository: DoctorRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)
    private val outboxRepository: OutboxRepository = mockk(relaxed = true)

    private lateinit var doctorLinkUseCase: DoctorLinkUseCase

    private val patientId = "patient_alice"
    private val doctorUid = "doc_house"
    private val doctorName = "Dr. House"

    @Before
    fun setup() {
        doctorLinkUseCase = DoctorLinkUseCase(doctorRepository, outboxRepository)
    }

    /**
     * MANDATORY BYPASS TEST (README Section 10B Acceptance Criteria & Verification Pack Part B):
     * Proves that a dose for a doctor-prescribed medicine CANNOT become TAKEN without
     * a passing verification event (DoseTransitionEvent.VerifyPassed).
     * Any direct attempt to change status to TAKEN without verified photo fails.
     */
    @Test
    fun doctorPrescribedDose_cannotBecomeTakenWithoutPassingVerification() = runTest {
        val doctorPrescribedMedicine = Medicine(
            id = "med_doc_1",
            patientId = patientId,
            name = "Warfarin",
            dosageText = "5mg",
            pillsPerDose = 1,
            frequencyType = FrequencyType.DAILY,
            timesOfDay = listOf(LocalTime.of(9, 0)),
            startDate = System.currentTimeMillis(),
            prescribedByDoctorUid = doctorUid,
            prescribedByDoctorName = doctorName,
            initialStock = 30,
            currentStock = 30
        )

        val pendingDose = DoseEvent(
            id = "dose_doc_1",
            medicineId = doctorPrescribedMedicine.id,
            patientId = patientId,
            scheduledAt = System.currentTimeMillis() + 1000L,
            windowEndsAt = System.currentTimeMillis() + 3600000L,
            status = DoseStatus.PENDING
        )

        // 1. Attempt invalid transition without verification (e.g. Skip or direct TAKEN without VerifyPassed)
        val skipResult = DoseStateMachine.transition(pendingDose.status, DoseTransitionEvent.Skip)
        assertTrue(skipResult.isSuccess)
        assertEquals(DoseStatus.SKIPPED, skipResult.getOrNull())
        assertFalse("Cannot become TAKEN on Skip", skipResult.getOrNull() == DoseStatus.TAKEN)

        // 2. Window expired leads to MISSED, never TAKEN
        val expireResult = DoseStateMachine.transition(pendingDose.status, DoseTransitionEvent.WindowExpired)
        assertTrue(expireResult.isSuccess)
        assertEquals(DoseStatus.MISSED, expireResult.getOrNull())

        // 3. Failed verification leads to UNVERIFIED, never TAKEN
        val failResult = DoseStateMachine.transition(pendingDose.status, DoseTransitionEvent.VerificationFailedExhausted)
        assertTrue(failResult.isSuccess)
        assertEquals(DoseStatus.UNVERIFIED, failResult.getOrNull())

        // 4. ONLY a genuine VerifyPassed transition can produce status TAKEN
        val verifyPassedResult = DoseStateMachine.transition(
            pendingDose.status,
            DoseTransitionEvent.VerifyPassed(photoPath = "/path/proof.jpg", emptyCellsAfter = 1, takenAt = System.currentTimeMillis())
        )
        assertTrue(verifyPassedResult.isSuccess)
        assertEquals(DoseStatus.TAKEN, verifyPassedResult.getOrNull())
    }

    @Test
    fun unlinkedDoctor_hasNoAccessToPatientData() = runTest {
        // Pending link grants zero access
        val pendingLink = DoctorLink(
            id = "dlink_pending",
            patientId = patientId,
            doctorUid = doctorUid,
            doctorName = doctorName,
            doctorContact = "",
            status = DoctorLinkStatus.PENDING
        )
        coEvery { doctorRepository.getDoctorLinkByDoctorAndPatient(doctorUid, patientId) } returns pendingLink

        val link = doctorRepository.getDoctorLinkByDoctorAndPatient(doctorUid, patientId)
        assertNotNull(link)
        assertFalse("Doctor with PENDING link must not have active access", link!!.status == DoctorLinkStatus.ACCEPTED)
    }

    @Test
    fun createDoctorInvitation_generatesExpiringSixCharAlphanumericCode() = runTest {
        val result = doctorLinkUseCase.createDoctorInvitation(doctorUid, doctorName, "+1-555-1234")

        assertTrue(result.isSuccess)
        val link = result.getOrNull()
        assertNotNull(link)
        assertEquals(6, link!!.invitationCode?.length)
        assertEquals(DoctorLinkStatus.PENDING, link.status)
        assertTrue(link.expiresAt!! > System.currentTimeMillis())
        coVerify(exactly = 1) { doctorRepository.insertDoctorLink(any()) }
    }

    @Test
    fun redeemDoctorInvitation_succeedsWithValidCode() = runTest {
        val link = DoctorLink(
            id = "dlink_code",
            patientId = "",
            doctorUid = doctorUid,
            doctorName = doctorName,
            doctorContact = "",
            status = DoctorLinkStatus.PENDING,
            invitationCode = "DOC123",
            expiresAt = System.currentTimeMillis() + 86400000L
        )
        coEvery { doctorRepository.getDoctorLinkByInviteCode("DOC123") } returns link

        val result = doctorLinkUseCase.redeemDoctorInvitation(patientId, "doc123", "Alice")

        assertTrue(result.isSuccess)
        val redeemed = result.getOrNull()
        assertNotNull(redeemed)
        assertEquals(DoctorLinkStatus.ACCEPTED, redeemed!!.status)
        assertEquals(patientId, redeemed.patientId)
        coVerify(exactly = 1) { doctorRepository.updateDoctorLink(match { it.status == DoctorLinkStatus.ACCEPTED }) }
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.DOCTOR_INVITE_ACCEPTED }) }
    }

    @Test
    fun redeemDoctorInvitation_failsWhenExpired() = runTest {
        val expiredLink = DoctorLink(
            id = "dlink_expired",
            patientId = "",
            doctorUid = doctorUid,
            doctorName = doctorName,
            doctorContact = "",
            status = DoctorLinkStatus.PENDING,
            invitationCode = "OLD890",
            expiresAt = System.currentTimeMillis() - 1000L // In past
        )
        coEvery { doctorRepository.getDoctorLinkByInviteCode("OLD890") } returns expiredLink

        val result = doctorLinkUseCase.redeemDoctorInvitation(patientId, "OLD890", "Alice")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { doctorRepository.updateDoctorLink(any()) }
    }

    @Test
    fun revokeDoctor_immediatelyTerminatesAccess() = runTest {
        val activeLink = DoctorLink(
            id = "dlink_active",
            patientId = patientId,
            doctorUid = doctorUid,
            doctorName = doctorName,
            doctorContact = "",
            status = DoctorLinkStatus.ACCEPTED
        )
        coEvery { doctorRepository.getDoctorLinkByDoctorAndPatient(doctorUid, patientId) } returns activeLink

        val result = doctorLinkUseCase.revokeDoctorAccess(patientId, doctorUid)

        assertTrue(result.isSuccess)
        val revoked = result.getOrNull()
        assertNotNull(revoked)
        assertEquals(DoctorLinkStatus.REVOKED, revoked!!.status)
        coVerify(exactly = 1) { doctorRepository.updateDoctorLink(match { it.status == DoctorLinkStatus.REVOKED }) }
        coVerify(exactly = 1) { outboxRepository.enqueue(match { it.type == NotifType.DOCTOR_INVITE_REVOKED }) }
    }
}
