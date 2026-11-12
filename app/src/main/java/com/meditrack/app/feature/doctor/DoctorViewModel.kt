package com.meditrack.app.feature.doctor

import com.meditrack.app.core.util.JsonPayload
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
    private val reviseProposalUseCase: ReviseProposalUseCase,
    private val doctorCloud: DoctorCloud
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

            // Ensure demo patient & demo doctor have an accepted link and proposal for out-of-the-box demo testing
            if (userId.startsWith("demo_") || userId == "doctor_1") {
                try {
                    val demoPatientId = "demo_patient_001"
                    val demoDoctorUid = "demo_doctor_001"
                    if (doctorRepository.getById("demo_doc_link_001") == null) {
                        doctorRepository.insert(
                            DoctorLink(
                                id = "demo_doc_link_001",
                                patientId = demoPatientId,
                                doctorUid = demoDoctorUid,
                                doctorName = "Dr. Smith (Demo)",
                                doctorContact = "dr.smith@hospital.org",
                                status = DoctorLinkStatus.ACCEPTED,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                    if (proposalRepository.getById("demo_prop_001") == null) {
                        proposalRepository.insert(
                            MedicineProposal(
                                id = "demo_prop_001",
                                patientId = demoPatientId,
                                doctorUid = demoDoctorUid,
                                                                type = ProposalType.ADD,
                                payloadJson = PrescriptionPayload(
                                    medicineName = "Amoxicillin 500mg",
                                    dosageText = "500mg capsule",
                                    pillsPerDose = 1,
                                    times = listOf("09:00"),
                                    startDate = LocalDate.now().toString(),
                                    additionalInstructions = "Take with water after breakfast"
                                ).toJson(),
                                status = ProposalStatus.PENDING,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                } catch (_: Exception) {}
            }

            // These collectors need the resolved user id, so they start only after it is known.
            launch {
                // Observe incoming doctor links for patient
                doctorRepository.observeByPatientId(userId).collect { links ->
                    _uiState.update { it.copy(incomingDoctorLinks = links) }
                }
            }

            launch {
                if (doctorCloud.isAvailable) {
                    val cloudProposals = doctorCloud.fetchProposalsForPatient(userId)
                    if (cloudProposals.isNotEmpty()) {
                        _uiState.update { it.copy(patientProposals = cloudProposals) }
                    }
                }
                // Only proposals this user sent (as doctor) or received (as patient); never other accounts' proposals.
                proposalRepository.observeAll().collect { proposals ->
                    val mine = proposals.filter { it.patientId == userId || it.doctorUid == userId }
                    _uiState.update { it.copy(patientProposals = mine) }
                }
            }

            // Observe accepted patients for doctor
            val linksFlow = if (role == UserRole.DOCTOR) {
                doctorRepository.observeAcceptedForDoctor(userId)
            } else {
                doctorRepository.observeAll()
            }
            linksFlow.collect { links ->
                val accepted = links.filter {
                    it.status == DoctorLinkStatus.ACCEPTED && (it.doctorUid == userId || it.patientId == userId)
                }
                _uiState.update { it.copy(acceptedPatients = accepted) }
                if (_uiState.value.selectedPatientId == null && accepted.isNotEmpty()) {
                    selectPatient(accepted.first().patientId)
                }
            }
        }

    }

    /**
     * C1: Doctor generates unguessable 8-char invite code (SecureRandom, no 0/O/1/I)
     * Writes to Firestore doctorInvitations when cloud is available; keeps Room mirror for offline UI.
     */
    fun generateDoctorInvite(patientContact: String? = null) {
        val code = DoctorInviteCodeGenerator.generate()
        viewModelScope.launch {
            if (doctorCloud.isAvailable) {
                val user = authRepository.getCurrentUser()
                val doctorUid = user?.id ?: _uiState.value.currentUserId
                val doctorName = user?.name ?: "Doctor"
                doctorCloud.createDoctorInvite(
                    doctorUid = doctorUid,
                    doctorName = doctorName,
                    doctorContact = "doctor@meditrack.org",
                    patientContact = patientContact
                )
            }
            _uiState.update {
                it.copy(
                    generatedInviteCode = code,
                    userMessage = "Invitation Code Generated: $code"
                )
            }
        }
    }

    /**
     * C2: Patient enters code -> redeems via acceptDoctorInvite Cloud Function when online,
     * or records pending link locally for offline testing.
     */
    fun patientConnectWithCode(code: String, doctorName: String = "Dr. Smith") {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.isBlank()) return
        viewModelScope.launch {
            if (doctorCloud.isAvailable) {
                _uiState.update { it.copy(isLoading = true) }
                val result = doctorCloud.redeemDoctorInvite(cleanCode)
                result.onSuccess { link ->
                    doctorRepository.insert(link)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            userMessage = "Connected to doctor ${link.doctorName} successfully!"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            userMessage = "Failed to connect: ${err.message ?: "Invalid code"}"
                        )
                    }
                }
            } else {
                val patientId = _uiState.value.currentUserId
                val linkId = "dlink_${UUID.randomUUID()}"
                val link = DoctorLink(
                    id = linkId,
                    patientId = patientId,
                    doctorUid = "doc_${cleanCode.takeLast(4)}",
                    doctorName = doctorName,
                    doctorContact = "doctor@meditrack.org",
                    status = DoctorLinkStatus.PENDING,
                    createdAt = System.currentTimeMillis()
                )
                doctorRepository.insert(link)

                val outbox = NotificationOutbox(
                    id = "outbox_${UUID.randomUUID()}",
                    type = NotifType.DOCTOR_INVITE_RECEIVED,
                    payloadJson = JsonPayload.build("doctorName" to doctorName, "patientId" to patientId),
                    createdAt = System.currentTimeMillis(),
                    delivered = false
                )
                outboxRepository.enqueue(outbox)

                _uiState.update { it.copy(userMessage = "Invitation request received from $doctorName. Consent required.") }
            }
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
                payloadJson = JsonPayload.build("patientName" to patientName, "doctorUid" to link.doctorUid),
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
            if (doctorCloud.isAvailable && !link.invitationCode.isNullOrBlank()) {
                doctorCloud.declineDoctorInvite(link.invitationCode)
            }
            val updated = link.copy(status = DoctorLinkStatus.DECLINED)
            doctorRepository.update(updated)

            val patientName = authRepository.getCurrentUser()?.name ?: "Patient"
            val outbox = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_DECLINED,
                payloadJson = JsonPayload.build("patientName" to patientName, "doctorUid" to link.doctorUid),
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outbox)

            _uiState.update { it.copy(userMessage = "Doctor invitation declined.") }
        }
    }

    /**
     * C6: Revoke link -> updates Firestore doctorLinks/{id}.status = 'REVOKED' and syncs to local Room.
     */
    fun revokeDoctorAccess(linkId: String) {
        viewModelScope.launch {
            if (doctorCloud.isAvailable) {
                doctorCloud.revokeDoctorLink(linkId)
            }
            val link = doctorRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = DoctorLinkStatus.REVOKED)
            doctorRepository.update(updated)

            val outbox = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.DOCTOR_INVITE_REVOKED,
                payloadJson = JsonPayload.build("doctorUid" to link.doctorUid),
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outbox)

            _uiState.update { it.copy(userMessage = "Doctor access revoked immediately.") }
        }
    }

    /**
     * C5: Doctor views patient adherence: queries Firestore users/{patientId}/medicines and
     * users/{patientId}/doseEvents (last 7 days). Does NOT touch doctor's local Room.
     * Calculates adherence via existing AdherenceCalculator.
     */
    private var selectPatientJob: Job? = null

    fun selectPatient(patientId: String) {
        selectPatientJob?.cancel()
        selectPatientJob = viewModelScope.launch {
            _uiState.update { it.copy(selectedPatientId = patientId, isLoading = true) }

            if (doctorCloud.isAvailable && !patientId.startsWith("demo_")) {
                val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
                val result = doctorCloud.fetchPatientData(patientId, sevenDaysAgo)
                result.onSuccess { (meds, doses) ->
                    val adherence = AdherenceCalculator.calculate(doses)
                    _uiState.update {
                        it.copy(
                            selectedPatientMedicines = meds,
                            selectedPatientDoses = doses,
                            selectedPatientAdherence = adherence,
                            isLoading = false
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            userMessage = "Failed to load patient data: ${err.message}"
                        )
                    }
                }
            } else {
                val fourteenDaysAgo = System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000)
                val tomorrow = System.currentTimeMillis() + (24L * 60 * 60 * 1000)

                combine(
                    medicineRepository.observeActiveByPatient(patientId),
                    doseRepository.observeBetweenByPatient(patientId, fourteenDaysAgo, tomorrow)
                ) { meds, doses ->
                    val adherence = AdherenceCalculator.calculate(doses)
                    _uiState.update {
                        it.copy(
                            selectedPatientMedicines = meds,
                            selectedPatientDoses = doses.sortedByDescending { d -> d.scheduledAt },
                            selectedPatientAdherence = adherence,
                            isLoading = false
                        )
                    }
                }.collect()
            }
        }
    }

    /**
     * C3: Doctor creates proposal -> writes to Firestore collection medicineProposals/{id}.
     * Also keeps Room mirroring for offline support.
     */
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

            if (doctorCloud.isAvailable) {
                doctorCloud.createProposal(proposal)
            }
            proposalRepository.insert(proposal)

            val outbox = NotificationOutbox(
                id = "outbox_${UUID.randomUUID()}",
                type = NotifType.PRESCRIPTION_PROPOSAL_RECEIVED,
                payloadJson = JsonPayload.build("doctorName" to doctorName, "medicineName" to finalPayload.medicineName, "patientId" to patientId),
                createdAt = System.currentTimeMillis(),
                delivered = false
            )
            outboxRepository.enqueue(outbox)

            _uiState.update { it.copy(userMessage = "Prescription proposal submitted. Awaiting patient approval.") }
        }
    }

    /**
     * C4: Patient accepts proposal -> updates Firestore doc and runs AcceptProposalUseCase locally
     * to add to patient's Room medicines.
     */
    fun acceptProposal(proposalId: String) {
        viewModelScope.launch {
            val success = acceptProposalUseCase(proposalId)
            if (success) {
                if (doctorCloud.isAvailable) {
                    doctorCloud.updateProposal(
                        proposalId,
                        mapOf("status" to ProposalStatus.ACCEPTED.name, "resolvedAt" to System.currentTimeMillis())
                    )
                }
                _uiState.update { it.copy(userMessage = "Prescription accepted and activated!") }
            }
        }
    }

    /**
     * C4: Patient declines proposal -> updates Firestore doc and marks proposal declined locally.
     */
    fun declineProposal(proposalId: String) {
        viewModelScope.launch {
            val success = declineProposalUseCase(proposalId)
            if (success) {
                if (doctorCloud.isAvailable) {
                    doctorCloud.updateProposal(
                        proposalId,
                        mapOf("status" to ProposalStatus.DECLINED.name, "resolvedAt" to System.currentTimeMillis())
                    )
                }
                _uiState.update { it.copy(userMessage = "Prescription proposal declined.") }
            }
        }
    }

    fun requestClarification(proposalId: String, note: String) {
        viewModelScope.launch {
            val success = requestClarificationUseCase(proposalId, note)
            if (success) {
                if (doctorCloud.isAvailable) {
                    doctorCloud.updateProposal(
                        proposalId,
                        mapOf(
                            "status" to ProposalStatus.CLARIFICATION_REQUESTED.name,
                            "clarificationNote" to note
                        )
                    )
                }
                _uiState.update { it.copy(userMessage = "Clarification requested from doctor.") }
            }
        }
    }

    fun reviseProposal(oldProposalId: String, updatedPayload: PrescriptionPayload) {
        viewModelScope.launch {
            val newProposal = reviseProposalUseCase(oldProposalId, updatedPayload)
            if (newProposal != null) {
                if (doctorCloud.isAvailable) {
                    doctorCloud.createProposal(newProposal)
                    doctorCloud.updateProposal(
                        oldProposalId,
                        mapOf(
                            "status" to ProposalStatus.SUPERSEDED.name,
                            "resolvedAt" to System.currentTimeMillis()
                        )
                    )
                }
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
