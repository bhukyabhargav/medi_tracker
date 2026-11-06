package com.meditrack.app.feature.home

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.meditrack.app.domain.usecase.LateEntryPolicy
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
    viewModel: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDoctorDialog by remember { mutableStateOf(false) }
    var doctorCodeInput by remember { mutableStateOf("") }
    var showClarifyDialog by remember { mutableStateOf(false) }
    var activeClarifyProposalId by remember { mutableStateOf("") }
    var clarifyNoteInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState.userMessage, uiState.userError) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
        uiState.userError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    if (showDoctorDialog) {
        AlertDialog(
            onDismissRequest = { showDoctorDialog = false },
            title = { Text("Connect to Doctor") },
            text = {
                Column {
                    Text(
                        "Enter the 6-character invitation code provided by your doctor:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = doctorCodeInput,
                        onValueChange = { doctorCodeInput = it.uppercase() },
                        label = { Text("Invitation Code (e.g. DOC892)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (doctorCodeInput.isNotBlank()) {
                            viewModel.redeemDoctorInvite(doctorCodeInput)
                            showDoctorDialog = false
                            doctorCodeInput = ""
                        }
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDoctorDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClarifyDialog) {
        AlertDialog(
            onDismissRequest = { showClarifyDialog = false },
            title = { Text("Request Clarification") },
            text = {
                Column {
                    Text(
                        "Send a question or clarification note to your doctor regarding this proposal:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clarifyNoteInput,
                        onValueChange = { clarifyNoteInput = it },
                        label = { Text("Clarification Note") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (clarifyNoteInput.isNotBlank()) {
                            viewModel.requestClarification(activeClarifyProposalId, clarifyNoteInput)
                            showClarifyDialog = false
                            clarifyNoteInput = ""
                        }
                    }
                ) {
                    Text("Send Request")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClarifyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.home_title),
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
                                text = "Hello, ${user.name} (${user.role.name})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                actions = {
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
                        onConnectDoctor = { showDoctorDialog = true }
                    )
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

                // Section 1.6: Doctor Prescription Proposals (Stage 10B)
                if (uiState.pendingProposals.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Prescription Proposals (${uiState.pendingProposals.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "A doctor cannot activate medicines without your approval. Review and decide below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                uiState.pendingProposals.forEach { proposal ->
                                    val payload = remember(proposal.payloadJson) {
                                        com.meditrack.app.domain.model.PrescriptionProposalPayload.fromJson(proposal.payloadJson)
                                    }
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "${proposal.type.name}: ${payload.medicineName}",
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.titleSmall
                                                )
                                                Text(
                                                    text = "Rev ${proposal.revision} • ${proposal.status.name}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(
                                                text = "Dosage: ${payload.dosageText} • ${payload.pillsPerDose} pill(s) • ${payload.timesOfDay.joinToString(", ")}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            if (payload.instructions.isNotBlank()) {
                                                Text(
                                                    text = "Instructions: ${payload.instructions}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            if (!proposal.clarificationNote.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Clarification note: \"${proposal.clarificationNote}\"",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = { viewModel.acceptProposal(proposal.id) }) {
                                                    Text("Accept")
                                                }
                                                OutlinedButton(onClick = { viewModel.declineProposal(proposal.id) }) {
                                                    Text("Decline")
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        activeClarifyProposalId = proposal.id
                                                        showClarifyDialog = true
                                                    }
                                                ) {
                                                    Text("Clarify")
                                                }
                                            }
                                        }
                                    }
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
                                            if (!medicine.prescribedByDoctorName.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "Prescribed by Dr. ${medicine.prescribedByDoctorName}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
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
    onConnectDoctor: () -> Unit = {}
) {
    val addMedicineLabel = stringResource(R.string.action_add_medicine)
    val calendarLabel = stringResource(R.string.action_calendar)
    val historyLabel = stringResource(R.string.action_history)
    val caregiverLabel = stringResource(R.string.action_caregiver)
    val linkDoctorLabel = "Link Doctor"

    val actions = listOf(
        Pair(Icons.Default.AddCircle, addMedicineLabel),
        Pair(Icons.Default.Medication, "My Medicines"),
        Pair(Icons.Default.MedicalServices, linkDoctorLabel),
        Pair(Icons.Default.QrCodeScanner, stringResource(R.string.action_scan)),
        Pair(Icons.Default.CalendarMonth, calendarLabel),
        Pair(Icons.Default.History, historyLabel),
        Pair(Icons.Default.People, caregiverLabel),
        Pair(Icons.Default.Person, stringResource(R.string.action_profile))
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
                        linkDoctorLabel -> onConnectDoctor()
                        calendarLabel -> onNavigateToCalendar()
                        historyLabel -> onNavigateToHistory()
                        caregiverLabel -> onNavigateToCaregiver()
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
