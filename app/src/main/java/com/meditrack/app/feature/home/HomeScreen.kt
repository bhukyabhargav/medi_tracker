package com.meditrack.app.feature.home

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meditrack.app.BuildConfig
import com.meditrack.app.MediTrackApp
import com.meditrack.app.R
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.PrescriptionPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.User
import com.meditrack.app.domain.model.UserRole
import com.meditrack.app.domain.usecase.LateEntryPolicy
import com.meditrack.app.feature.doctor.CreateProposalDialog
import com.meditrack.app.feature.doctor.DoctorUiState
import com.meditrack.app.feature.caregiver.CaregiverViewModel
import com.meditrack.app.feature.caregiver.CaregiverUiState
import com.meditrack.app.feature.caregiver.MissedDoseItem
import androidx.compose.material.icons.filled.People
import com.meditrack.app.feature.doctor.DoctorViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToDebugTools: () -> Unit,
    onNavigateToAddMedicine: () -> Unit = {},
    onNavigateToMedicinesList: () -> Unit = {},
    onNavigateToVerification: (String) -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToCaregiver: () -> Unit = {},
    onNavigateToDoctor: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAddMedicineFromProposal: (MedicineProposal, PrescriptionPayload) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = hiltViewModel(),
    doctorViewModel: DoctorViewModel = hiltViewModel(),
    caregiverViewModel: CaregiverViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDoctor = uiState.user?.role == UserRole.DOCTOR
    val isCaregiver = uiState.user?.role == UserRole.CAREGIVER
    val doctorUiState by doctorViewModel.uiState.collectAsState()
    val caregiverUiState by caregiverViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(isCaregiver) {
        if (isCaregiver) {
            caregiverViewModel.loadData()
        }
    }

    LaunchedEffect(caregiverUiState.userMessage) {
        caregiverUiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            caregiverViewModel.clearMessage()
        }
    }

    var showProposalDialog by remember { mutableStateOf(false) }
    var targetPatientIdForProposal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isDoctor) {
        if (isDoctor) {
            doctorViewModel.loadData()
        }
    }

    LaunchedEffect(doctorUiState.userMessage) {
        doctorUiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            doctorViewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when {
                                isDoctor -> "Doctor Dashboard"
                                isCaregiver -> "Caregiver Dashboard"
                                else -> stringResource(R.string.home_title)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(MediTrackApp.TAG, "Navigating to Debug Tools via long-press")
                                        onNavigateToDebugTools()
                                    }
                                }
                            )
                        )
                        uiState.user?.let { user ->
                            Text(
                                text = when {
                                    isDoctor -> "Dr. ${user.name} \u2022 Clinical Oversight"
                                    isCaregiver -> "${user.name} \u2022 Missed Doses Oversight"
                                    else -> "Hello, ${user.name} (${user.role.name})"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        IconButton(
                            onClick = {
                                Log.d(MediTrackApp.TAG, "Navigating to Debug Tools via icon")
                                onNavigateToDebugTools()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = stringResource(R.string.debug_tools_title)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        } else if (isDoctor) {
            DoctorHomeContent(
                user = uiState.user,
                doctorUiState = doctorUiState,
                onGenerateInvite = { doctorViewModel.generateDoctorInvite() },
                onSelectPatient = { patientId -> doctorViewModel.selectPatient(patientId) },
                onOpenProposalDialog = { patientId ->
                    targetPatientIdForProposal = patientId
                    showProposalDialog = true
                },
                onNavigateToDoctorPortal = onNavigateToDoctor,
                onNavigateToSettings = onNavigateToSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else if (isCaregiver) {
            CaregiverHomeContent(
                user = uiState.user,
                caregiverUiState = caregiverUiState,
                onGenerateInvite = { caregiverViewModel.generateCaregiverInvite() },
                onConnectWithCode = { code -> caregiverViewModel.connectWithCode(code) },
                onNavigateToCaregiverPortal = onNavigateToCaregiver,
                onNavigateToSettings = onNavigateToSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Quick Actions
                item {
                    SectionHeader(title = stringResource(R.string.section_quick_actions))
                    Spacer(modifier = Modifier.height(8.dp))
                    QuickActionsRow(
                        onNavigateToAddMedicine = onNavigateToAddMedicine,
                        onNavigateToMedicinesList = onNavigateToMedicinesList,
                        onNavigateToHistory = onNavigateToHistory,
                        onNavigateToCalendar = onNavigateToCalendar,
                        onNavigateToCaregiver = onNavigateToCaregiver,
                        onNavigateToDoctor = onNavigateToDoctor,
                        onNavigateToScan = onNavigateToScan,
                        onNavigateToSettings = onNavigateToSettings
                    )
                }

                // Section 1.2: Incoming Doctor Prescription Proposals (for Patient)
                val pendingProposals = doctorUiState.patientProposals.filter {
                    it.status == ProposalStatus.PENDING || it.status == ProposalStatus.CLARIFICATION_REQUESTED
                }
                if (pendingProposals.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Prescription Proposal from Doctor",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                pendingProposals.forEach { prop ->
                                    val payload = try {
                                        PrescriptionPayload.fromJson(prop.payloadJson)
                                    } catch (_: Exception) {
                                        null
                                    }
                                    Text(
                                        text = "${payload?.medicineName ?: "Prescription"} (${payload?.dosageText ?: ""}) \u2022 ${payload?.pillsPerDose ?: 1} pill(s)/session",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    if (!payload?.doctorName.isNullOrBlank()) {
                                        Text(
                                            text = "Prescribed by Dr. ${payload?.doctorName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                    if (!payload?.times.isNullOrEmpty()) {
                                        Text(
                                            text = "Scheduled Time: ${payload?.times?.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                        )
                                    }
                                    Text(
                                        text = "Stock & blister strip photo to be uploaded by patient upon accepting.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                // Only a NEW medicine goes through Add Medicine. A change or stop of an
                                                // existing medicine is reviewed in Doctor Mode, which updates that medicine.
                                                if (payload != null && prop.type == com.meditrack.app.domain.model.ProposalType.ADD) {
                                                    onNavigateToAddMedicineFromProposal(prop, payload)
                                                } else {
                                                    onNavigateToDoctor()
                                                }
                                            }
                                        ) {
                                            Text(
                                                if (prop.type == com.meditrack.app.domain.model.ProposalType.ADD) {
                                                    "Review & Accept (Enter Stock & Strip)"
                                                } else {
                                                    "Review Change in Doctor Mode"
                                                }
                                            )
                                        }
                                        OutlinedButton(onClick = onNavigateToDoctor) {
                                            Text("Doctor Mode")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 1.5: Stock Alerts
                val outOfStockMeds = uiState.activeMedicines.filter { it.currentStock <= 0 }
                val lowStockMeds = uiState.activeMedicines.filter { it.currentStock > 0 && com.meditrack.app.domain.usecase.StockCalculator.isLow(it) }

                if (outOfStockMeds.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            onClick = onNavigateToMedicinesList,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Out of Stock: ${outOfStockMeds.joinToString(", ") { it.name }}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "0 pills remaining. Tap to refill immediately.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }

                if (lowStockMeds.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFF3E0)),
                            onClick = onNavigateToMedicinesList,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFE65100))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Low Stock: ${lowStockMeds.joinToString(", ") { "${it.name} (${it.currentStock} left)" }}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color(0xFFE65100)
                                    )
                                    Text(
                                        text = "Running low. Tap to refill your supply.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = androidx.compose.ui.graphics.Color(0xFFE65100).copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 2: Today's Medicines
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(title = stringResource(R.string.section_today_medicines))
                        androidx.compose.material3.TextButton(onClick = onNavigateToMedicinesList) {
                            Text(text = "Manage All", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.activeMedicines.isEmpty()) {
                        EmptyCard(
                            icon = Icons.Default.Medication,
                            message = stringResource(R.string.empty_today_medicines)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.activeMedicines.forEach { medicine ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    onClick = onNavigateToMedicinesList,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Medication,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Column(modifier = Modifier.padding(start = 12.dp)) {
                                            Text(
                                                text = medicine.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "${medicine.dosageText} \u2022 ${medicine.pillsPerDose} pill(s) \u2022 ${medicine.foodRelation}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 3: Upcoming Doses
                item {
                    SectionHeader(title = stringResource(R.string.section_upcoming_doses))
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.upcomingDoses.isEmpty()) {
                        EmptyCard(
                            icon = Icons.Default.NotificationsActive,
                            message = stringResource(R.string.empty_upcoming_doses)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.upcomingDoses.forEach { dose ->
                                val timeStr = DateTimeFormatter.ofPattern("hh:mm a")
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(dose.scheduledAt))
                                val medName = uiState.activeMedicines.find { it.id == dose.medicineId }?.name ?: "Scheduled Dose"
                                Card(
                                    onClick = { onNavigateToVerification(dose.id) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$medName \u2022 $timeStr",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text(dose.status.name) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 4: Missed Doses
                item {
                    SectionHeader(title = stringResource(R.string.section_missed_doses))
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.missedDoses.isEmpty()) {
                        EmptyCard(
                            icon = Icons.Default.Warning,
                            message = stringResource(R.string.empty_missed_doses)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val now = System.currentTimeMillis()
                            uiState.missedDoses.forEach { dose ->
                                val timeStr = DateTimeFormatter.ofPattern("hh:mm a")
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(dose.scheduledAt))
                                val medName = uiState.activeMedicines.find { it.id == dose.medicineId }?.name ?: "Dose"
                                val nextDoseTime = uiState.upcomingDoses
                                    .filter { it.medicineId == dose.medicineId && it.scheduledAt > dose.scheduledAt }
                                    .minByOrNull { it.scheduledAt }?.scheduledAt
                                val canLate = LateEntryPolicy.canLogLate(dose, nextDoseTime, now)

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Missed: $medName \u2022 $timeStr",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            if (dose.manualUnverified) {
                                                Text(
                                                    text = "Reported without photo",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                                )
                                            }
                                        }

                                        if (canLate && !dose.manualUnverified) {
                                            Button(
                                                onClick = { onNavigateToVerification(dose.id) },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("I took this late")
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = "Locked",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Locked",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProposalDialog && targetPatientIdForProposal != null) {
        CreateProposalDialog(
            patientId = targetPatientIdForProposal!!,
            onDismiss = {
                showProposalDialog = false
                targetPatientIdForProposal = null
            },
            onSubmit = { type, payload, medicineId ->
                doctorViewModel.createPrescriptionProposal(
                    targetPatientIdForProposal!!,
                    type,
                    payload,
                    medicineId
                )
                showProposalDialog = false
                targetPatientIdForProposal = null
            }
        )
    }
}

@Composable
fun DoctorHomeContent(
    user: User?,
    doctorUiState: DoctorUiState,
    onGenerateInvite: () -> Unit,
    onSelectPatient: (String) -> Unit,
    onOpenProposalDialog: (String) -> Unit,
    onNavigateToDoctorPortal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Clinical Overview Hero Banner
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dr. ${user?.name ?: "Doctor"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Role: DOCTOR \u2022 Monitoring & Proposals",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Connected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${doctorUiState.acceptedPatients.size} Patients",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Proposals",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${doctorUiState.patientProposals.size} Total",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "MediTrack doctor mode provides read-only adherence oversight and prescription proposals. Patient consent is required before any proposed medication takes effect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onNavigateToDoctorPortal,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Full Doctor Portal")
                        }
                        OutlinedButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Settings")
                        }
                    }
                }
            }
        }

        // Section: Patient Invitation
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connect New Patient",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Generate a secure, single-use invitation code to share with your patient. The patient enters it under Doctor Connection to grant you read-only adherence visibility.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = onGenerateInvite) {
                        Text("Generate Invitation Code")
                    }
                    if (doctorUiState.generatedInviteCode != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Active Invitation Code:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = doctorUiState.generatedInviteCode,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Share this 8-character code with your patient. It expires in 24 hours.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Connected Patients
        item {
            SectionHeader(title = "Connected Patients (${doctorUiState.acceptedPatients.size})")
        }

        if (doctorUiState.acceptedPatients.isEmpty()) {
            item {
                EmptyCard(
                    icon = Icons.Default.People,
                    message = "No connected patients yet. Generate an invitation code above to link your first patient."
                )
            }
        } else {
            items(doctorUiState.acceptedPatients, key = { it.id }) { link ->
                val isSelected = doctorUiState.selectedPatientId == link.patientId
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Patient: ${link.patientId}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Status: ACCEPTED (Read-Only Access)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSelectPatient(link.patientId) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isSelected) "Refresh Details" else "View Adherence & Doses")
                            }
                            OutlinedButton(
                                onClick = { onOpenProposalDialog(link.patientId) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Propose Rx")
                            }
                        }

                        if (isSelected) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))

                            // Adherence Report
                            val adherence = doctorUiState.selectedPatientAdherence
                            if (adherence != null) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "7-Day Adherence: ${adherence.percentageFormatted}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Verified Taken: ${adherence.verifiedTakenCount} \u2022 Missed: ${adherence.missedCount} \u2022 Skipped: ${adherence.skippedCount}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Active Medicines
                            Text(
                                text = "Patient Active Medicines (${doctorUiState.selectedPatientMedicines.size}):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (doctorUiState.selectedPatientMedicines.isEmpty()) {
                                Text(
                                    text = "No active medicines on record.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                doctorUiState.selectedPatientMedicines.forEach { med ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "\u2022 ${med.name} (${med.dosageText})",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Stock: ${med.currentStock} pills",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            // Recent Doses
                            Text(
                                text = "Today's Dose Logs (${doctorUiState.selectedPatientDoses.size}):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (doctorUiState.selectedPatientDoses.isEmpty()) {
                                Text(
                                    text = "No dose events recorded today.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                doctorUiState.selectedPatientDoses.take(5).forEach { dose ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Dose: ${dose.id.take(8)}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = dose.status.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = when (dose.status) {
                                                com.meditrack.app.domain.model.DoseStatus.TAKEN,
                                                com.meditrack.app.domain.model.DoseStatus.TAKEN_LATE -> MaterialTheme.colorScheme.primary
                                                com.meditrack.app.domain.model.DoseStatus.MISSED -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Prescription Proposals
        item {
            SectionHeader(title = "Prescription Proposals (${doctorUiState.patientProposals.size})")
        }

        if (doctorUiState.patientProposals.isEmpty()) {
            item {
                EmptyCard(
                    icon = Icons.Default.Medication,
                    message = "No prescription proposals generated yet. Select a patient above to propose medication."
                )
            }
        } else {
            items(doctorUiState.patientProposals, key = { it.id }) { proposal ->
                val payload = try {
                    PrescriptionPayload.fromJson(proposal.payloadJson)
                } catch (_: Exception) {
                    null
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = payload?.medicineName ?: "Prescription Proposal",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = when (proposal.status) {
                                        ProposalStatus.ACCEPTED -> androidx.compose.ui.graphics.Color(0xFFE8F5E9)
                                        ProposalStatus.DECLINED -> MaterialTheme.colorScheme.errorContainer
                                        ProposalStatus.CLARIFICATION_REQUESTED -> androidx.compose.ui.graphics.Color(0xFFE1F5FE)
                                        ProposalStatus.PENDING -> androidx.compose.ui.graphics.Color(0xFFFFF3E0)
                                        ProposalStatus.SUPERSEDED -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Text(
                                    text = "${proposal.type.name} \u2022 ${proposal.status.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = when (proposal.status) {
                                        ProposalStatus.ACCEPTED -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                        ProposalStatus.DECLINED -> MaterialTheme.colorScheme.onErrorContainer
                                        ProposalStatus.CLARIFICATION_REQUESTED -> androidx.compose.ui.graphics.Color(0xFF0277BD)
                                        ProposalStatus.PENDING -> androidx.compose.ui.graphics.Color(0xFFE65100)
                                        ProposalStatus.SUPERSEDED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Patient: ${proposal.patientId} \u2022 Dosage: ${payload?.dosageText ?: "N/A"} (${payload?.pillsPerDose ?: 1} pill(s)/session)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!payload?.times.isNullOrEmpty()) {
                            Text(
                                text = "Scheduled Time: ${payload?.times?.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = "Stock count & blister strip photo uploaded by patient upon acceptance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!payload?.additionalInstructions.isNullOrBlank()) {
                            Text(
                                text = "Instructions: ${payload.additionalInstructions}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!payload?.clarificationNote.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Patient Note: ${payload?.clarificationNote}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
fun EmptyCard(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

@Composable
fun QuickActionsRow(
    onNavigateToAddMedicine: () -> Unit = {},
    onNavigateToMedicinesList: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToCaregiver: () -> Unit = {},
    onNavigateToDoctor: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val addMedicineLabel = stringResource(R.string.action_add_medicine)
    val calendarLabel = stringResource(R.string.action_calendar)
    val historyLabel = stringResource(R.string.action_history)
    val caregiverLabel = stringResource(R.string.action_caregiver)
    val scanLabel = stringResource(R.string.action_scan)

    val actions = listOf(
        Pair(Icons.Default.AddCircle, addMedicineLabel),
        Pair(Icons.Default.Medication, "My Medicines"),
        Pair(Icons.Default.QrCodeScanner, scanLabel),
        Pair(Icons.Default.Person, "Doctor Mode"),
        Pair(Icons.Default.CalendarMonth, calendarLabel),
        Pair(Icons.Default.History, historyLabel),
        Pair(Icons.Default.People, caregiverLabel),
        Pair(Icons.Default.Settings, "Settings")
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(actions) { (icon, label) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                onClick = {
                    when (label) {
                        addMedicineLabel -> onNavigateToAddMedicine()
                        "My Medicines" -> onNavigateToMedicinesList()
                        scanLabel -> onNavigateToScan()
                        "Doctor Mode" -> onNavigateToDoctor()
                        calendarLabel -> onNavigateToCalendar()
                        historyLabel -> onNavigateToHistory()
                        caregiverLabel -> onNavigateToCaregiver()
                        "Settings" -> onNavigateToSettings()
                        else -> {}
                    }
                },
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CaregiverHomeContent(
    user: User?,
    caregiverUiState: CaregiverUiState,
    onGenerateInvite: () -> Unit,
    onConnectWithCode: (String) -> Unit,
    onNavigateToCaregiverPortal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var codeInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Caregiver ${user?.name ?: "Sarah"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Role: CAREGIVER \u2022 Missed Doses Oversight",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Connected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${caregiverUiState.linkedPatients.size} Patients",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (caregiverUiState.allConnectedMissedDoses.isNotEmpty()) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Missed Doses",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${caregiverUiState.allConnectedMissedDoses.size} Alerts",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (caregiverUiState.allConnectedMissedDoses.isNotEmpty()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "In Caregiver mode, you receive alerts strictly for missed medication doses by your connected loved ones, allowing you to follow up immediately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onNavigateToCaregiverPortal,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Full Caregiver Portal")
                        }
                        OutlinedButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Settings")
                        }
                    }
                }
            }
        }

        // Section: Connect New Patient
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connect Loved One / Patient",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Generate a code to share with your loved one, or enter their patient invitation code:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it },
                            placeholder = { Text("e.g. CG-1234") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (codeInput.isNotBlank()) {
                                    onConnectWithCode(codeInput)
                                    codeInput = ""
                                }
                            }
                        ) {
                            Text("Connect")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onGenerateInvite) {
                        Text("Generate Invitation Code")
                    }
                    if (caregiverUiState.generatedInviteCode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Code: ${caregiverUiState.generatedInviteCode}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Section: Connected Patients
        item {
            SectionHeader(title = "Connected Loved Ones (${caregiverUiState.linkedPatients.size})")
        }

        if (caregiverUiState.linkedPatients.isEmpty()) {
            item {
                EmptyCard(
                    icon = Icons.Default.People,
                    message = "No connected patients yet. Generate an invitation code above or connect with a code."
                )
            }
        } else {
            items(caregiverUiState.linkedPatients, key = { it.id }) { link ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Patient: ${link.patientId}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Status: CONNECTED (ACCEPTED)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(onClick = onNavigateToCaregiverPortal) {
                            Text("View in Portal")
                        }
                    }
                }
            }
        }

        // Section: Missed Medication Alerts (Connected Patients Only)
        item {
            SectionHeader(title = "Missed Medication Alerts (${caregiverUiState.allConnectedMissedDoses.size})")
        }

        if (caregiverUiState.allConnectedMissedDoses.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFE8F5E9)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "All Clear! No Missed Doses",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = androidx.compose.ui.graphics.Color(0xFF1B5E20)
                            )
                            Text(
                                text = "All connected patients have taken their scheduled medications.",
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            )
                        }
                    }
                }
            }
        } else {
            items(caregiverUiState.allConnectedMissedDoses, key = { it.id }) { item ->
                val timeStr = java.time.format.DateTimeFormatter.ofPattern("hh:mm a \u2022 MMM d")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(item.dose.scheduledAt))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = item.medicineName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            SuggestionChip(
                                onClick = {},
                                label = { Text("MISSED", fontWeight = FontWeight.Bold) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    labelColor = MaterialTheme.colorScheme.onError
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Patient: ${item.patientId} \u2022 Scheduled: $timeStr",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Urgent: Dose verification window elapsed. Please reach out to patient to ensure safety.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}
