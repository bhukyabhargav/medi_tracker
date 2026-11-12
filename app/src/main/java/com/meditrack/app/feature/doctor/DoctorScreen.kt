package com.meditrack.app.feature.doctor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meditrack.app.domain.model.DoctorLink
import com.meditrack.app.domain.model.DoctorLinkStatus
import com.meditrack.app.domain.model.DoseEvent
import com.meditrack.app.domain.model.Medicine
import com.meditrack.app.domain.model.MedicineProposal
import com.meditrack.app.domain.model.PrescriptionPayload
import com.meditrack.app.domain.model.ProposalStatus
import com.meditrack.app.domain.model.ProposalType
import com.meditrack.app.domain.model.UserRole
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorScreen(
    viewModel: DoctorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAddMedicineFromProposal: ((MedicineProposal, PrescriptionPayload) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showProposalDialog by remember { mutableStateOf(false) }
    var showClarificationDialog by remember { mutableStateOf(false) }
    var selectedProposalIdForClarification by remember { mutableStateOf<String?>(null) }
    var inviteCodeInput by remember { mutableStateOf("") }

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
                        if (uiState.currentUserRole == UserRole.DOCTOR) {
                            "Doctor Dashboard (Read-Only)"
                        } else {
                            "Doctor Connection & Proposals"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleRole() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Switch Role (Doctor / Patient)"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Role indicator banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.currentUserRole == UserRole.DOCTOR) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
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
                                text = if (uiState.currentUserRole == UserRole.DOCTOR) {
                                "Active Role: Doctor (Monitoring & Proposals)"
                            } else {
                                "Active Role: Patient (Prescription Decision Authority)"
                            },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (uiState.currentUserRole == UserRole.DOCTOR) {
                                Text(
                                    text = "MediTrack does not verify medical licenses. Doctor proposals require explicit patient acceptance.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.toggleRole() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.currentUserRole == UserRole.DOCTOR) "Switch to Patient View" else "Switch to Doctor View"
                        )
                    }
                }
            }

            if (uiState.currentUserRole == UserRole.DOCTOR) {
                DoctorDashboardContent(
                    uiState = uiState,
                    onGenerateInvite = { viewModel.generateDoctorInvite() },
                    onSelectPatient = { viewModel.selectPatient(it) },
                    onOpenProposalDialog = { showProposalDialog = true }
                )
            } else {
                PatientDoctorContent(
                    uiState = uiState,
                    inviteCodeInput = inviteCodeInput,
                    onInviteCodeChange = { inviteCodeInput = it },
                    onConnectWithCode = {
                        viewModel.patientConnectWithCode(inviteCodeInput)
                        inviteCodeInput = ""
                    },
                    onAcceptConnection = { viewModel.acceptDoctorConnection(it) },
                    onDeclineConnection = { viewModel.declineDoctorConnection(it) },
                    onRevokeAccess = { viewModel.revokeDoctorAccess(it) },
                    onAcceptProposal = { proposal ->
                        val payload = try {
                            PrescriptionPayload.fromJson(proposal.payloadJson)
                        } catch (_: Exception) {
                            null
                        }
                        if (proposal.type == ProposalType.ADD && payload != null && onNavigateToAddMedicineFromProposal != null) {
                            onNavigateToAddMedicineFromProposal(proposal, payload)
                        } else {
                            viewModel.acceptProposal(proposal.id)
                        }
                    },
                    onDeclineProposal = { viewModel.declineProposal(it) },
                    onOpenClarification = {
                        selectedProposalIdForClarification = it
                        showClarificationDialog = true
                    }
                )
            }
        }
    }

    if (showProposalDialog && uiState.selectedPatientId != null) {
        CreateProposalDialog(
            patientId = uiState.selectedPatientId!!,
            onDismiss = { showProposalDialog = false },
            onSubmit = { type, payload, medicineId ->
                viewModel.createPrescriptionProposal(uiState.selectedPatientId!!, type, payload, medicineId)
                showProposalDialog = false
            }
        )
    }

    if (showClarificationDialog && selectedProposalIdForClarification != null) {
        var noteInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showClarificationDialog = false },
            title = { Text("Request Clarification") },
            text = {
                Column {
                    Text("Enter your question or clarification request for the doctor:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. Can I take this with meals?") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteInput.isNotBlank()) {
                            viewModel.requestClarification(selectedProposalIdForClarification!!, noteInput)
                            showClarificationDialog = false
                            selectedProposalIdForClarification = null
                        }
                    }
                ) {
                    Text("Send Request")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClarificationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DoctorDashboardContent(
    uiState: DoctorUiState,
    onGenerateInvite: () -> Unit,
    onSelectPatient: (String) -> Unit,
    onOpenProposalDialog: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Patient Invitation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Generate a secure invitation code for your patient to connect.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onGenerateInvite) {
                        Text("Generate Invitation Code")
                    }
                    if (uiState.generatedInviteCode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Code: ${uiState.generatedInviteCode}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Connected Patients",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = { onSelectPatient("demo_patient_001") }) {
                    Text("Link Demo Patient")
                }
            }
        }

        if (uiState.acceptedPatients.isEmpty()) {
            item {
                Text(
                    "No accepted patients yet. Patients with pending, declined, or revoked links are not accessible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(uiState.acceptedPatients, key = { it.id }) { link ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = { onSelectPatient(link.patientId) }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Patient ID: ${link.patientId}", fontWeight = FontWeight.Bold)
                            Text("Status: ACCEPTED", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Button(onClick = { onSelectPatient(link.patientId) }) {
                            Text("View")
                        }
                    }
                }
            }
        }

        if (uiState.selectedPatientId != null) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Patient Details (Read-Only)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Button(onClick = onOpenProposalDialog) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Propose Prescription")
                    }
                }
            }

            if (uiState.selectedPatientAdherence != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Adherence: ${uiState.selectedPatientAdherence.percentageFormatted}", fontWeight = FontWeight.Bold)
                            Text("Taken: ${uiState.selectedPatientAdherence.verifiedTakenCount} • Missed: ${uiState.selectedPatientAdherence.missedCount} • Skipped: ${uiState.selectedPatientAdherence.skippedCount}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Text("Today's Doses:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }

            items(uiState.selectedPatientDoses, key = { it.id }) { dose ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Dose: ${dose.id.take(8)}")
                        Text("Status: ${dose.status.name}", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Active Medicines:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }

            items(uiState.selectedPatientMedicines, key = { it.id }) { med ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(med.name, fontWeight = FontWeight.Bold)
                        Text("Stock: ${med.currentStock} pills")
                    }
                }
            }
        }
    }
}

