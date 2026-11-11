package com.meditrack.app.feature.caregiver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meditrack.app.domain.model.CaregiverLink
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.domain.model.LinkStatus
import com.meditrack.app.domain.model.UserRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaregiverScreen(
    onNavigateBack: () -> Unit,
    viewModel: CaregiverViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showInviteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.currentUserRole == UserRole.PATIENT) "Caregivers & Family Connections"
                        else "Caregiver Dashboard (Missed Doses Oversight)"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleRole) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Switch View (Patient / Caregiver)"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.currentUserRole == UserRole.PATIENT) {
                FloatingActionButton(onClick = { showInviteDialog = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Caregiver")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Role indicator banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.currentUserRole == UserRole.CAREGIVER) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (uiState.currentUserRole == UserRole.CAREGIVER) {
                                    "Active Role: Caregiver (Missed Doses Oversight)"
                                } else {
                                    "Active Role: Patient (Family Support Authority)"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (uiState.currentUserRole == UserRole.CAREGIVER) {
                                    "Caregiver interface strictly displays missed medication doses for connected patients."
                                } else {
                                    "Connected family members receive alerts when your scheduled doses are missed."
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.toggleRole() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.currentUserRole == UserRole.CAREGIVER) "Switch to Patient View" else "Switch to Caregiver View"
                        )
                    }
                }
            }

            if (uiState.currentUserRole == UserRole.PATIENT) {
                PatientCaregiverView(
                    connectedCaregivers = uiState.connectedCaregivers,
                    pendingInvitations = uiState.pendingInvitations,
                    generatedInviteCode = uiState.generatedInviteCode,
                    onGenerateInvite = { viewModel.generateCaregiverInvite() },
                    onConnectWithCode = { viewModel.connectWithCode(it) },
                    onAcceptConnection = { viewModel.acceptConnection(it) },
                    onDeclineConnection = { viewModel.declineConnection(it) },
                    onRevokeAccess = { viewModel.revokeCaregiver(it) },
                    onOpenInviteDialog = { showInviteDialog = true },
                    modifier = Modifier.weight(1f)
                )
            } else {
                CaregiverPortalView(
                    linkedPatients = uiState.linkedPatients,
                    selectedPatientId = uiState.selectedPatientId,
                    missedDoses = uiState.allConnectedMissedDoses,
                    generatedInviteCode = uiState.generatedInviteCode,
                    onGenerateInvite = { viewModel.generateCaregiverInvite() },
                    onSelectPatient = viewModel::selectPatient,
                    onConnectWithCode = { viewModel.connectWithCode(it) },
                    onRevokePatient = { viewModel.revokeCaregiver(it) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (showInviteDialog) {
                AddCaregiverDialog(
                    onDismiss = { showInviteDialog = false },
                    onSend = { name, contact, relationship ->
                        viewModel.sendInvitation(name, contact, relationship)
                        showInviteDialog = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PatientCaregiverView(
    connectedCaregivers: List<CaregiverLink>,
    pendingInvitations: List<CaregiverLink>,
    generatedInviteCode: String?,
    onGenerateInvite: () -> Unit,
    onConnectWithCode: (String) -> Unit,
    onAcceptConnection: (String) -> Unit,
    onDeclineConnection: (String) -> Unit,
    onRevokeAccess: (String) -> Unit,
    onOpenInviteDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var codeInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Connect Caregiver Pipeline
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connect with Family Member or Caregiver",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enter a caregiver invitation code or generate one to share with your loved one.",
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
                    if (generatedInviteCode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Code: $generatedInviteCode",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Section: Connected Caregivers
        item {
            Text(
                text = "Connected Caregivers (${connectedCaregivers.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (connectedCaregivers.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No caregivers connected yet",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onOpenInviteDialog) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Invite Caregiver Manually")
                        }
                    }
                }
            }
        } else {
            items(connectedCaregivers, key = { it.id }) { link ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(link.caregiverName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Relationship: ${link.relationship} • Status: Connected", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = { onRevokeAccess(link.id) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Revoke")
                            }
                        }
                    }
                }
            }
        }

        // Section: Pending Invitations
        if (pendingInvitations.isNotEmpty()) {
            item {
                Text(
                    text = "Pending Invitations (${pendingInvitations.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(pendingInvitations, key = { it.id }) { link ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(link.caregiverName, fontWeight = FontWeight.Bold)
                            Text("Status: PENDING", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE65100))
                        }
                        Row {
                            Button(onClick = { onAcceptConnection(link.id) }) {
                                Text("Accept")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = { onDeclineConnection(link.id) }) {
                                Text("Decline")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaregiverPortalView(
    linkedPatients: List<CaregiverLink>,
    selectedPatientId: String?,
    missedDoses: List<MissedDoseItem>,
    generatedInviteCode: String?,
    onGenerateInvite: () -> Unit,
    onSelectPatient: (String) -> Unit,
    onConnectWithCode: (String) -> Unit,
    onRevokePatient: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var codeInput by remember { mutableStateOf("") }
    val displayMissed = if (selectedPatientId != null) {
        missedDoses.filter { it.patientId == selectedPatientId }
    } else {
        missedDoses
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Caregiver View (Missed Doses Oversight)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "This interface strictly tracks missed medication doses for connected patients so you can follow up quickly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Section: Invitation Pipeline
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Patient Connection Pipeline",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Generate a code for your patient, or enter their invitation code to link:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it },
                            label = { Text("Invitation Code (e.g. CG-1234)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
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
                    Button(onClick = onGenerateInvite) {
                        Text("Generate Invitation Code")
                    }
                    if (generatedInviteCode != null) {
                        Text(
                            text = "Active Code: $generatedInviteCode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Section: Connected Patients
        item {
            Text(
                text = "Connected Loved Ones & Patients (${linkedPatients.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (linkedPatients.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No linked patients connected yet. Use the connection pipeline above to link a patient.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(linkedPatients, key = { it.id }) { link ->
                val isSelected = link.patientId == selectedPatientId
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ),
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
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Relationship: ${link.relationship} • Status: CONNECTED",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(onClick = { onSelectPatient(link.patientId) }) {
                            Text(if (isSelected) "Selected" else "Filter Patient")
                        }
                    }
                }
            }
        }

        // Section: MISSED DOSES ONLY
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Missed Medication Alerts (${displayMissed.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (displayMissed.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "All Clear! No Missed Doses",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20)
                            )
                            Text(
                                text = "All connected patients have taken their scheduled medications.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }
            }
        } else {
            items(displayMissed, key = { it.id }) { item ->
                val timeStr = DateTimeFormatter.ofPattern("hh:mm a • MMM d")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(item.dose.scheduledAt))

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
                                    contentDescription = "Missed Dose Alert",
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
                            text = "Patient: ${item.patientId} • Scheduled: $timeStr",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Urgent: Dose verification window elapsed. Please contact the patient to ensure safety.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCaregiverDialog(
    onDismiss: () -> Unit,
    onSend: (name: String, contact: String, relationship: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var selectedRelationship by remember { mutableStateOf("Family") }

    val relationships = listOf("Spouse", "Son", "Daughter", "Parent", "Sibling", "Friend", "Other")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Family Member / Caregiver") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Caregiver Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    label = { Text("Email or Phone Number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Relationship:", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    relationships.take(4).forEach { rel ->
                        SuggestionChip(
                            onClick = { selectedRelationship = rel },
                            label = { Text(rel) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (selectedRelationship == rel) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && contact.isNotBlank()) {
                        onSend(name, contact, selectedRelationship)
                    }
                }
            ) {
                Text("Send Invitation")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
