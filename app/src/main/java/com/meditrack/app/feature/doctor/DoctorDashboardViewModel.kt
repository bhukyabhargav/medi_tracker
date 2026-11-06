package com.meditrack.app.feature.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.usecase.DoctorLinkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DoctorDashboardUiState(
    val doctorUid: String = "",
    val doctorName: String = "",
    val connectedPatients: List<DoctorLink> = emptyList(),
    val generatedCode: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DoctorDashboardViewModel @Inject constructor(
    private val doctorRepository: DoctorRepository,
    private val doctorLinkUseCase: DoctorLinkUseCase,
    private val userPreferences: UserPreferences,
    private val userDao: UserDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(DoctorDashboardUiState())
    val uiState: StateFlow<DoctorDashboardUiState> = _uiState.asStateFlow()

    init {
        loadDoctorInfo()
    }

    private fun loadDoctorInfo() {
        viewModelScope.launch {
            val uid = userPreferences.currentUserId.firstOrNull() ?: ""
            val user = userDao.getUserById(uid)
            val docName = user?.name ?: "Doctor"
            _uiState.value = _uiState.value.copy(doctorUid = uid, doctorName = docName)

            if (uid.isNotBlank()) {
                doctorRepository.observeAcceptedLinksByDoctorUid(uid).collect { links ->
                    _uiState.value = _uiState.value.copy(
                        connectedPatients = links.filter { it.status == DoctorLinkStatus.ACCEPTED }
                    )
                }
            }
        }
    }

    fun generateInviteCode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = doctorLinkUseCase.createDoctorInvitation(
                doctorUid = _uiState.value.doctorUid,
                doctorName = _uiState.value.doctorName,
                doctorContact = ""
            )
            result.onSuccess { link ->
                _uiState.value = _uiState.value.copy(
                    generatedCode = link.invitationCode,
                    isLoading = false
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    error = ex.message ?: "Failed to generate invitation code.",
                    isLoading = false
                )
            }
        }
    }

    fun clearGeneratedCode() {
        _uiState.value = _uiState.value.copy(generatedCode = null)
    }
}
