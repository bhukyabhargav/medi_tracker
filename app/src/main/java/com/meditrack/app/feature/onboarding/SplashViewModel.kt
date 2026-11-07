package com.meditrack.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.core.navigation.Screen
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SplashNavigationTarget {
    data object Loading : SplashNavigationTarget()
    data class Navigate(val route: String) : SplashNavigationTarget()
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _navigationTarget = MutableStateFlow<SplashNavigationTarget>(SplashNavigationTarget.Loading)
    val navigationTarget: StateFlow<SplashNavigationTarget> = _navigationTarget.asStateFlow()

    init {
        checkSession()
    }

    fun checkSession() {
        viewModelScope.launch {
            // Stage 12 Task 3: Consent before any data is stored.
            // First run flow: Welcome -> Privacy consent -> Create account/Login.
            val isConsentGiven = userPreferences.isPrivacyConsentGiven.first()
            if (!isConsentGiven) {
                _navigationTarget.value = SplashNavigationTarget.Navigate(Screen.PrivacyConsent.route)
                return@launch
            }

            val isLoggedIn = authRepository.observeIsLoggedIn().first()
            if (!isLoggedIn) {
                _navigationTarget.value = SplashNavigationTarget.Navigate(Screen.Login.route)
                return@launch
            }

            val isOnboardingCompleted = authRepository.observeIsOnboardingCompleted().first()
            if (!isOnboardingCompleted) {
                _navigationTarget.value = SplashNavigationTarget.Navigate(Screen.PermissionSetup.route)
                return@launch
            }

            val isBatteryGuidanceSeen = authRepository.observeIsBatteryGuidanceSeen().first()
            if (!isBatteryGuidanceSeen) {
                _navigationTarget.value = SplashNavigationTarget.Navigate(Screen.BatteryGuidance.route)
                return@launch
            }

            _navigationTarget.value = SplashNavigationTarget.Navigate(Screen.Home.route)
        }
    }
}
