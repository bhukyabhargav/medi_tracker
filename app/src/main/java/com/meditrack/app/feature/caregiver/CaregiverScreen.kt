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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.DoseStatus
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
                        if (uiState.currentUserRole == UserRole.PATIENT) "Caregivers & Family"
                        else "Caregiver Portal"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Quick toggle to preview Patient vs Caregiver view on single device
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.currentUserRole == UserRole.PATIENT) {
                PatientCaregiverView(
                    connectedCaregivers = uiState.connectedCaregivers,
                    pendingInvitations = uiState.pendingInvitations,
                    onSimulateResponse = viewModel::simulateCaregiverResponse,
                    onOpenInviteDialog = { showInviteDialog = true }
                )
            } else {
                CaregiverPortalView(
                    linkedPatients = uiState.linkedPatients,
                    selectedPatientId = uiState.selectedPatientId,
                    selectedDoses = uiState.selectedPatientDoses,
                    adherenceReport = uiState.adherenceReport,
                    onSelectPatient = viewModel::selectPatient
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
    onSimulateResponse: (String, Boolean) -> Unit,
    onOpenInviteDialog: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Family & Caregiver Support",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Connected caregivers receive automatic alerts when a dose is missed or medication runs low, helping ensure safe adherence.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                            Text("Invite Caregiver")
                        }
                    }
                }
            }
        } else {
            items(connectedCaregivers, key = { it.id }) { link ->
                CaregiverItemCard(
                    link = link,
                    isConnected = true,
                    onSimulateResponse = onSimulateResponse
                )
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
                CaregiverItemCard(
                    link = link,
                    isConnected = false,
                    onSimulateResponse = onSimulateResponse
                )
            }
        }
    }
}

@Composable
private fun CaregiverItemCard(
    link: CaregiverLink,
    isConnected: Boolean,
    onSimulateResponse: (String, Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = link.caregiverName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${link.relationship} \u2022 ${link.caregiverContact}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isConnected) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Connected") },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF2E7D32)
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFFE8F5E9),
                            labelColor = Color(0xFF2E7D32)
                        )
                    )
                } else {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Pending") },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFFE65100)
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFFFFF3E0),
                            labelColor = Color(0xFFE65100)
                        )
                    )
                }
            }

            // In debug simulation mode: Allow accepting/rejecting directly
            if (!isConnected) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { onSimulateResponse(link.id, false) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Simulate Reject")
                    }
                    Button(
                        onClick = { onSimulateResponse(link.id, true) }
                    ) {
                        Text("Simulate Accept")
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
    selectedDoses: List<DoseEvent>,
    adherenceReport: com.meditrack.app.domain.usecase.AdherenceReport?,
    onSelectPatient: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
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
                            text = "Caregiver View (Read-Only)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Monitor linked patients' medication adherence and intake records in real time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Patients in Your Care (${linkedPatients.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (linkedPatients.isEmpty()) {
            item {
                Text(
                    text = "No linked patients currently assigned.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(linkedPatients, key = { it.id }) { link ->
                val isSelected = link.patientId == selectedPatientId
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    onClick = { onSelectPatient(link.patientId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Patient: ${link.patientId}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Relationship: ${link.relationship} \u2022 Status: Connected",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Selected Patient Details
        if (selectedPatientId != null) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Today's Patient Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (adherenceReport != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Medication Adherence: ${adherenceReport.percentageFormatted}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Taken: ${adherenceReport.verifiedTakenCount} \u2022 Missed: ${adherenceReport.missedCount} \u2022 Skipped: ${adherenceReport.skippedCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(selectedDoses, key = { it.id }) { dose ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val timeStr = DateTimeFormatter.ofPattern("hh:mm a")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(dose.scheduledAt))
                            Text(
                                text = "Scheduled: $timeStr",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val (statusText, statusColor) = when (dose.status) {
                            DoseStatus.TAKEN -> Pair("Taken", Color(0xFF2E7D32))
                            DoseStatus.TAKEN_LATE -> Pair("Taken Late", Color(0xFFE65100))
                            DoseStatus.MISSED -> Pair("Missed", Color(0xFFC62828))
                            DoseStatus.SKIPPED -> Pair("Skipped", Color.Gray)
                            DoseStatus.PENDING -> Pair("Pending", Color(0xFF1565C0))
                            DoseStatus.UNVERIFIED -> Pair("Unverified", Color(0xFFE65100))
                        }

                        SuggestionChip(
                            onClick = {},
                            label = { Text(statusText) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = statusColor.copy(alpha = 0.15f),
                                labelColor = statusColor
                            )
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
                    label = { Text("Phone Number or Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text("Relationship:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    relationships.take(3).forEach { rel ->
                        FilterChip(
                            selected = selectedRelationship == rel,
                            onClick = { selectedRelationship = rel },
                            label = { Text(rel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    relationships.drop(3).take(3).forEach { rel ->
                        FilterChip(
                            selected = selectedRelationship == rel,
                            onClick = { selectedRelationship = rel },
                            label = { Text(rel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(name, contact, selectedRelationship) },
                enabled = name.isNotBlank() && contact.isNotBlank()
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
