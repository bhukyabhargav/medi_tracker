package com.meditrack.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.AcceptProposalUseCase
import com.meditrack.app.domain.usecase.DeclineProposalUseCase
import com.meditrack.app.domain.usecase.DoctorLinkUseCase
import com.meditrack.app.domain.usecase.RequestClarificationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

data class HomeUiState(
    val user: User? = null,
    val activeMedicines: List<Medicine> = emptyList(),
    val todayDoses: List<DoseEvent> = emptyList(),
    val upcomingDoses: List<DoseEvent> = emptyList(),
    val missedDoses: List<DoseEvent> = emptyList(),
    val pendingProposals: List<MedicineProposal> = emptyList(),
    val activeDoctorLinks: List<DoctorLink> = emptyList(),
    val userMessage: String? = null,
    val userError: String? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val doctorRepository: DoctorRepository,
    private val acceptProposalUseCase: AcceptProposalUseCase,
    private val declineProposalUseCase: DeclineProposalUseCase,
    private val requestClarificationUseCase: RequestClarificationUseCase,
    private val doctorLinkUseCase: DoctorLinkUseCase
) : ViewModel() {

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }
    internal var zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    companion object {
        private const val MILLIS_PER_HOUR = 3600 * 1000L
        private const val LATE_WINDOW_MILLIS = AppConfig.LATE_DOSE_MAX_HOURS * MILLIS_PER_HOUR // 24 hours
        private const val UPCOMING_WINDOW_MILLIS = 24 * MILLIS_PER_HOUR // next 24 hours
    }

    init {
        loadDashboard()
    }

    fun loadDashboard() {
        viewModelScope.launch {
            val now = timeProvider()
            val zoneId = zoneProvider()
            val todayDate = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
            val startOfToday = ZonedDateTime.of(todayDate, LocalTime.MIN, zoneId).toInstant().toEpochMilli()
            val endOfToday = ZonedDateTime.of(todayDate, LocalTime.MAX, zoneId).toInstant().toEpochMilli()

            val queryStart = minOf(startOfToday, now - LATE_WINDOW_MILLIS)
            val queryEnd = maxOf(endOfToday, now + UPCOMING_WINDOW_MILLIS)

            combine(
                authRepository.observeCurrentUser(),
                medicineRepository.observeActive(),
                doseRepository.observeBetween(queryStart, queryEnd)
            ) { user, medicines, allDoses ->
                val currentUserId = user?.id ?: ""
                val patientMeds = if (currentUserId.isNotBlank()) medicines.filter { it.patientId.isEmpty() || it.patientId == currentUserId } else medicines
                val patientDoses = if (currentUserId.isNotBlank()) allDoses.filter { it.patientId.isEmpty() || it.patientId == currentUserId } else allDoses

                val today = patientDoses.filter { it.scheduledAt in startOfToday..endOfToday }
                    .sortedBy { it.scheduledAt }

                val upcoming = patientDoses.filter {
                    it.status == DoseStatus.PENDING && it.scheduledAt in now..(now + UPCOMING_WINDOW_MILLIS)
                }.sortedBy { it.scheduledAt }

                val missed = patientDoses.filter {
                    it.status == DoseStatus.MISSED && (now - it.scheduledAt) in 0..LATE_WINDOW_MILLIS
                }.sortedBy { it.scheduledAt }

                _uiState.value = _uiState.value.copy(
                    user = user,
                    activeMedicines = patientMeds,
                    todayDoses = today,
                    upcomingDoses = upcoming,
                    missedDoses = missed,
                    isLoading = false
                )
            }.collect {}
        }

        viewModelScope.launch {
            authRepository.observeCurrentUser().collect { user ->
                val uid = user?.id ?: return@collect
                // Observe pending proposals
                doctorRepository.observeProposalsByPatientId(uid).collect { proposals ->
                    val pending = proposals.filter {
                        it.status == ProposalStatus.PENDING || it.status == ProposalStatus.CLARIFICATION_REQUESTED
                    }
                    _uiState.value = _uiState.value.copy(pendingProposals = pending)
                }
            }
        }

        viewModelScope.launch {
            authRepository.observeCurrentUser().collect { user ->
                val uid = user?.id ?: return@collect
                // Observe doctor links
                doctorRepository.observeDoctorLinksByPatientId(uid).collect { links ->
                    val active = links.filter { it.status == DoctorLinkStatus.ACCEPTED }
                    _uiState.value = _uiState.value.copy(activeDoctorLinks = active)
                }
            }
        }
    }

    fun acceptProposal(proposalId: String) {
        viewModelScope.launch {
            val patientId = _uiState.value.user?.id ?: return@launch
            val patientName = _uiState.value.user?.name ?: "Patient"
            val result = acceptProposalUseCase(proposalId, patientId, patientName)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    userMessage = "Prescription proposal accepted. Medicine and doses activated."
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    userError = ex.message ?: "Failed to accept proposal."
                )
            }
        }
    }

    fun declineProposal(proposalId: String) {
        viewModelScope.launch {
            val patientId = _uiState.value.user?.id ?: return@launch
            val patientName = _uiState.value.user?.name ?: "Patient"
            val result = declineProposalUseCase(proposalId, patientId, patientName)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    userMessage = "Prescription proposal declined."
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    userError = ex.message ?: "Failed to decline proposal."
                )
            }
        }
    }

    fun requestClarification(proposalId: String, note: String) {
        viewModelScope.launch {
            val patientId = _uiState.value.user?.id ?: return@launch
            val patientName = _uiState.value.user?.name ?: "Patient"
            val result = requestClarificationUseCase(proposalId, patientId, note, patientName)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    userMessage = "Clarification request sent to doctor."
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    userError = ex.message ?: "Failed to send clarification."
                )
            }
        }
    }

    fun redeemDoctorInvite(code: String) {
        viewModelScope.launch {
            val patientId = _uiState.value.user?.id ?: return@launch
            val patientName = _uiState.value.user?.name ?: "Patient"
            val result = doctorLinkUseCase.redeemDoctorInvitation(patientId, code, patientName)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    userMessage = "Connected with Dr. ${it.doctorName} successfully!"
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    userError = ex.message ?: "Failed to redeem doctor code."
                )
            }
        }
    }

    fun revokeDoctor(doctorUid: String) {
        viewModelScope.launch {
            val patientId = _uiState.value.user?.id ?: return@launch
            val result = doctorLinkUseCase.revokeDoctorAccess(patientId, doctorUid)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    userMessage = "Doctor access revoked. Prescribed medicines remain active under your control."
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    userError = ex.message ?: "Failed to revoke doctor access."
                )
            }
        }
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(userMessage = null, userError = null)
    }
}
