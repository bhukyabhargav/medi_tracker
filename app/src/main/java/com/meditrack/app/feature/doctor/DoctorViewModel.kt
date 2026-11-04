package com.meditrack.app.feature.doctor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.NotifType
import com.meditrack.app.domain.model.NotificationOutbox
import com.meditrack.app.domain.model.PrescriptionPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.repository.OutboxRepository
import com.meditrack.app.domain.repository.ProposalRepository
import com.meditrack.app.domain.usecase.AcceptProposalUseCase
import com.meditrack.app.domain.usecase.AdherenceCalculator
import com.meditrack.app.domain.usecase.AdherenceReport
import com.meditrack.app.domain.usecase.DeclineProposalUseCase
import com.meditrack.app.domain.usecase.RequestClarificationUseCase
import com.meditrack.app.domain.usecase.ReviseProposalUseCase
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

data class DoctorUiState(
    val currentUserId: String = "",
    val currentUserRole: UserRole = UserRole.DOCTOR,
    val acceptedPatients: List<DoctorLink> = emptyList(),
    val incomingDoctorLinks: List<DoctorLink> = emptyList(), // For patient view
    val patientProposals: List<MedicineProposal> = emptyList(),
    val selectedPatientId: String? = null,
    val selectedPatientMedicines: List<Medicine> = emptyList(),
    val selectedPatientDoses: List<DoseEvent> = emptyList(),
    val selectedPatientAdherence: AdherenceReport? = null,
    val generatedInviteCode: String? = null,
    val isLoading: Boolean = false,
    val userMessage: String? = null
)