@Composable
fun PatientDoctorContent(
    uiState: DoctorUiState,
    inviteCodeInput: String,
    onInviteCodeChange: (String) -> Unit,
    onConnectWithCode: () -> Unit,
    onAcceptConnection: (String) -> Unit,
    onDeclineConnection: (String) -> Unit,
    onRevokeAccess: (String) -> Unit,
    onAcceptProposal: (MedicineProposal) -> Unit,
    onDeclineProposal: (String) -> Unit,
    onOpenClarification: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connect with Doctor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Enter doctor invitation code (e.g. DOC-1234):", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = inviteCodeInput,
                            onValueChange = onInviteCodeChange,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("DOC-xxxx") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onConnectWithCode) {
                            Text("Connect")
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Doctor Connections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (uiState.incomingDoctorLinks.isEmpty()) {
            item {
                Text("No doctor connections yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(uiState.incomingDoctorLinks, key = { it.id }) { link ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(link.doctorName, fontWeight = FontWeight.Bold)
                            Text("Status: ${link.status.name}", color = MaterialTheme.colorScheme.primary)
                        }
                        Text("Contact: ${link.doctorContact}", style = MaterialTheme.typography.bodySmall)

                        Spacer(modifier = Modifier.height(8.dp))
                        when (link.status) {
                            DoctorLinkStatus.PENDING -> {
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
                            DoctorLinkStatus.ACCEPTED -> {
                                OutlinedButton(
                                    onClick = { onRevokeAccess(link.id) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Revoke Access")
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Prescription Proposals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        val proposals = uiState.patientProposals
        if (proposals.isEmpty()) {
            item {
                Text("No prescription proposals received.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(proposals, key = { it.id }) { prop ->
                val payload = try {
                    PrescriptionPayload.fromJson(prop.payloadJson)
                } catch (_: Exception) {
                    null
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(payload?.medicineName ?: "Prescription", fontWeight = FontWeight.Bold)
                            Text("Type: ${prop.type.name} • ${prop.status.name}")
                        }
                        Text("Dosage: ${payload?.dosageText} • Pills per session: ${payload?.pillsPerDose}", style = MaterialTheme.typography.bodySmall)
                        Text("Time to take: ${payload?.times?.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        Text("Stock & Blister Photo: Uploaded by patient upon accepting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (payload?.clarificationNote != null) {
                            Text("Note: ${payload.clarificationNote}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }

                        if (prop.status == ProposalStatus.PENDING || prop.status == ProposalStatus.CLARIFICATION_REQUESTED) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = { onAcceptProposal(prop) }) {
                                    Text("Accept")
                                }
                                OutlinedButton(onClick = { onDeclineProposal(prop.id) }) {
                                    Text("Decline")
                                }
                                TextButton(onClick = { onOpenClarification(prop.id) }) {
                                    Text("Ask Note")
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
fun CreateProposalDialog(
    patientId: String,
    onDismiss: () -> Unit,
    onSubmit: (ProposalType, PrescriptionPayload, String?) -> Unit
) {
    var medName by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("500mg") }
    var pillsCount by remember { mutableStateOf("1") }
    var timeInput by remember { mutableStateOf("09:00") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Propose Prescription (Doctor Mode)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Doctor Mode Scope: You can specify only the medicine name, dosage, number of pills per session, and schedule time.\n\nMedicine stock and baseline blister strip photo will be uploaded by the patient upon accepting this prescription.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                OutlinedTextField(
                    value = medName,
                    onValueChange = { medName = it },
                    label = { Text("Medicine Name (e.g. Amoxicillin)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text("Dosage / Strength (e.g. 500mg, 10ml)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = pillsCount,
                    onValueChange = { pillsCount = it },
                    label = { Text("No of pills per session (e.g. 1)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = timeInput,
                    onValueChange = { timeInput = it },
                    label = { Text("Time to take that medicine (HH:mm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Patient Responsibilities on Acceptance:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Enter current physical stock count\n• Upload/capture baseline blister strip photo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (medName.isNotBlank()) {
                        val payload = PrescriptionPayload(
                            medicineName = medName.trim(),
                            dosageText = dosage.trim(),
                            pillsPerDose = pillsCount.toIntOrNull() ?: 1,
                            times = listOf(timeInput.trim()),
                            startDate = LocalDate.now().toString(),
                            additionalInstructions = null
                        )
                        onSubmit(ProposalType.ADD, payload, null)
                    }
                }
            ) {
                Text("Submit Proposal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
