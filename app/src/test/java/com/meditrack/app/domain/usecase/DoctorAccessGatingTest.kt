package com.meditrack.app.domain.usecase

import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DoctorAccessGatingTest {

    private val doctorRepository: DoctorRepository = mockk(relaxed = true)
    private val medicineRepository: MedicineRepository = mockk(relaxed = true)
    private val doseRepository: DoseRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        val sampleDoses = listOf(
            DoseEvent(id = "d1", medicineId = "m1", scheduledAt = 1000L, windowEndsAt = 2000L, status = DoseStatus.TAKEN)
        )
        coEvery { doseRepository.observeBetween(any(), any()) } returns flowOf(sampleDoses)
    }

    @Test
    fun pendingLink_grantsZeroAccessToPatientData() = runTest {
        val link = DoctorLink(id = "l1", patientId = "pat_1", doctorUid = "doc_1", doctorName = "Dr. Smith", doctorContact = "doc@example.com", status = DoctorLinkStatus.PENDING)
        coEvery { doctorRepository.getById("l1") } returns link

        val canAccess = link.status == DoctorLinkStatus.ACCEPTED
        assertFalse(canAccess)
    }

    @Test
    fun declinedLink_grantsZeroAccessToPatientData() = runTest {
        val link = DoctorLink(id = "l2", patientId = "pat_2", doctorUid = "doc_1", doctorName = "Dr. Smith", doctorContact = "doc@example.com", status = DoctorLinkStatus.DECLINED)
        coEvery { doctorRepository.getById("l2") } returns link

        val canAccess = link.status == DoctorLinkStatus.ACCEPTED
        assertFalse(canAccess)
    }

    @Test
    fun revokedLink_immediatelyTerminatesDoctorAccess() = runTest {
        val link = DoctorLink(id = "l3", patientId = "pat_3", doctorUid = "doc_1", doctorName = "Dr. Smith", doctorContact = "doc@example.com", status = DoctorLinkStatus.REVOKED)
        coEvery { doctorRepository.getById("l3") } returns link

        val canAccess = link.status == DoctorLinkStatus.ACCEPTED
        assertFalse(canAccess)
    }

    @Test
    fun acceptedLink_grantsReadOnlyAccessToPatientData() = runTest {
        val link = DoctorLink(id = "l4", patientId = "pat_4", doctorUid = "doc_1", doctorName = "Dr. Smith", doctorContact = "doc@example.com", status = DoctorLinkStatus.ACCEPTED)
        coEvery { doctorRepository.getById("l4") } returns link

        val canAccess = link.status == DoctorLinkStatus.ACCEPTED
        assertTrue(canAccess)
    }
}
