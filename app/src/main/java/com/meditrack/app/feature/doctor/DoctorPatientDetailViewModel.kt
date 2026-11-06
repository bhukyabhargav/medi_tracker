package com.meditrack.app.feature.doctor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.app.data.db.dao.UserDao
import com.meditrack.app.data.prefs.UserPreferences
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.PrescriptionProposalPayload
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.repository.DoctorRepository
import com.meditrack.app.domain.repository.DoseRepository
import com.meditrack.app.domain.repository.MedicineRepository
import com.meditrack.app.domain.usecase.AdherenceCalculator
import com.meditrack.app.domain.usecase.CreateProposalUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DoctorPatientDetailUiState(
    val patientId: String = "",
    val doctorUid: String = "",
    val doctorName: String = "",
    val medicines: List<Medicine> = emptyList(),
    val todayDoses: List<DoseEvent> = emptyList(),
    val proposals: List<MedicineProposal> = emptyList(),
    val todayAdherence: Double = 0.0,
    val weekAdherence: Double = 0.0,
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class DoctorPatientDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val doctorRepository: DoctorRepository,
    private val medicineRepository: MedicineRepository,
    private val doseRepository: DoseRepository,
    private val createProposalUseCase: CreateProposalUseCase,
    private val userPreferences: UserPreferences,
    private val userDao: UserDao
) : ViewModel() {

    private val patientId: String = savedStateHandle.get<String>("patientId") ?: ""

    private val _uiState = MutableStateFlow(DoctorPatientDetailUiState(patientId = patientId))
    val uiState: StateFlow<DoctorPatientDetailUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val uid = userPreferences.currentUserId.firstOrNull() ?: ""
            val user = userDao.getUserById(uid)
            val docName = user?.name ?: "Doctor"
            _uiState.value = _uiState.value.copy(doctorUid = uid, doctorName = docName)

            // Verify active link
            val link = doctorRepository.getDoctorLinkByDoctorAndPatient(uid, patientId)
            if (link == null || link.status != com.meditrack.app.domain.model.DoctorLinkStatus.ACCEPTED) {
                _uiState.value = _uiState.value.copy(
                    error = "Access denied: Patient has not actively accepted this doctor connection."
                )
                return@launch
            }

            // Observe patient medicines (read-only)
            medicineRepository.observeActive().collect { allMeds ->
                val patientMeds = allMeds.filter { it.patientId == patientId }
                _uiState.value = _uiState.value.copy(medicines = patientMeds)
            }
        }

        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val endOfDay = startOfDay + (24 * 60 * 60 * 1000L) - 1
            val weekStart = startOfDay - (6 * 24 * 60 * 60 * 1000L)

            // Observe today's doses
            doseRepository.observeBetween(startOfDay, endOfDay).collect { doses ->
                val patientDoses = doses.filter { it.patientId == patientId }
                val report = AdherenceCalculator.calculate(patientDoses)
                val todayAdherence = report.percentage ?: 0.0
                _uiState.value = _uiState.value.copy(
                    todayDoses = patientDoses,
                    todayAdherence = todayAdherence
                )
            }
        }

        viewModelScope.launch {
            // Observe proposals
            doctorRepository.observeProposalsByPatientId(patientId).collect { props ->
                _uiState.value = _uiState.value.copy(proposals = props)
            }
        }
    }

    fun proposeStopMedicine(medicine: Medicine) {
        viewModelScope.launch {
            val payload = PrescriptionProposalPayload(
                medicineName = medicine.name,
                dosageText = medicine.dosageText,
                pillsPerDose = medicine.pillsPerDose,
                frequencyType = medicine.frequencyType,
                doctorName = _uiState.value.doctorName,
                reason = "Doctor proposed discontinuation of ${medicine.name}"
            )

            val result = createProposalUseCase(
                patientId = patientId,
                doctorUid = _uiState.value.doctorUid,
                doctorName = _uiState.value.doctorName,
                type = ProposalType.STOP,
                medicineId = medicine.id,
                payload = payload
            )

            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    message = "Stop proposal submitted to patient for approval."
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    error = ex.message ?: "Failed to submit proposal."
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}