@HiltViewModel
class DoctorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val doctorRepository: DoctorRepository,
    private val proposalRepository: ProposalRepository,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val outboxRepository: OutboxRepository,
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences,
    private val acceptProposalUseCase: AcceptProposalUseCase,
    private val declineProposalUseCase: DeclineProposalUseCase,
    private val requestClarificationUseCase: RequestClarificationUseCase,
    private val reviseProposalUseCase: ReviseProposalUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DoctorUiState())
    val uiState: StateFlow<DoctorUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            val user = authRepository.getCurrentUser()
            val userId = user?.id ?: userPreferences.currentUserId.firstOrNull() ?: "doctor_1"
            val role = user?.role ?: UserRole.DOCTOR

            _uiState.update { it.copy(currentUserId = userId, currentUserRole = role) }

            // Observe accepted patients for doctor
            doctorRepository.observeAcceptedForDoctor(userId).collect { links ->
                _uiState.update { it.copy(acceptedPatients = links) }
            }
        }

        viewModelScope.launch {
            val userId = _uiState.value.currentUserId
            // Observe incoming doctor links for patient
            doctorRepository.observeByPatientId(userId).collect { links ->
                _uiState.update { it.copy(incomingDoctorLinks = links) }
            }
        }

        viewModelScope.launch {
            proposalRepository.observeAll().collect { proposals ->
                _uiState.update { it.copy(patientProposals = proposals) }
            }
        }
    }

    fun generateDoctorInvite(patientContact: String? = null) {
        val code = "DOC-${(1000..9999).random()}"
        _uiState.update {
            it.copy(
                generatedInviteCode = code,
                userMessage = "Invitation Code Generated: $code"
            )
        }
    }

    fun patientConnectWithCode(code: String, doctorName: String = "Dr. Smith") {
        if (code.isBlank()) return
        viewModelScope.launch {
            val patientId = _uiState.value.currentUserId
            val linkId = "dlink_${UUID.randomUUID()}"
            val link = DoctorLink(
                id = linkId,
                patientId = patientId,
                doctorUid = "doc_${code.takeLast(4)}",
                doctorName = doctorName,
                doctorContact = "doctor@meditrack.org",
                status = DoctorLinkStatus.PENDING,
                createdAt = System.currentTimeMillis()
            )
            doctorRepository.insert(link)

            val outbox = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_RECEIVED,
                payloadJson = """{"doctorName":"$doctorName","patientId":"$patientId"}""",
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outbox)

            _uiState.update { it.copy(userMessage = "Invitation request received from $doctorName. Consent required.") }
        }
    }

    fun acceptDoctorConnection(linkId: String) {
        viewModelScope.launch {
            val link = doctorRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = DoctorLinkStatus.ACCEPTED)
            doctorRepository.update(updated)

            val patientName = authRepository.getCurrentUser()?.name ?: "Patient"
            val outbox = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_ACCEPTED,
                payloadJson = """{"patientName":"$patientName","doctorUid":"${link.doctorUid}"}""",
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outbox)

            _uiState.update { it.copy(userMessage = "Doctor connected: ${link.doctorName}") }
        }
    }

    fun declineDoctorConnection(linkId: String) {
        viewModelScope.launch {
            val link = doctorRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = DoctorLinkStatus.DECLINED)
            doctorRepository.update(updated)

            val patientName = authRepository.getCurrentUser()?.name ?: "Patient"
            val outbox = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_DECLINED,
                payloadJson = """{"patientName":"$patientName","doctorUid":"${link.doctorUid}"}""",
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outbox)

            _uiState.update { it.copy(userMessage = "Doctor invitation declined.") }
        }
    }

    fun revokeDoctorAccess(linkId: String) {
        viewModelScope.launch {
            val link = doctorRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = DoctorLinkStatus.REVOKED)
            doctorRepository.update(updated)

            val outbox = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_REVOKED,
                payloadJson = """{"doctorUid":"${link.doctorUid}"}""",
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outbox)

            _uiState.update { it.copy(userMessage = "Doctor access revoked immediately.") }
        }
    }

    fun selectPatient(patientId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedPatientId = patientId, isLoading = true) }

            // Strict read-only query of medicines and doses
            val medicines = medicineRepository.observeAll().firstOrNull() ?: emptyList()

            val today = LocalDate.now()
            val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

            val doses = doseRepository.observeBetween(startOfDay, endOfDay).firstOrNull() ?: emptyList()
            val adherence = AdherenceCalculator.calculate(doses)

            _uiState.update {
                it.copy(
                    selectedPatientMedicines = medicines,
                    selectedPatientDoses = doses,
                    selectedPatientAdherence = adherence,
                    isLoading = false
                )
            }
        }
    }

    fun createPrescriptionProposal(
        patientId: String,
        type: ProposalType,
        payload: PrescriptionPayload,
        medicineId: String? = null
    ) {
        viewModelScope.launch {
            val doctorUser = authRepository.getCurrentUser()
            val doctorUid = doctorUser?.id ?: _uiState.value.currentUserId
            val doctorName = doctorUser?.name ?: "Doctor"

            val proposalId = "prop_${UUID.randomUUID()}"
            val finalPayload = payload.copy(doctorName = doctorName)
            val proposal = MedicineProposal(
                id = proposalId,
                patientId = patientId,
                doctorUid = doctorUid,
                type = type,
                medicineId = medicineId,
                payloadJson = finalPayload.toJson(),
                status = ProposalStatus.PENDING,
                createdAt = System.currentTimeMillis()
            )
            proposalRepository.insert(proposal)

            val outbox = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.PRESCRIPTION_PROPOSAL_RECEIVED,
                payloadJson = """{"doctorName":"$doctorName","medicineName":"${finalPayload.medicineName}","patientId":"$patientId"}""",
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outbox)

            _uiState.update { it.copy(userMessage = "Prescription proposal submitted. Awaiting patient approval.") }
        }
    }

    fun acceptProposal(proposalId: String) {
        viewModelScope.launch {
            val success = acceptProposalUseCase(proposalId)
            if (success) {
                _uiState.update { it.copy(userMessage = "Prescription accepted and activated!") }
            }
        }
    }

    fun declineProposal(proposalId: String) {
        viewModelScope.launch {
            val success = declineProposalUseCase(proposalId)
            if (success) {
                _uiState.update { it.copy(userMessage = "Prescription proposal declined.") }
            }
        }
    }

    fun requestClarification(proposalId: String, note: String) {
        viewModelScope.launch {
            val success = requestClarificationUseCase(proposalId, note)
            if (success) {
                _uiState.update { it.copy(userMessage = "Clarification requested from doctor.") }
            }
        }
    }

    fun reviseProposal(oldProposalId: String, updatedPayload: PrescriptionPayload) {
        viewModelScope.launch {
            val newProposal = reviseProposalUseCase(oldProposalId, updatedPayload)
            if (newProposal != null) {
                _uiState.update { it.copy(userMessage = "Prescription proposal updated (Revision ${updatedPayload.revision + 1}). Awaiting patient acceptance.") }
            }
        }
    }

    fun toggleRole() {
        val newRole = if (_uiState.value.currentUserRole == UserRole.DOCTOR) UserRole.PATIENT else UserRole.DOCTOR
        _uiState.update { it.copy(currentUserRole = newRole) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
