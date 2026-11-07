package com.meditrack.app.feature.caregiver

import com.meditrack.app.core.util.JsonPayload
import android.content.Context
import com.meditrack.app.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoseEvent
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class CaregiverUiState(
    val currentUserId: String = "",
    val currentUserRole: UserRole = UserRole.PATIENT,
    val connectedCaregivers: List<CaregiverLink> = emptyList(),
    val pendingInvitations: List<CaregiverLink> = emptyList(),
    val linkedPatients: List<CaregiverLink> = emptyList(),
    val selectedPatientId: String? = null,
    val selectedPatientDoses: List<DoseEvent> = emptyList(),
    val selectedPatientMedicines: List<Medicine> = emptyList(),
    val adherenceReport: AdherenceReport? = null,
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
            val userId = user?.id ?: userPreferences.currentUserId.firstOrNull() ?: "patient_default"
            val role = user?.role ?: UserRole.PATIENT

            _uiState.update { it.copy(currentUserId = userId, currentUserRole = role) }
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
            // Local mirror is only used when the cloud is not available (debug / fake mode).
            if (!caregiverCloud.isAvailable) {
                caregiverRepository.observeAll().collect { links ->
                    val currentId = _uiState.value.currentUserId
                    val patients = links.filter { it.caregiverUid == currentId && it.status == LinkStatus.ACCEPTED }
                    _uiState.update { it.copy(linkedPatients = patients) }
                }
            }
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
        if (clean.length < 8) {
            _uiState.update { it.copy(userMessage = "Enter the full invitation code.") }
            return
        }
        if (!caregiverCloud.isAvailable) {
            _uiState.update { it.copy(userMessage = "Sign in with an online account to use invitation codes.") }
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
            // Invitation-code flow: the patient never needs the caregiver's uid. The caregiver redeems the
            // code, and the acceptCaregiverInvite Cloud Function creates the link.
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
            // Ensure link ID format strictly conforms to {patientId}_{caregiverUid} per firestore.rules
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

            // Enqueue CAREGIVER_INVITE outbox notification with structured JSON and caregiverUid
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

            // Trigger sync and delivery
            try {
                SyncWorker.enqueue(context)
                OutboxDeliveryWorker.enqueue(context)
            } catch (_: Exception) {}

            _uiState.update { it.copy(userMessage = "Invitation sent to $name. Status: Pending.") }
        }
    }

    fun simulateCaregiverResponse(linkId: String, accept: Boolean) {
        if (!BuildConfig.DEBUG || caregiverCloud.isAvailable) return // debug-only fake mode helper; forbidden in real mode
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

    fun selectPatient(patientId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedPatientId = patientId, isLoading = true) }

            if (caregiverCloud.isAvailable) {
                // Real caregiver mode: read-only data of the LINKED patient from Firestore, last 7 days.
                val from = LocalDate.now().minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                caregiverCloud.fetchPatientData(patientId, from)
                    .onSuccess { (medicines, doses) ->
                        _uiState.update {
                            it.copy(
                                selectedPatientDoses = doses,
                                selectedPatientMedicines = medicines,
                                adherenceReport = AdherenceCalculator.calculate(doses),
                                isLoading = false
                            )
                        }
                    }
                    .onFailure {
                        // Revoked link or rules denial: show nothing rather than stale/other data.
                        _uiState.update {
                            it.copy(
                                selectedPatientDoses = emptyList(),
                                selectedPatientMedicines = emptyList(),
                                adherenceReport = null,
                                isLoading = false,
                                userMessage = "Cannot load this patient's data. The link may have been revoked."
                            )
                        }
                    }
                return@launch
            }

            val today = LocalDate.now()
            val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

            // Filter doses and medicines specifically for this patient to prevent cross-patient data display
            val allDoses = doseRepository.observeBetween(startOfDay, endOfDay).firstOrNull() ?: emptyList()
            val dosesToday = allDoses.filter { it.patientId == patientId }

            val allMedicines = medicineRepository.observeActive().firstOrNull() ?: emptyList()
            val medicines = allMedicines.filter { it.patientId == patientId }

            val adherence = AdherenceCalculator.calculate(dosesToday)

            _uiState.update {
                it.copy(
                    selectedPatientDoses = dosesToday,
                    selectedPatientMedicines = medicines,
                    adherenceReport = adherence,
                    isLoading = false
                )
            }
        }
    }

    fun toggleRole() {
        if (!BuildConfig.DEBUG) return // debug-only preview switch
        val newRole = if (_uiState.value.currentUserRole == UserRole.PATIENT) UserRole.CAREGIVER else UserRole.PATIENT
        _uiState.update { it.copy(currentUserRole = newRole) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
