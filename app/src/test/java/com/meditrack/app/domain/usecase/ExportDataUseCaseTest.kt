package com.meditrack.app.domain.usecase

import android.content.Context
import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoctorLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.db.dao.MedicineProposalDao
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.db.dao.VerificationAttemptDao
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalCoroutinesApi::class)
class ExportDataUseCaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val medicineDao: MedicineDao = mockk(relaxed = true)
    private val doseEventDao: DoseEventDao = mockk(relaxed = true)
    private val caregiverLinkDao: CaregiverLinkDao = mockk(relaxed = true)
    private val doctorLinkDao: DoctorLinkDao = mockk(relaxed = true)
    private val medicineProposalDao: MedicineProposalDao = mockk(relaxed = true)
    private val verificationAttemptDao: VerificationAttemptDao = mockk(relaxed = true)

    private lateinit var useCase: ExportDataUseCase
    private lateinit var testCacheDir: File
    private lateinit var testFilesDir: File

    private val testUser = User(
        id = "patient_export_1",
        name = "Bob Patient",
        email = "bob@example.com",
        phone = "+919876543210",
        role = UserRole.PATIENT
    )

    private val testMedicine = Medicine(
        id = "med_1",
        patientId = "patient_export_1",
        name = "Metformin",
        dosageText = "500mg",
        pillsPerDose = 1,
        frequencyType = FrequencyType.DAILY,
        timesOfDay = emptyList(),
        startDate = 1000L,
        initialStock = 30,
        currentStock = 28
    )

    private val testDose = DoseEvent(
        id = "dose_1",
        patientId = "patient_export_1",
        medicineId = "med_1",
        scheduledAt = 2000L,
        windowEndsAt = 2000L + 3600_000L,
        status = DoseStatus.TAKEN,
        takenAt = 2010L
    )

    private val testDoctorLink = DoctorLink(
        id = "dlink_1",
        patientId = "patient_export_1",
        doctorUid = "doc_99",
        doctorName = "Dr. Gupta",
        doctorContact = "gupta@meditrack.org",
        status = DoctorLinkStatus.ACCEPTED
    )

    private val testProposal = MedicineProposal(
        id = "prop_1",
        patientId = "patient_export_1",
        doctorUid = "doc_99",
        type = ProposalType.ADD,
        payloadJson = "{\"name\":\"Metformin\"}",
        status = ProposalStatus.ACCEPTED,
        revision = 1,
        clarificationNote = "Start 500mg daily"
    )

    @Before
    fun setUp() {
        testCacheDir = tempFolder.newFolder("cache")
        testFilesDir = tempFolder.newFolder("files")

        coEvery { context.cacheDir } returns testCacheDir
        coEvery { context.filesDir } returns testFilesDir

        coEvery { authRepository.getCurrentUser() } returns testUser
        coEvery { userDao.getUserById("patient_export_1") } returns testUser
        coEvery { medicineDao.getAllByPatientId("patient_export_1") } returns listOf(testMedicine)
        coEvery { doseEventDao.getAllByPatientId("patient_export_1") } returns listOf(testDose)
        coEvery { caregiverLinkDao.getAllByPatientId("patient_export_1") } returns emptyList()
        coEvery { doctorLinkDao.getAllByPatientId("patient_export_1") } returns listOf(testDoctorLink)
        coEvery { medicineProposalDao.getAllByPatientId("patient_export_1") } returns listOf(testProposal)
        coEvery { verificationAttemptDao.getAll() } returns emptyList()

        useCase = ExportDataUseCase(
            context = context,
            authRepository = authRepository,
            userDao = userDao,
            medicineDao = medicineDao,
            doseEventDao = doseEventDao,
            caregiverLinkDao = caregiverLinkDao,
            doctorLinkDao = doctorLinkDao,
            medicineProposalDao = medicineProposalDao,
            verificationAttemptDao = verificationAttemptDao
        )
    }

    @Test
    fun `invoke generates valid zip containing structured JSON with doctor connections and proposals`() = runTest {
        val result = useCase("patient_export_1")

        assertTrue(result.isSuccess)
        val export = result.getOrThrow()

        // Check counts
        assertEquals(1, export.totalMedicines)
        assertEquals(1, export.totalDoses)
        assertEquals(1, export.totalDoctorLinks)
        assertEquals(1, export.totalProposals)
        assertEquals(0, export.totalCaregiverLinks)

        // Check JSON content
        assertTrue(export.jsonContent.contains("\"patientId\":\"patient_export_1\""))
        assertTrue(export.jsonContent.contains("\"name\":\"Bob Patient\""))
        assertTrue(export.jsonContent.contains("\"name\":\"Metformin\""))
        assertTrue(export.jsonContent.contains("\"doctorName\":\"Dr. Gupta\""))
        assertTrue(export.jsonContent.contains("\"clarificationNote\":\"Start 500mg daily\""))

        // Verify ZIP file structure
        assertTrue(export.zipFile.exists())
        val zipFile = ZipFile(export.zipFile)
        val jsonEntry = zipFile.getEntry("export_data.json")
        assertNotNull(jsonEntry)

        zipFile.close()
    }

    @Test
    fun `invoke bundles proof photos from app-private proofs directory into zip`() = runTest {
        // Create a dummy proof photo in filesDir/proofs
        val proofsDir = File(testFilesDir, "proofs").apply { mkdirs() }
        val samplePhoto = File(proofsDir, "dose_1_proof.jpg")
        samplePhoto.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) // JPEG header bytes

        val result = useCase("patient_export_1")
        assertTrue(result.isSuccess)
        val export = result.getOrThrow()

        val zipFile = ZipFile(export.zipFile)
        val photoEntry = zipFile.getEntry("photos/dose_1_proof.jpg")
        assertNotNull(photoEntry)

        zipFile.close()
    }
}
