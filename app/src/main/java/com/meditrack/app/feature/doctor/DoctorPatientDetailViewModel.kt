package com.meditrack.app.feature.doctor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.PrescriptionProposalPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.AdherenceCalculator
import com.meditrack.app.domain.usecase.CreateProposalUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class DoctorPatientDetailUiState(
    val patientId: String = "",
    val doctorUid: String = "",
    val doctorName: String = "",
    val medicines: List<Medicine> = emptyList(),
    val todayDoses: List<DoseEvent> = emptyList(),
    val proposals: List<MedicineProposal> = emptyList(),
    val todayAdherence: Double = 0.0,
    val weekAdherence: Double = 0.0,
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class DoctorPatientDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val doctorRepository: DoctorRepository,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val createProposalUseCase: CreateProposalUseCase,
    private val userPreferences: UserPreferences,
    private val userDao: UserDao,
    @ApplicationContext private val context: Context? = null
) : ViewModel() {

    private val patientId: String = savedStateHandle.get<String>("patientId") ?: ""

    private val _uiState = MutableStateFlow(DoctorPatientDetailUiState(patientId = patientId))
    val uiState: StateFlow<DoctorPatientDetailUiState> = _uiState.asStateFlow()

    private val isFirebaseAvailable: Boolean
        get() = try {
            if (context != null) FirebaseApp.getApps(context).isNotEmpty() else false
        } catch (_: Throwable) { false }

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val uid = userPreferences.currentUserId.firstOrNull() ?: ""
            val user = userDao.getUserById(uid)
            val docName = user?.name ?: "Doctor"
            _uiState.value = _uiState.value.copy(doctorUid = uid, doctorName = docName)

            // Verify active link locally or from Firebase
            var link = doctorRepository.getDoctorLinkByDoctorAndPatient(uid, patientId)
            if ((link == null || link.status != DoctorLinkStatus.ACCEPTED) && isFirebaseAvailable) {
                try {
                    val db = FirebaseFirestore.getInstance()
                    val remoteDoc = db.collection("doctorLinks").document("${patientId}_${uid}").get().await()
                    if (remoteDoc.exists() && remoteDoc.getString("status") == "ACCEPTED") {
                        val activeLink = DoctorLink(
                            id = remoteDoc.id,
                            patientId = patientId,
                            doctorUid = uid,
                            doctorName = remoteDoc.getString("doctorName") ?: docName,
                            doctorContact = remoteDoc.getString("doctorContact") ?: "",
                            status = DoctorLinkStatus.ACCEPTED,
                            createdAt = remoteDoc.getLong("createdAt") ?: System.currentTimeMillis(),
                            updatedAt = remoteDoc.getLong("updatedAt") ?: System.currentTimeMillis()
                        )
                        doctorRepository.insertDoctorLink(activeLink)
                        link = activeLink
                    }
                } catch (_: Exception) {}
            }

            if (link == null || link.status != DoctorLinkStatus.ACCEPTED) {
                _uiState.value = _uiState.value.copy(
                    error = "Access denied: Patient has not actively accepted this doctor connection."
                )
                return@launch
            }

            // If Firebase is available, fetch remote patient medicines
            if (isFirebaseAvailable) {
                try {
                    val db = FirebaseFirestore.getInstance()
                    val remoteSnap = db.collection("users").document(patientId).collection("medicines").get().await()
                    if (!remoteSnap.isEmpty) {
                        val remoteMeds = remoteSnap.documents.mapNotNull { doc ->
                            val id = doc.getString("id") ?: doc.id
                            val name = doc.getString("name") ?: return@mapNotNull null
                            val times = (doc.get("timesOfDay") as? List<*>)?.mapNotNull {
                                runCatching { LocalTime.parse(it.toString()) }.getOrNull()
                            } ?: emptyList()
                            val days = (doc.get("daysOfWeek") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                            val freq = doc.getString("frequencyType")?.let {
                                runCatching { FrequencyType.valueOf(it) }.getOrNull()
                            } ?: FrequencyType.DAILY
                            val food = doc.getString("foodRelation")?.let {
                                runCatching { FoodRelation.valueOf(it) }.getOrNull()
                            } ?: FoodRelation.ANY

                            Medicine(
                                id = id,
                                patientId = patientId,
                                name = name,
                                dosageText = doc.getString("dosageText") ?: "",
                                pillsPerDose = doc.getLong("pillsPerDose")?.toInt() ?: 1,
                                frequencyType = freq,
                                intervalHours = doc.getLong("intervalHours")?.toInt(),
                                timesOfDay = times,
                                daysOfWeek = days,
                                startDate = doc.getLong("startDate") ?: System.currentTimeMillis(),
                                endDate = doc.getLong("endDate"),
                                foodRelation = food,
                                instructions = doc.getString("instructions") ?: "",
                                reminderEnabled = doc.getBoolean("reminderEnabled") ?: true,
                                initialStock = doc.getLong("initialStock")?.toInt() ?: 10,
                                currentStock = doc.getLong("currentStock")?.toInt() ?: 10,
                                baselineEmptyCells = doc.getLong("baselineEmptyCells")?.toInt() ?: 0,
                                baselinePhotoPath = doc.getString("baselinePhotoPath"),
                                lowStockNotified = doc.getBoolean("lowStockNotified") ?: false,
                                lastRefillAt = doc.getLong("lastRefillAt"),
                                isDeleted = doc.getBoolean("isDeleted") ?: false,
                                prescribedByDoctorUid = doc.getString("prescribedByDoctorUid"),
                                prescribedByDoctorName = doc.getString("prescribedByDoctorName"),
                                stoppedByDoctor = doc.getBoolean("stoppedByDoctor") ?: false,
                                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                                updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
                            )
                        }
                        _uiState.value = _uiState.value.copy(medicines = remoteMeds.filter { !it.isDeleted })
                    }
                } catch (_: Exception) {}
            }

            // Observe patient medicines from Room (offline / fallback)
            medicineRepository.observeActive().collect { allMeds ->
                val patientMeds = allMeds.filter { it.patientId == patientId }
                if (patientMeds.isNotEmpty() || !isFirebaseAvailable) {
                    _uiState.value = _uiState.value.copy(medicines = patientMeds)
                }
            }
        }

        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val endOfDay = startOfDay + (24 * 60 * 60 * 1000L) - 1

            if (isFirebaseAvailable) {
                try {
                    val db = FirebaseFirestore.getInstance()
                    val remoteDosesSnap = db.collection("users").document(patientId).collection("doseEvents")
                        .whereGreaterThanOrEqualTo("scheduledAt", startOfDay)
                        .whereLessThanOrEqualTo("scheduledAt", endOfDay)
                        .get().await()
                    if (!remoteDosesSnap.isEmpty) {
                        val remoteDoses = remoteDosesSnap.documents.mapNotNull { doc ->
                            val id = doc.getString("id") ?: doc.id
                            val medId = doc.getString("medicineId") ?: return@mapNotNull null
                            val scheduledAt = doc.getLong("scheduledAt") ?: return@mapNotNull null
                            val statusStr = doc.getString("status") ?: DoseStatus.PENDING.name
                            val status = runCatching { DoseStatus.valueOf(statusStr) }.getOrDefault(DoseStatus.PENDING)
                            DoseEvent(
                                id = id,
                                medicineId = medId,
                                patientId = patientId,
                                scheduledAt = scheduledAt,
                                windowEndsAt = doc.getLong("windowEndsAt") ?: (scheduledAt + 3600_000L),
                                status = status,
                                takenAt = doc.getLong("takenAt"),
                                emptyCellsAfter = doc.getLong("emptyCellsAfter")?.toInt(),
                                lateEntry = doc.getBoolean("lateEntry") ?: false,
                                manualUnverified = doc.getBoolean("manualUnverified") ?: false,
                                nextReminderAt = doc.getLong("nextReminderAt"),
                                verifiedPhotoPath = doc.getString("verifiedPhotoPath"),
                                updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
                            )
                        }
                        val report = AdherenceCalculator.calculate(remoteDoses)
                        _uiState.value = _uiState.value.copy(
                            todayDoses = remoteDoses,
                            todayAdherence = report.percentage ?: 0.0
                        )
                    }
                } catch (_: Exception) {}
            }

            // Observe today's doses from Room
            doseRepository.observeBetween(startOfDay, endOfDay).collect { doses ->
                val patientDoses = doses.filter { it.patientId == patientId }
                if (patientDoses.isNotEmpty() || !isFirebaseAvailable) {
                    val report = AdherenceCalculator.calculate(patientDoses)
                    val todayAdherence = report.percentage ?: 0.0
                    _uiState.value = _uiState.value.copy(
                        todayDoses = patientDoses,
                        todayAdherence = todayAdherence
                    )
                }
            }
        }

        viewModelScope.launch {
            if (isFirebaseAvailable) {
                try {
                    val db = FirebaseFirestore.getInstance()
                    val remotePropsSnap = db.collection("medicineProposals")
                        .whereEqualTo("patientId", patientId)
                        .get().await()
                    for (doc in remotePropsSnap.documents) {
                        val id = doc.getString("id") ?: doc.id
                        val docUid = doc.getString("doctorUid") ?: continue
                        val typeStr = doc.getString("type") ?: ProposalType.ADD.name
                        val type = runCatching { ProposalType.valueOf(typeStr) }.getOrDefault(ProposalType.ADD)
                        val statusStr = doc.getString("status") ?: ProposalStatus.PENDING.name
                        val status = runCatching { ProposalStatus.valueOf(statusStr) }.getOrDefault(ProposalStatus.PENDING)
                        val payloadJson = doc.getString("payloadJson") ?: ""
                        val p = MedicineProposal(
                            id = id,
                            patientId = patientId,
                            doctorUid = docUid,
                            type = type,
                            medicineId = doc.getString("medicineId"),
                            payloadJson = payloadJson,
                            status = status,
                            revision = doc.getLong("revision")?.toInt() ?: 1,
                            clarificationNote = doc.getString("clarificationNote"),
                            createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                            resolvedAt = doc.getLong("resolvedAt")
                        )
                        doctorRepository.insertProposal(p)
                    }
                } catch (_: Exception) {}
            }

            // Observe proposals from Room
            doctorRepository.observeProposalsByPatientId(patientId).collect { props ->
                _uiState.value = _uiState.value.copy(proposals = props)
            }
        }
    }

    fun proposeStopMedicine(medicine: Medicine) {
        viewModelScope.launch {
            val payload = PrescriptionProposalPayload(
                medicineName = medicine.name,
                dosageText = medicine.dosageText,
                pillsPerDose = medicine.pillsPerDose,
                frequencyType = medicine.frequencyType,
                doctorName = _uiState.value.doctorName,
                reason = "Doctor proposed discontinuation of ${medicine.name}"
            )

            val result = createProposalUseCase(
                patientId = patientId,
                doctorUid = _uiState.value.doctorUid,
                doctorName = _uiState.value.doctorName,
                type = ProposalType.STOP,
                medicineId = medicine.id,
                payload = payload
            )

            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    message = "Stop proposal submitted to patient for approval."
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    error = ex.message ?: "Failed to submit proposal."
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}
