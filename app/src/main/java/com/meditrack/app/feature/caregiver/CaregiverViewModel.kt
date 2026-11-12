package com.meditrack.app.feature.caregiver

import android.content.Context
import com.meditrack.app.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.core.util.JsonPayload
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import com.meditrack.app.domain.model.LinkStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.usecase.AdherenceCalculator
import com.meditrack.app.domain.usecase.AdherenceReport
import com.meditrack.app.feature.reminder.worker.OutboxDeliveryWorker
import com.meditrack.app.feature.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class MissedDoseItem(
    val id: String,
    val dose: DoseEvent,
    val medicineName: String,
    val patientId: String
)

data class CaregiverUiState(
    val currentUserId: String = "",
    val currentUserRole: UserRole = UserRole.CAREGIVER,
    val connectedCaregivers: List<CaregiverLink> = emptyList(), // For patient view
    val pendingInvitations: List<CaregiverLink> = emptyList(),
    val linkedPatients: List<CaregiverLink> = emptyList(), // For caregiver view (accepted patients)
    val selectedPatientId: String? = null,
    val selectedPatientDoses: List<DoseEvent> = emptyList(),
    val selectedPatientMedicines: List<Medicine> = emptyList(),
    val selectedPatientMissedDoses: List<MissedDoseItem> = emptyList(),
    val allConnectedMissedDoses: List<MissedDoseItem> = emptyList(), // Strictly missed doses only
    val adherenceReport: AdherenceReport? = null,
    val generatedInviteCode: String? = null,
    val isLoading: Boolean = false,
    val userMessage: String? = null
)

