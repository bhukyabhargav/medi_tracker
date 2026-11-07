package com.meditrack.app.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.core.config.AppConfig
import com.meditrack.app.core.security.KeystoreSecurityManager
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.CaregiverRepository
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.ProposalRepository
import com.meditrack.app.domain.usecase.DeleteAccountUseCase
import com.meditrack.app.domain.usecase.ExportDataUseCase
import com.meditrack.app.domain.usecase.ExportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val user: User? = null,
    val isLoggedIn: Boolean = false,
    val doseWindowMinutes: Int = 60,
    val defaultSnoozeMinutes: Int = 15,
    val notifMissedEnabled: Boolean = true,
    val notifStockEnabled: Boolean = true,
    val notifUnverifiedEnabled: Boolean = true,
    val notifLateEnabled: Boolean = true,
    val isFlagSecureEnabled: Boolean = true,
    val isPrivacyConsentGiven: Boolean = false,
    val connectedDoctors: List<DoctorLink> = emptyList(),
    val pendingDoctorInvitations: List<DoctorLink> = emptyList(),
    val doctorProposals: List<MedicineProposal> = emptyList(),
    val connectedPatients: List<DoctorLink> = emptyList(),
    val caregiverLinks: List<CaregiverLink> = emptyList(),
    val isExporting: Boolean = false,
    val exportResult: ExportResult? = null,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val isLoggedOut: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences,
    private val userDao: UserDao,
    private val doctorRepository: DoctorRepository,
    private val proposalRepository: ProposalRepository,
    private val caregiverRepository: CaregiverRepository,
    private val exportDataUseCase: ExportDataUseCase,
    private val deleteAccountUseCase: DeleteAccountUseCase,
    private val keystoreSecurityManager: KeystoreSecurityManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        // Observe Current User
        viewModelScope.launch {
            authRepository.observeCurrentUser().collect { user ->
                _uiState.update { it.copy(user = user) }
                if (user != null) {
                    observeRoleData(user.id, user.role)
                }
            }
        }

        // Observe Is Logged In
        viewModelScope.launch {
            authRepository.observeIsLoggedIn().collect { loggedIn ->
                _uiState.update { it.copy(isLoggedIn = loggedIn) }
            }
        }

        // Observe Dose Window Minutes
        viewModelScope.launch {
            userPreferences.doseWindowMinutes.collect { minutes ->
                AppConfig.userDoseWindowMinutes = minutes
                _uiState.update { it.copy(doseWindowMinutes = minutes) }
            }
        }

        // Observe Default Snooze Minutes
        viewModelScope.launch {
            userPreferences.defaultSnoozeMinutes.collect { minutes ->
                _uiState.update { it.copy(defaultSnoozeMinutes = minutes) }
            }
        }

        // Observe Notification Toggles
        viewModelScope.launch {
            userPreferences.notifMissedEnabled.collect { enabled ->
                _uiState.update { it.copy(notifMissedEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferences.notifStockEnabled.collect { enabled ->
                _uiState.update { it.copy(notifStockEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferences.notifUnverifiedEnabled.collect { enabled ->
                _uiState.update { it.copy(notifUnverifiedEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferences.notifLateEnabled.collect { enabled ->
                _uiState.update { it.copy(notifLateEnabled = enabled) }
            }
        }

        // Observe Screen Security
        viewModelScope.launch {
            userPreferences.isFlagSecureEnabled.collect { enabled ->
                _uiState.update { it.copy(isFlagSecureEnabled = enabled) }
            }
        }

        // Observe Privacy Consent
        viewModelScope.launch {
            userPreferences.isPrivacyConsentGiven.collect { given ->
                _uiState.update { it.copy(isPrivacyConsentGiven = given) }
            }
        }
    }

    private fun observeRoleData(userId: String, role: UserRole) {
        if (role == UserRole.PATIENT) {
            // Observe Doctor Links for Patient
            viewModelScope.launch {
                doctorRepository.observeByPatientId(userId).collect { links ->
                    val connected = links.filter { it.status == DoctorLinkStatus.ACCEPTED }
                    val pending = links.filter { it.status == DoctorLinkStatus.PENDING }
                    _uiState.update { it.copy(connectedDoctors = connected, pendingDoctorInvitations = pending) }
                }
            }
            // Observe Proposals for Patient
            viewModelScope.launch {
                proposalRepository.observeByPatientId(userId).collect { proposals ->
                    _uiState.update { it.copy(doctorProposals = proposals) }
                }
            }
            // Observe Caregiver Links
            viewModelScope.launch {
                caregiverRepository.observeByPatientId(userId).collect { caregivers ->
                    _uiState.update { it.copy(caregiverLinks = caregivers) }
                }
            }
        } else if (role == UserRole.DOCTOR) {
            // Observe Connected Patients for Doctor
            viewModelScope.launch {
                doctorRepository.observeAcceptedForDoctor(userId).collect { patients ->
                    _uiState.update { it.copy(connectedPatients = patients) }
                }
            }
            // Observe Proposals created by Doctor
            viewModelScope.launch {
                proposalRepository.observeByDoctorUid(userId).collect { proposals ->
                    _uiState.update { it.copy(doctorProposals = proposals) }
                }
            }
        }
    }

    fun updateUserName(name: String) {
        viewModelScope.launch {
            val currentUser = _uiState.value.user ?: return@launch
            if (name.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Name cannot be empty") }
                return@launch
            }
            val updatedUser = currentUser.copy(name = name.trim())
            userDao.insertUser(updatedUser)
            _uiState.update { it.copy(user = updatedUser, message = "Profile updated") }
        }
    }

    fun setDoseWindowMinutes(minutes: Int) {
        viewModelScope.launch {
            val clamped = minutes.coerceIn(30, 180)
            userPreferences.setDoseWindowMinutes(clamped)
            AppConfig.userDoseWindowMinutes = clamped
            _uiState.update { it.copy(doseWindowMinutes = clamped, message = "Dose window set to $clamped min") }
        }
    }

    fun setDefaultSnoozeMinutes(minutes: Int) {
        viewModelScope.launch {
            val clamped = minutes.coerceIn(1, 240)
            userPreferences.setDefaultSnoozeMinutes(clamped)
            _uiState.update { it.copy(defaultSnoozeMinutes = clamped, message = "Snooze preset set to $clamped min") }
        }
    }

    fun setNotifMissedEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotifMissedEnabled(enabled)
            _uiState.update { it.copy(notifMissedEnabled = enabled) }
        }
    }

    fun setNotifStockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotifStockEnabled(enabled)
            _uiState.update { it.copy(notifStockEnabled = enabled) }
        }
    }

    fun setNotifUnverifiedEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotifUnverifiedEnabled(enabled)
            _uiState.update { it.copy(notifUnverifiedEnabled = enabled) }
        }
    }

    fun setNotifLateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotifLateEnabled(enabled)
            _uiState.update { it.copy(notifLateEnabled = enabled) }
        }
    }

    fun setFlagSecureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setFlagSecureEnabled(enabled)
            _uiState.update { it.copy(isFlagSecureEnabled = enabled) }
        }
    }

    fun setPrivacyConsent(given: Boolean) {
        viewModelScope.launch {
            userPreferences.setPrivacyConsentGiven(given)
            _uiState.update { it.copy(isPrivacyConsentGiven = given) }
        }
    }

    fun acceptDoctorInvite(linkId: String) {
        viewModelScope.launch {
            val link = doctorRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = DoctorLinkStatus.ACCEPTED)
            doctorRepository.update(updated)
            _uiState.update { it.copy(message = "Doctor invitation accepted: ${link.doctorName}") }
        }
    }

    fun declineDoctorInvite(linkId: String) {
        viewModelScope.launch {
            val link = doctorRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = DoctorLinkStatus.DECLINED)
            doctorRepository.update(updated)
            _uiState.update { it.copy(message = "Doctor invitation declined") }
        }
    }

    fun revokeDoctorAccess(linkId: String) {
        viewModelScope.launch {
            val link = doctorRepository.getById(linkId) ?: return@launch
            val updated = link.copy(status = DoctorLinkStatus.REVOKED)
            doctorRepository.update(updated)
            _uiState.update { it.copy(message = "Doctor access revoked for ${link.doctorName}") }
        }
    }

    fun exportData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null) }
            val currentUserId = _uiState.value.user?.id
            val result = exportDataUseCase(currentUserId)
            result.fold(
                onSuccess = { exportResult ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportResult = exportResult,
                            message = "Data export generated successfully (${exportResult.zipFile.length() / 1024} KB)"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            errorMessage = "Export failed: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
            val currentUserId = _uiState.value.user?.id
            val result = deleteAccountUseCase(currentUserId)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            isDeleted = true,
                            message = "Account and data permanently deleted"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = "Account deletion error: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            userPreferences.clearSession()
            _uiState.update { it.copy(isLoggedOut = true) }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, errorMessage = null) }
    }
}
