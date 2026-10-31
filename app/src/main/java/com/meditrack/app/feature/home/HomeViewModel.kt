package com.meditrack.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadDashboard()
    }

    private fun loadDashboard() {
        viewModelScope.launch {
            val zoneId = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val todayDate = LocalDate.now(zoneId)
            val startOfToday = ZonedDateTime.of(todayDate, LocalTime.MIN, zoneId).toInstant().toEpochMilli()
            val endOfToday = ZonedDateTime.of(todayDate, LocalTime.MAX, zoneId).toInstant().toEpochMilli()

            combine(
                authRepository.observeCurrentUser(),
                medicineRepository.observeActive(),
                doseRepository.observeBetween(startOfToday, endOfToday)
            ) { user, medicines, todayDoses ->
                val upcoming = todayDoses.filter { it.scheduledAt >= now && it.status == DoseStatus.PENDING }
                val missed = todayDoses.filter { it.status == DoseStatus.MISSED }

                HomeUiState(
                    user = user,
                    activeMedicines = medicines,
                    todayDoses = todayDoses,
                    upcomingDoses = upcoming,
                    missedDoses = missed,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }
}
