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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToDebugTools: () -> Unit,
    onNavigateToAddMedicine: () -> Unit = {},
    onNavigateToMedicinesList: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
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
                        onNavigateToMedicinesList = onNavigateToMedicinesList
                    )
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
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = timeStr,
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
                            uiState.missedDoses.forEach { dose ->
                                val timeStr = DateTimeFormatter.ofPattern("hh:mm a")
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(dose.scheduledAt))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Missed: $timeStr",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
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
    onNavigateToMedicinesList: () -> Unit = {}
) {
    val addMedicineLabel = stringResource(R.string.action_add_medicine)
    val actions = listOf(
        Pair(Icons.Default.AddCircle, addMedicineLabel),
        Pair(Icons.Default.Medication, "My Medicines"),
        Pair(Icons.Default.QrCodeScanner, stringResource(R.string.action_scan)),
        Pair(Icons.Default.CalendarMonth, stringResource(R.string.action_calendar)),
        Pair(Icons.Default.History, stringResource(R.string.action_history)),
        Pair(Icons.Default.People, stringResource(R.string.action_caregiver)),
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