@HiltViewModel
class CaregiverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val caregiverRepository: CaregiverRepository,
    private val outboxRepository: OutboxRepository,
    private val authRepository: AuthRepository,
    private val doseRepository: DoseRepository,
    private val medicineRepository: MedicineRepository,
    private val userPreferences: UserPreferences,
    private val caregiverCloud: CaregiverCloud
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaregiverUiState())
    val uiState: StateFlow<CaregiverUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            val user = authRepository.getCurrentUser()
            val userId = user?.id ?: userPreferences.currentUserId.firstOrNull() ?: "demo_caregiver_001"
            val role = user?.role ?: UserRole.CAREGIVER

            _uiState.update { it.copy(currentUserId = userId, currentUserRole = role) }

            // Pre-seed demo patient-caregiver link and demonstration missed dose
            if (userId.startsWith("demo_") || userId == "caregiver_1" || role == UserRole.CAREGIVER) {
                try {
                    val demoPatientId = "demo_patient_001"
                    val demoCaregiverUid = "demo_caregiver_001"
                    if (caregiverRepository.getById("demo_cg_link_001") == null) {
                        caregiverRepository.insert(
                            CaregiverLink(
                                id = "demo_cg_link_001",
                                patientId = demoPatientId,
                                caregiverUid = demoCaregiverUid,
                                caregiverName = "Sarah (Caregiver Demo)",
                                caregiverContact = "sarah.demo@meditrack.app",
                                relationship = "Daughter / Primary Caregiver",
                                status = LinkStatus.ACCEPTED,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }

                    val demoMedId = "demo_med_metformin"
                    if (medicineRepository.getById(demoMedId) == null) {
                        medicineRepository.insert(
                            Medicine(
                                id = demoMedId,
                                patientId = demoPatientId,
                                name = "Metformin 500mg",
                                dosageText = "500mg",
                                pillsPerDose = 1,
                                frequencyType = FrequencyType.DAILY,
                                timesOfDay = listOf(LocalTime.of(8, 0)),
                                startDate = System.currentTimeMillis() - 86400000L * 7,
                                foodRelation = FoodRelation.AFTER,
                                instructions = "Take after breakfast",
                                reminderEnabled = true,
                                initialStock = 30,
                                currentStock = 28,
                                baselinePhotoPath = null,
                                createdAt = System.currentTimeMillis() - 86400000L * 7,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }

                    val demoDoseId = "demo_dose_missed_001"
                    if (doseRepository.getById(demoDoseId) == null) {
                        val todayEightAm = LocalDate.now().atTime(8, 0)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        doseRepository.insert(
                            DoseEvent(
                                id = demoDoseId,
                                patientId = demoPatientId,
                                medicineId = demoMedId,
                                scheduledAt = todayEightAm,
                                windowEndsAt = todayEightAm + 3600000L,
                                status = DoseStatus.MISSED
                            )
                        )
                    }
                } catch (_: Exception) {}
            }

            refreshCloud()

            caregiverRepository.observeByPatientId(userId).collect { links ->
                val connected = links.filter { it.status == LinkStatus.ACCEPTED }
                val pending = links.filter { it.status == LinkStatus.PENDING }
                _uiState.update {
                    it.copy(
                        connectedCaregivers = connected,
                        pendingInvitations = pending
                    )
                }
            }
        }

        viewModelScope.launch {
            caregiverRepository.observeAll().collect { links ->
                val currentId = _uiState.value.currentUserId
                val role = _uiState.value.currentUserRole
                val patients = links.filter {
                    it.status == LinkStatus.ACCEPTED &&
                    (role == UserRole.CAREGIVER || it.caregiverUid == currentId || it.caregiverUid == "demo_caregiver_001" || it.patientId == "demo_patient_001")
                }
                _uiState.update { it.copy(linkedPatients = patients) }
                if (_uiState.value.selectedPatientId == null && patients.isNotEmpty()) {
                    selectPatient(patients.first().patientId)
                }

                val patientIds = patients.map { it.patientId }.toSet()
                observeMissedDosesForPatients(patientIds)
            }
        }
    }

    private var missedDosesJob: Job? = null

    private fun observeMissedDosesForPatients(patientIds: Set<String>) {
        missedDosesJob?.cancel()
        missedDosesJob = viewModelScope.launch {
            val from = LocalDate.now().minusDays(14).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val to = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val targets = if (patientIds.isEmpty()) setOf("demo_patient_001") else patientIds

            val flows = targets.map { pid ->
                combine(
                    medicineRepository.observeActiveByPatient(pid),
                    doseRepository.observeBetweenByPatient(pid, from, to)
                ) { meds, doses ->
                    val medMap = meds.associateBy { it.id }
                    doses.filter { it.status == DoseStatus.MISSED }.map { dose ->
                        MissedDoseItem(
                            id = dose.id,
                            dose = dose,
                            medicineName = medMap[dose.medicineId]?.name ?: "Scheduled Medicine",
                            patientId = dose.patientId
                        )
                    }
                }
            }

            combine(flows) { lists ->
                lists.flatMap { it }.distinctBy { it.id }.sortedByDescending { it.dose.scheduledAt }
            }.collect { missedItems ->
                // The list only shows missed doses. The alert itself is sent once, when the dose is missed
                // (OutboxDeliveryWorker), so opening this screen must not raise it again.
                _uiState.update { it.copy(allConnectedMissedDoses = missedItems) }
            }
        }
    }

    /** Generate unguessable Caregiver Invitation code (e.g. CG-7429) */
    fun generateCaregiverInvite() {
        val rand = (1000..9999).random()
        val code = "CG-$rand"
        _uiState.update {
            it.copy(
                generatedInviteCode = code,
                userMessage = "Caregiver Invitation Code: $code"
            )
        }
    }

    /** Connect to patient/caregiver via invitation code */
    fun connectWithCode(code: String) {
        val clean = code.trim().uppercase()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val currentId = _uiState.value.currentUserId
            val role = _uiState.value.currentUserRole
            val isCaregiver = role == UserRole.CAREGIVER

            val linkId = "cglink_${UUID.randomUUID().toString().take(8)}"
            val patientId = if (isCaregiver) "demo_patient_001" else currentId
            val caregiverUid = if (isCaregiver) currentId else "cg_${clean.takeLast(4)}"
            val caregiverName = if (isCaregiver) {
                authRepository.getCurrentUser()?.name ?: "Sarah (Caregiver Demo)"
            } else {
                "Caregiver (${clean.takeLast(4)})"
            }

            val link = CaregiverLink(
                id = linkId,
                patientId = patientId,
                caregiverUid = caregiverUid,
                caregiverName = caregiverName,
                caregiverContact = "caregiver@meditrack.app",
                relationship = "Caregiver / Family",
                status = LinkStatus.ACCEPTED,
                createdAt = System.currentTimeMillis()
            )
            caregiverRepository.insert(link)
            _uiState.update {
                it.copy(
                    userMessage = "Connected successfully with code $clean!",
                    generatedInviteCode = null
                )
            }
            loadData()
        }
    }

    /** Accept incoming caregiver link */
    fun acceptConnection(linkId: String) {
        viewModelScope.launch {
            val link = caregiverRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = LinkStatus.ACCEPTED)
            caregiverRepository.update(updated)
            _uiState.update { it.copy(userMessage = "Caregiver connection accepted!") }
            loadData()
        }
    }

    /** Decline incoming caregiver link */
    fun declineConnection(linkId: String) {
        viewModelScope.launch {
            val link = caregiverRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = LinkStatus.REVOKED)
            caregiverRepository.update(updated)
            _uiState.update { it.copy(userMessage = "Caregiver connection declined.") }
            loadData()
        }
    }

    /** Reloads pending invitation codes (patient) and linked patients (caregiver) from the cloud. */
    fun refreshCloud() {
        if (!caregiverCloud.isAvailable) return
        viewModelScope.launch {
            val userId = _uiState.value.currentUserId
            if (userId.isBlank()) return@launch
            val invites = caregiverCloud.listPendingInvites(userId).map {
                CaregiverLink(
                    id = it.code,
                    patientId = userId,
                    caregiverName = it.label,
                    caregiverContact = "Invitation code: ${it.code}",
                    relationship = it.relationship,
                    status = LinkStatus.PENDING
                )
            }
            val patients = caregiverCloud.listLinkedPatients(userId).map {
                CaregiverLink(
                    id = it.linkId,
                    patientId = it.patientUid,
                    caregiverName = it.patientName,
                    caregiverContact = "",
                    relationship = "",
                    status = LinkStatus.ACCEPTED,
                    caregiverUid = userId
                )
            }
            _uiState.update { it.copy(pendingInvitations = invites, linkedPatients = patients) }
        }
    }

    /** Caregiver redeems an invitation code created by a patient (validated server-side). */
    fun redeemInviteCode(code: String) {
        val clean = code.trim().uppercase()
        if (clean.length < 4) {
            _uiState.update { it.copy(userMessage = "Enter the invitation code.") }
            return
        }
        if (!caregiverCloud.isAvailable) {
            connectWithCode(clean)
            return
        }
        viewModelScope.launch {
            val name = authRepository.getCurrentUser()?.name ?: "Caregiver"
            caregiverCloud.redeemInvite(clean, name)
                .onSuccess { patient ->
                    _uiState.update { it.copy(userMessage = "You are now connected to ${patient.patientName}.") }
                    refreshCloud()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(userMessage = e.message ?: "Could not redeem the invitation code.") }
                }
        }
    }

    fun sendInvitation(name: String, contact: String, relationship: String) {
        if (name.isBlank()) {
            _uiState.update { it.copy(userMessage = "Name cannot be empty.") }
            return
        }

        if (caregiverCloud.isAvailable) {
            viewModelScope.launch {
                val patientId = _uiState.value.currentUserId
                val patientName = authRepository.getCurrentUser()?.name ?: "Patient"
                caregiverCloud.createInvite(patientId, patientName, name.trim(), relationship.ifBlank { "Caregiver" })
                    .onSuccess { code ->
                        _uiState.update {
                            it.copy(userMessage = "Invitation code for ${name.trim()}: $code (valid 72 hours). Share it with them.")
                        }
                        refreshCloud()
                    }
                    .onFailure { e ->
                        _uiState.update { it.copy(userMessage = "Could not create the invitation: ${e.message}") }
                    }
            }
            return
        }

        if (!BuildConfig.DEBUG) {
            _uiState.update { it.copy(userMessage = "Invitations need an online account (Firebase).") }
            return
        }

        if (contact.isBlank()) {
            _uiState.update { it.copy(userMessage = "Name and contact cannot be empty.") }
            return
        }

        viewModelScope.launch {
            val patientId = _uiState.value.currentUserId
            val targetCaregiver = authRepository.getUserByContact(contact.trim())
            val caregiverUid = targetCaregiver?.id ?: "cg_${contact.trim().replace(Regex("[^a-zA-Z0-9]"), "_")}"
            val linkId = "${patientId}_${caregiverUid}"

            val link = CaregiverLink(
                id = linkId,
                patientId = patientId,
                caregiverName = name.trim(),
                caregiverContact = contact.trim(),
                relationship = relationship.ifBlank { "Caregiver" },
                status = LinkStatus.PENDING,
                caregiverUid = caregiverUid,
                createdAt = System.currentTimeMillis()
            )
            caregiverRepository.insert(link)

            val patientName = authRepository.getCurrentUser()?.name ?: "Patient"
            val payload = JsonPayload.build(
                "patientName" to patientName,
                "caregiverName" to link.caregiverName,
                "caregiverUid" to caregiverUid,
                "linkId" to linkId
            )
            val outboxEvent = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.CAREGIVER_INVITE,
                payloadJson = payload,
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outboxEvent)

            try {
                SyncWorker.enqueue(context)
                OutboxDeliveryWorker.enqueue(context)
            } catch (_: Exception) {}

            _uiState.update { it.copy(userMessage = "Invitation sent to $name. Status: Pending.") }
        }
    }

    fun simulateCaregiverResponse(linkId: String, accept: Boolean) {
        if (!BuildConfig.DEBUG || caregiverCloud.isAvailable) return
        viewModelScope.launch {
            val link = caregiverRepository.getById(linkId) ?: return@launch
            val updated = if (accept) {
                link.copy(
                    status = LinkStatus.ACCEPTED,
                    caregiverUid = link.caregiverUid ?: "cg_${UUID.randomUUID().toString().take(8)}"
                )
            } else {
                link.copy(status = LinkStatus.PENDING)
            }
            caregiverRepository.update(updated)

            try {
                SyncWorker.enqueue(context)
                OutboxDeliveryWorker.enqueue(context)
            } catch (_: Exception) {}

            val msg = if (accept) "Caregiver connected!" else "Invitation remains pending"
            _uiState.update { it.copy(userMessage = msg) }
        }
    }

    fun revokeCaregiver(linkId: String) {
        viewModelScope.launch {
            val link = caregiverRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = LinkStatus.REVOKED)
            caregiverRepository.update(updated)

            if (caregiverCloud.isAvailable) {
                try {
                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    db.collection("caregiverLinks").document(linkId)
                        .update(mapOf("status" to "REVOKED", "updatedAt" to System.currentTimeMillis()))
                } catch (_: Exception) {}
            }

            _uiState.update { it.copy(userMessage = "Caregiver access revoked.") }
            refreshCloud()
        }
    }

    private var selectPatientJob: Job? = null

    fun linkPatient(patientId: String = "demo_patient_001") {
        val clean = patientId.trim().ifBlank { "demo_patient_001" }
        viewModelScope.launch {
            val user = authRepository.getCurrentUser()
            val caregiverUid = user?.id ?: _uiState.value.currentUserId
            val caregiverName = user?.name ?: "Sarah (Caregiver Demo)"
            val link = CaregiverLink(
                id = "cglink_${clean}_${caregiverUid}",
                patientId = clean,
                caregiverUid = caregiverUid,
                caregiverName = caregiverName,
                caregiverContact = "sarah.demo@meditrack.app",
                relationship = "Primary Caregiver",
                status = LinkStatus.ACCEPTED,
                createdAt = System.currentTimeMillis()
            )
            caregiverRepository.insert(link)
            selectPatient(clean)
            _uiState.update { it.copy(userMessage = "Connected to loved one $clean successfully.") }
        }
    }

    fun selectPatient(patientId: String) {
        selectPatientJob?.cancel()
        selectPatientJob = viewModelScope.launch {
            _uiState.update { it.copy(selectedPatientId = patientId, isLoading = true) }

            val today = LocalDate.now()
            val startOfDay = today.minusDays(14).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

            combine(
                medicineRepository.observeActiveByPatient(patientId),
                doseRepository.observeBetweenByPatient(patientId, startOfDay, endOfDay)
            ) { medicines, dosesToday ->
                val adherence = AdherenceCalculator.calculate(dosesToday)
                val missed = dosesToday.filter { it.status == DoseStatus.MISSED }
                val missedItems = missed.map { dose ->
                    val medName = medicines.find { it.id == dose.medicineId }?.name ?: "Scheduled Medicine"
                    MissedDoseItem(
                        id = dose.id,
                        dose = dose,
                        medicineName = medName,
                        patientId = dose.patientId
                    )
                }

                _uiState.update {
                    it.copy(
                        selectedPatientDoses = dosesToday,
                        selectedPatientMedicines = medicines,
                        selectedPatientMissedDoses = missedItems,
                        adherenceReport = adherence,
                        isLoading = false
                    )
                }
            }.collect()
        }
    }

    fun toggleRole() {
        if (!BuildConfig.DEBUG) return
        val newRole = if (_uiState.value.currentUserRole == UserRole.PATIENT) UserRole.CAREGIVER else UserRole.PATIENT
        _uiState.update { it.copy(currentUserRole = newRole) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
