package com.meditrack.app.feature.verification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meditrack.app.BuildConfig
import com.meditrack.app.domain.model.DoseStatus
import com.meditrack.app.feature.verification.pipeline.ForceDetectorBehavior
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VerificationStubScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCapture: (String) -> Unit = {},
    capturedPhotoPath: String? = null,
    onClearCapturedPhoto: () -> Unit = {},
    viewModel: VerificationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showManualUnverifiedDialog by remember { mutableStateOf(false) }
    var showUseAnywayDialog by remember { mutableStateOf(false) }

    if (uiState.showDoubleDoseWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDoubleDoseWarning() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Double-Dose Warning") },
            text = {
                Text(
                    "Your next dose of ${uiState.medicine?.name ?: "this medicine"} is scheduled soon. " +
                    "Check with your doctor before taking this one. Only proceed if you already took this dose earlier."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmDoubleDoseWarning()
                        onNavigateToCapture(viewModel.doseId)
                    }
                ) {
                    Text("I already took it")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDoubleDoseWarning() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showUseAnywayDialog) {
        AlertDialog(
            onDismissRequest = { showUseAnywayDialog = false },
            title = { Text("Use this photo anyway?") },
            text = { Text("The photo could not be verified automatically. Confirm only if you really took this dose. It will be recorded as taken.") },
            confirmButton = {
                Button(onClick = {
                    showUseAnywayDialog = false
                    viewModel.useRejectedPhotoAnyway()
                }) { Text("Yes, I took it") }
            },
            dismissButton = {
                TextButton(onClick = { showUseAnywayDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showManualUnverifiedDialog) {
        AlertDialog(
            onDismissRequest = { showManualUnverifiedDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("Report Without Photo?") },
            text = {
                Text(
                    "This will record that you took this dose, but it will remain unverified " +
                    "and will not count toward your adherence score."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showManualUnverifiedDialog = false
                        viewModel.recordManualUnverified()
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualUnverifiedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LaunchedEffect(capturedPhotoPath) {
        if (!capturedPhotoPath.isNullOrBlank()) {
            if (uiState.needsBaselinePhoto) {
                viewModel.recordBaselineEnrollment(capturedPhotoPath)
            } else {
                viewModel.processCapturedPhoto(capturedPhotoPath)
            }
            onClearCapturedPhoto()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dose Verification") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.errorMessage != null && uiState.dose == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = uiState.errorMessage ?: "Unknown error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            val dose = uiState.dose
            val medicine = uiState.medicine

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Medicine & Schedule summary card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.MedicalServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = medicine?.name ?: "Unknown Medicine",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (medicine != null) {
                            Text(
                                text = "Dosage: ${medicine.dosageText} • ${medicine.pillsPerDose} pill(s)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Instructions: Take ${medicine.foodRelation.name.lowercase().replaceFirstChar { it.uppercase() }} food",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Current stock remaining: ${medicine.currentStock}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (dose != null) {
                            val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a")
                                .withZone(ZoneId.systemDefault())
                            val scheduledTimeStr = formatter.format(Instant.ofEpochMilli(dose.scheduledAt))
                            val windowEndsStr = formatter.format(Instant.ofEpochMilli(dose.windowEndsAt))

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Scheduled for: $scheduledTimeStr",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Window closes: $windowEndsStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "Status: ${dose.status.name} • Attempts: ${dose.verificationAttempts} / 3",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = when (dose.status) {
                                    DoseStatus.TAKEN, DoseStatus.TAKEN_LATE -> Color(0xFF2E7D32)
                                    DoseStatus.MISSED, DoseStatus.UNVERIFIED -> MaterialTheme.colorScheme.error
                                    DoseStatus.SKIPPED -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                }

                // If medicine lacks baseline photo: request enrollment photo first
                if (uiState.needsBaselinePhoto) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Baseline Photo Required",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Enrollment was skipped for this medicine. Please photograph your full blister strip before verifying intake.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { onNavigateToCapture(viewModel.doseId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Photograph Baseline Strip")
                            }
                        }
                    }
                }

                // Analyzing indicator
                if (uiState.isAnalyzing) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Analyzing Blister Strip...",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Checking quality & blister cell ledger...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }

                // Success Result State
                if (uiState.isSuccess) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFE8F5E9)
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
                                    text = uiState.infoMessage ?: "Dose recorded ✔",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B5E20)
                                )
                                Text(
                                    text = "Intake verified. Stock updated to ${medicine?.currentStock}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onNavigateBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Return to Home")
                    }
                } else if (uiState.isExhausted) {
                    // 3-Strikes Exhausted (UNVERIFIED)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Verification Attempts Exhausted",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "We couldn't verify this dose after 3 attempts. Your caregiver has been notified.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onNavigateBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done")
                    }
                } else if (uiState.isFailed) {
                    // Rejection with retries left
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFE65100),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Verification Rejected (${uiState.rejectReason?.name})",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFBF360C)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = uiState.errorMessage ?: "Verification check failed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFBF360C)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${uiState.attemptsRemaining} attempt(s) remaining.",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFE65100)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { onNavigateToCapture(viewModel.doseId) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retake Photo")
                    }
                    if (uiState.errorMessage?.contains("new strip", ignoreCase = true) == true) {
                        Button(
                            onClick = { viewModel.confirmNewStripAndVerify() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("I Started a New Strip (Confirm & Verify)")
                        }
                    }
                    if (uiState.lastRejectedPhotoPath != null) {
                        OutlinedButton(
                            onClick = { showUseAnywayDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use Photo Anyway & Mark as Taken")
                        }
                    }
                } else if (uiState.isLocked) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Late Entry Period Expired",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "The allowable late entry cutoff for this dose has ended. This dose is locked as Missed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (uiState.isManualUnverified) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFFE65100)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Reported without photo",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Recorded in history as manual unverified (status remains Missed).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE65100)
                                )
                            }
                        }
                    }
                } else if (!uiState.isSuccess && !uiState.needsBaselinePhoto) {
                    // Photo Verification Button
                    Button(
                        onClick = {
                            viewModel.requestCapturePhoto {
                                onNavigateToCapture(viewModel.doseId)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isAnalyzing,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (uiState.isLateMode) "Capture Late Blister Photo" else "Take Live Blister Photo")
                    }

                    if (uiState.isLateMode) {
                        // Late Mode option: Mark as taken (unverified)
                        OutlinedButton(
                            onClick = { showManualUnverifiedDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isAnalyzing
                        ) {
                            Text("Mark as taken (unverified)")
                        }
                    } else {
                        // Pending Mode: Snooze & Skip options
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.snooze(15) },
                                modifier = Modifier.weight(1f),
                                enabled = !uiState.isAnalyzing
                            ) {
                                Text("Snooze 15m")
                            }

                            OutlinedButton(
                                onClick = { viewModel.snooze(30) },
                                modifier = Modifier.weight(1f),
                                enabled = !uiState.isAnalyzing
                            ) {
                                Text("Snooze 30m")
                            }
                        }

                        OutlinedButton(
                            onClick = { viewModel.skip() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isAnalyzing
                        ) {
                            Text("Skip Dose")
                        }
                    }
                }

                // Debug Tools: Force Detector Result menu
                if (BuildConfig.DEBUG && !uiState.isSuccess && !uiState.isExhausted) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Debug: Force Detector Result",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Override fake detector output to test verification pathways:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ForceDetectorBehavior.entries.forEach { behavior ->
                                    FilterChip(
                                        selected = uiState.forcedBehavior == behavior,
                                        onClick = { viewModel.setForcedDetectorBehavior(behavior) },
                                        label = { Text(behavior.name.removePrefix("FORCE_").lowercase().replace('_', ' ')) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.simulateVerifiedTake() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.isSimulating
                            ) {
                                Text("Simulate Verified Intake (Bypass)")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Return to App")
                }
            }
        }
    }
}
