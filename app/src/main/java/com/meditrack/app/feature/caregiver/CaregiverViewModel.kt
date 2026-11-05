package com.meditrack.app.feature.caregiver

import android.content.Context
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
import org.json.JSONObject
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
    private val userPreferences: UserPreferences
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
            // In caregiver role: observe links where this user is the caregiver and link is ACCEPTED
            caregiverRepository.observeAll().collect { links ->
                val currentId = _uiState.value.currentUserId
                val patients = links.filter { it.caregiverUid == currentId && it.status == LinkStatus.ACCEPTED }
                _uiState.update { it.copy(linkedPatients = patients) }
            }
        }
    }

    fun sendInvitation(name: String, contact: String, relationship: String) {
        if (name.isBlank() || contact.isBlank()) {
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
            val payload = JSONObject().apply {
                put("patientName", patientName)
                put("caregiverName", link.caregiverName)
                put("caregiverUid", caregiverUid)
                put("linkId", linkId)
            }
            val outboxEvent = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.CAREGIVER_INVITE,
                payloadJson = payload.toString(),
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

    fun selectPatient(patientId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedPatientId = patientId, isLoading = true) }

            val today = LocalDate.now()
            val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

            // Filter doses and medicines specifically for this patient to prevent cross-patient data display
            val allDoses = doseRepository.observeBetween(startOfDay, endOfDay).firstOrNull() ?: emptyList()
            val dosesToday = allDoses.filter { it.patientId == null || it.patientId == patientId }

            val allMedicines = medicineRepository.observeActive().firstOrNull() ?: emptyList()
            val medicines = allMedicines.filter { it.patientId == null || it.patientId == patientId }

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
        val newRole = if (_uiState.value.currentUserRole == UserRole.PATIENT) UserRole.CAREGIVER else UserRole.PATIENT
        _uiState.update { it.copy(currentUserRole = newRole) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
