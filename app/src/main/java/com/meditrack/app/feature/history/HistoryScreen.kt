package com.meditrack.app.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.meditrack.app.domain.model.DoseStatus
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Medication History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
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
                // Section 1: Time-Range Selector Chips
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HistoryTimeRange.values().forEach { range ->
                            FilterChip(
                                selected = uiState.timeRange == range,
                                onClick = { viewModel.setTimeRange(range) },
                                label = {
                                    Text(
                                        text = when (range) {
                                            HistoryTimeRange.TODAY -> "Today"
                                            HistoryTimeRange.WEEK -> "Past 7 Days"
                                            HistoryTimeRange.MONTH -> "Past 30 Days"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }

                // Section 2: Adherence Summary Card
                item {
                    AdherenceSummaryCard(
                        report = uiState.adherenceReport,
                        perMedicine = uiState.medicineAdherence
                    )
                }

                // Section 3: Records List Header
                item {
                    Text(
                        text = "Dose Records (${uiState.items.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Section 4: Records List or Empty State
                if (uiState.items.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No medication records found for this period.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.items, key = { it.dose.id }) { item ->
                        HistoryItemCard(
                            item = item,
                            onClick = { viewModel.selectItem(item) }
                        )
                    }
                }
            }
        }
    }

    // Detail Dialog
    uiState.selectedItem?.let { selected ->
        HistoryDetailDialog(
            item = selected,
            onDismiss = { viewModel.selectItem(null) }
        )
    }
}

@Composable
fun AdherenceSummaryCard(
    report: com.meditrack.app.domain.usecase.AdherenceReport,
    modifier: Modifier = Modifier,
    perMedicine: List<com.meditrack.app.domain.usecase.MedicineAdherence> = emptyList()
) {
    // Tapping the card opens the adherence of each medicine for the selected time range.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Medication Adherence",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = report.displayText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AdherenceCountItem(
                    label = "Taken",
                    count = report.verifiedTakenCount,
                    color = Color(0xFF2E7D32)
                )
                AdherenceCountItem(
                    label = "Missed",
                    count = report.missedCount,
                    color = MaterialTheme.colorScheme.error
                )
                AdherenceCountItem(
                    label = "Skipped",
                    count = report.skippedCount,
                    color = MaterialTheme.colorScheme.outline
                )
                if (report.unverifiedClosedCount > 0) {
                    AdherenceCountItem(
                        label = "Unverified",
                        count = report.unverifiedClosedCount,
                        color = Color(0xFFE65100)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (expanded) "▲ Tap to hide medicines" else "▼ Tap to see each medicine",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                    if (perMedicine.isEmpty()) {
                        Text(
                            text = "No doses recorded for any medicine in this period.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    } else {
                        perMedicine.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.medicineName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "Taken: ${entry.report.verifiedTakenCount} • Missed: ${entry.report.missedCount} • Skipped: ${entry.report.skippedCount}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                                Text(
                                    text = entry.report.displayText,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdherenceCountItem(
    label: String,
    count: Int,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun HistoryItemCard(
    item: HistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a")
        .withZone(ZoneId.systemDefault())
    val scheduledFormatted = formatter.format(Instant.ofEpochMilli(item.dose.scheduledAt))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.medicineName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (item.dosageText.isNotBlank()) {
                    Text(
                        text = item.dosageText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Scheduled: $scheduledFormatted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (item.dose.takenAt != null && (item.dose.status == DoseStatus.TAKEN || item.dose.status == DoseStatus.TAKEN_LATE)) {
                    val takenFormatted = formatter.format(Instant.ofEpochMilli(item.dose.takenAt))
                    Text(
                        text = "Taken: $takenFormatted",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.isLate) Color(0xFFE65100) else Color(0xFF2E7D32)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                HistoryStatusChip(item = item)
                if (!item.photoPath.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Photo proof attached",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Proof",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryStatusChip(item: HistoryItem) {
    val (label, containerColor, textColor) = when {
        item.isManualUnverified -> Triple(
            "Reported without photo",
            Color(0xFFFFECB3),
            Color(0xFFE65100)
        )
        item.dose.status == DoseStatus.TAKEN_LATE -> Triple(
            "Taken Late",
            Color(0xFFFFE0B2),
            Color(0xFFE65100)
        )
        item.dose.status == DoseStatus.TAKEN -> Triple(
            "Taken",
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32)
        )
        item.dose.status == DoseStatus.MISSED -> Triple(
            "Missed",
            Color(0xFFFFEBEE),
            Color(0xFFC62828)
        )
        item.dose.status == DoseStatus.SKIPPED -> Triple(
            "Skipped",
            Color(0xFFEEEEEE),
            Color(0xFF616161)
        )
        item.dose.status == DoseStatus.UNVERIFIED -> Triple(
            "Unverified",
            Color(0xFFFFF3E0),
            Color(0xFFEF6C00)
        )
        else -> Triple(
            "Pending",
            Color(0xFFE3F2FD),
            Color(0xFF1565C0)
        )
    }

    SuggestionChip(
        onClick = {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.Medium
            )
        },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = containerColor
        ),
        border = null
    )
}

@Composable
fun HistoryDetailDialog(
    item: HistoryItem,
    onDismiss: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a")
        .withZone(ZoneId.systemDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = item.medicineName, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.dosageText.isNotBlank()) {
                    Text(
                        text = "Dosage: ${item.dosageText}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "Scheduled: ${formatter.format(Instant.ofEpochMilli(item.dose.scheduledAt))}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (item.dose.takenAt != null) {
                    Text(
                        text = "Recorded at: ${formatter.format(Instant.ofEpochMilli(item.dose.takenAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isLate) Color(0xFFE65100) else Color(0xFF2E7D32)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Status: ", style = MaterialTheme.typography.bodyMedium)
                    HistoryStatusChip(item = item)
                }

                if (item.isLate) {
                    Text(
                        text = "Note: Taken late after window closed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100)
                    )
                }

                if (item.isManualUnverified) {
                    Text(
                        text = "Note: Marked as taken without photo verification. This dose remains missed for adherence calculations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100)
                    )
                }

                if (!item.photoPath.isNullOrBlank()) {
                    val file = File(item.photoPath)
                    if (file.exists()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Verification Proof Photo:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        AsyncImage(
                            model = file,
                            contentDescription = "Verification proof photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Close")
            }
        }
    )
}
