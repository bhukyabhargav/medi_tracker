package com.meditrack.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val name: String = "",
    val contact: String = "",
    val password: String = "",
    val role: UserRole = UserRole.PATIENT,
    val otp: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onNameChange(name: String) = _uiState.update { it.copy(name = name, errorMessage = null) }
    fun onContactChange(contact: String) = _uiState.update { it.copy(contact = contact, errorMessage = null) }
    fun onPasswordChange(password: String) = _uiState.update { it.copy(password = password, errorMessage = null) }
    fun onRoleChange(role: UserRole) = _uiState.update { it.copy(role = role, errorMessage = null) }
    fun onOtpChange(otp: String) = _uiState.update { it.copy(otp = otp, errorMessage = null) }

    fun validateLoginFields(): Boolean {
        val state = _uiState.value
        if (state.contact.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your email or phone number") }
            return false
        }
        if (state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your password") }
            return false
        }
        return true
    }

    fun validateRegistrationFields(): Boolean {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your name") }
            return false
        }
        if (state.contact.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your email or phone number") }
            return false
        }
        if (state.password.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters") }
            return false
        }
        return true
    }

    fun login(onSuccess: () -> Unit) {
        if (!validateLoginFields()) return

        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result = authRepository.login(state.contact, state.password)
            result.onSuccess {
                _uiState.update { it.copy(isLoading = false, isSuccess = true) }
                onSuccess()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Login failed"
                    )
                }
            }
        }
    }

    fun createAccount(onNavigateToOtp: (String) -> Unit) {
        if (!validateRegistrationFields()) return

        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result = authRepository.createAccount(
                name = state.name,
                emailOrPhone = state.contact,
                password = state.password,
                role = state.role,
                doctorRegistrationNo = null
            )
            result.onSuccess {
                _uiState.update { it.copy(isLoading = false) }
                onNavigateToOtp(state.contact)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Account creation failed"
                    )
                }
            }
        }
    }

    fun verifyOtp(contact: String, onSuccess: () -> Unit) {
        val otp = _uiState.value.otp.trim()
        if (otp.length != 6) {
            _uiState.update { it.copy(errorMessage = "Please enter the 6-digit verification code") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result = authRepository.verifyOtp(contact, otp)
            result.onSuccess {
                _uiState.update { it.copy(isLoading = false, isSuccess = true) }
                onSuccess()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Invalid code"
                    )
                }
            }
        }
    }
}
