package com.meditrack.app.domain.usecase

import android.content.Context
import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.data.db.dao.CaregiverLinkDao
import com.meditrack.app.data.db.dao.DoctorLinkDao
import com.meditrack.app.data.db.dao.DoseEventDao
import com.meditrack.app.data.db.dao.MedicineDao
import com.meditrack.app.data.db.dao.MedicineProposalDao
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.db.dao.VerificationAttemptDao
import com.meditrack.app.domain.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ExportResult(
    val zipFile: File,
    val jsonContent: String,
    val totalMedicines: Int,
    val totalDoses: Int,
    val totalProposals: Int,
    val totalDoctorLinks: Int,
    val totalCaregiverLinks: Int
)

@Singleton
class ExportDataUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userDao: UserDao,
    private val medicineDao: MedicineDao,
    private val doseEventDao: DoseEventDao,
    private val caregiverLinkDao: CaregiverLinkDao,
    private val doctorLinkDao: DoctorLinkDao,
    private val medicineProposalDao: MedicineProposalDao,
    private val verificationAttemptDao: VerificationAttemptDao
) {

    suspend operator fun invoke(targetPatientId: String? = null): Result<ExportResult> = withContext(Dispatchers.IO) {
        try {
            val currentUser = authRepository.getCurrentUser()
            val patientId = targetPatientId ?: currentUser?.id ?: "unknown_patient"

            val profile = userDao.getUserById(patientId) ?: currentUser
            val medicines = medicineDao.getAllByPatientId(patientId)
            val doses = doseEventDao.getAllByPatientId(patientId)
            val caregiverLinks = caregiverLinkDao.getAllByPatientId(patientId)
            val doctorLinks = doctorLinkDao.getAllByPatientId(patientId)
            val proposals = medicineProposalDao.getAllByPatientId(patientId)
            val verificationAttempts = verificationAttemptDao.getAll()

            // Construct structured JSON export
            val sb = StringBuilder("{")
            sb.append("\"exportedAt\":").append(System.currentTimeMillis()).append(",")
            sb.append("\"patientId\":\"").append(JsonPayload.escape(patientId)).append("\",")

            // User profile
            sb.append("\"profile\":{")
            sb.append("\"name\":\"").append(JsonPayload.escape(profile?.name ?: "User")).append("\",")
            sb.append("\"role\":\"").append(profile?.role?.name ?: "PATIENT").append("\",")
            sb.append("\"email\":\"").append(JsonPayload.escape(profile?.email ?: "")).append("\",")
            sb.append("\"phone\":\"").append(JsonPayload.escape(profile?.phone ?: "")).append("\"")
            sb.append("},")

            // Medicines
            sb.append("\"medicines\":[")
            medicines.forEachIndexed { i, m ->
                if (i > 0) sb.append(",")
                sb.append("{")
                sb.append("\"id\":\"").append(JsonPayload.escape(m.id)).append("\",")
                sb.append("\"name\":\"").append(JsonPayload.escape(m.name)).append("\",")
                sb.append("\"dosageText\":\"").append(JsonPayload.escape(m.dosageText)).append("\",")
                sb.append("\"pillsPerDose\":").append(m.pillsPerDose).append(",")
                sb.append("\"currentStock\":").append(m.currentStock).append(",")
                sb.append("\"initialStock\":").append(m.initialStock).append(",")
                sb.append("\"frequencyType\":\"").append(m.frequencyType.name).append("\",")
                sb.append("\"instructions\":\"").append(JsonPayload.escape(m.instructions)).append("\",")
                sb.append("\"stoppedByDoctor\":").append(m.stoppedByDoctor)
                sb.append("}")
            }
            sb.append("],")

            // Dose history
            sb.append("\"doses\":[")
            doses.forEachIndexed { i, d ->
                if (i > 0) sb.append(",")
                sb.append("{")
                sb.append("\"id\":\"").append(JsonPayload.escape(d.id)).append("\",")
                sb.append("\"medicineId\":\"").append(JsonPayload.escape(d.medicineId)).append("\",")
                sb.append("\"scheduledAt\":").append(d.scheduledAt).append(",")
                sb.append("\"status\":\"").append(d.status.name).append("\",")
                sb.append("\"takenAt\":").append(d.takenAt ?: 0).append(",")
                sb.append("\"lateEntry\":").append(d.lateEntry).append(",")
                sb.append("\"manualUnverified\":").append(d.manualUnverified)
                sb.append("}")
            }
            sb.append("],")

            // Doctor links & connections
            sb.append("\"doctorLinks\":[")
            doctorLinks.forEachIndexed { i, dl ->
                if (i > 0) sb.append(",")
                sb.append("{")
                sb.append("\"id\":\"").append(JsonPayload.escape(dl.id)).append("\",")
                sb.append("\"doctorUid\":\"").append(JsonPayload.escape(dl.doctorUid ?: "")).append("\",")
                sb.append("\"doctorName\":\"").append(JsonPayload.escape(dl.doctorName)).append("\",")
                sb.append("\"doctorContact\":\"").append(JsonPayload.escape(dl.doctorContact)).append("\",")
                sb.append("\"status\":\"").append(dl.status.name).append("\",")
                sb.append("\"createdAt\":").append(dl.createdAt)
                sb.append("}")
            }
            sb.append("],")

            // Caregiver links
            sb.append("\"caregiverLinks\":[")
            caregiverLinks.forEachIndexed { i, cl ->
                if (i > 0) sb.append(",")
                sb.append("{")
                sb.append("\"id\":\"").append(JsonPayload.escape(cl.id)).append("\",")
                sb.append("\"caregiverName\":\"").append(JsonPayload.escape(cl.caregiverName)).append("\",")
                sb.append("\"caregiverContact\":\"").append(JsonPayload.escape(cl.caregiverContact)).append("\",")
                sb.append("\"relationship\":\"").append(JsonPayload.escape(cl.relationship)).append("\",")
                sb.append("\"status\":\"").append(cl.status.name).append("\"")
                sb.append("}")
            }
            sb.append("],")

            // Prescription proposals & decision audit logs
            sb.append("\"prescriptionProposals\":[")
            proposals.forEachIndexed { i, p ->
                if (i > 0) sb.append(",")
                sb.append("{")
                sb.append("\"id\":\"").append(JsonPayload.escape(p.id)).append("\",")
                sb.append("\"doctorUid\":\"").append(JsonPayload.escape(p.doctorUid)).append("\",")
                sb.append("\"type\":\"").append(p.type.name).append("\",")
                sb.append("\"status\":\"").append(p.status.name).append("\",")
                sb.append("\"revision\":").append(p.revision).append(",")
                sb.append("\"clarificationNote\":\"").append(JsonPayload.escape(p.clarificationNote ?: "")).append("\",")
                sb.append("\"createdAt\":").append(p.createdAt).append(",")
                sb.append("\"resolvedAt\":").append(p.resolvedAt ?: 0)
                sb.append("}")
            }
            sb.append("],")

            // Verification attempts audit history
            sb.append("\"verificationAttempts\":[")
            verificationAttempts.forEachIndexed { i, va ->
                if (i > 0) sb.append(",")
                sb.append("{")
                sb.append("\"id\":\"").append(JsonPayload.escape(va.id)).append("\",")
                sb.append("\"doseEventId\":\"").append(JsonPayload.escape(va.doseEventId)).append("\",")
                sb.append("\"result\":\"").append(va.result.name).append("\",")
                sb.append("\"rejectReason\":\"").append(va.rejectReason?.name ?: "").append("\",")
                sb.append("\"createdAt\":").append(va.createdAt)
                sb.append("}")
            }
            sb.append("]")

            sb.append("}")
            val jsonContent = sb.toString()

            // Package into zip file in cacheDir
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportDir, "meditrack_export_${patientId}_${System.currentTimeMillis()}.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // Add export_data.json
                val jsonEntry = ZipEntry("export_data.json")
                zos.putNextEntry(jsonEntry)
                zos.write(jsonContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Collect and bundle proof photos from app-private filesDir
                val photosDir = File(context.filesDir, "proofs")
                if (photosDir.exists() && photosDir.isDirectory) {
                    photosDir.listFiles()?.filter { it.isFile }?.forEach { photoFile ->
                        try {
                            val photoEntry = ZipEntry("photos/${photoFile.name}")
                            zos.putNextEntry(photoEntry)
                            photoFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        } catch (_: Exception) {}
                    }
                }
            }

            Result.success(
                ExportResult(
                    zipFile = zipFile,
                    jsonContent = jsonContent,
                    totalMedicines = medicines.size,
                    totalDoses = doses.size,
                    totalProposals = proposals.size,
                    totalDoctorLinks = doctorLinks.size,
                    totalCaregiverLinks = caregiverLinks.size
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
