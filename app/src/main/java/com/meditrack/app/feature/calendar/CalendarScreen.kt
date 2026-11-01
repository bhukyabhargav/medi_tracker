package com.meditrack.app.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.meditrack.app.domain.model.DoseStatus
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onNavigateBack: () -> Unit,
    onNavigateToVerification: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Medication Calendar",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home"
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
                // Section 1: Month View Card
                item {
                    MonthCalendarCard(
                        currentMonth = uiState.currentMonth,
                        selectedDate = uiState.selectedDate,
                        dayStatusMap = uiState.dayStatusMap,
                        onPreviousMonth = { viewModel.previousMonth() },
                        onNextMonth = { viewModel.nextMonth() },
                        onSelectDate = { date -> viewModel.selectDate(date) }
                    )
                }

                // Section 2: Legend
                item {
                    CalendarLegendRow()
                }

                // Section 3: Selected Date Header
                item {
                    val dateFormatted = DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy")
                        .format(uiState.selectedDate)
                    Text(
                        text = "Doses for $dateFormatted",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Section 4: Doses for Selected Date
                if (uiState.selectedDateDoses.isEmpty()) {
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
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No medication scheduled for this date.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.selectedDateDoses, key = { it.dose.id }) { item ->
                        CalendarDoseCard(
                            item = item,
                            onClick = { viewModel.selectDoseDetail(item) },
                            onLogLate = { onNavigateToVerification(item.dose.id) }
                        )
                    }
                }
            }
        }
    }

    // Detail Dialog ("Back to Calendar")
    uiState.selectedDoseDetail?.let { detail ->
        CalendarDoseDetailDialog(
            item = detail,
            onDismiss = { viewModel.selectDoseDetail(null) }
        )
    }
}

@Composable
fun MonthCalendarCard(
    currentMonth: YearMonth,
    selectedDate: LocalDate,
    dayStatusMap: Map<LocalDate, CalendarDayStatus>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Month Header with Prev/Next
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous Month"
                    )
                }
                Text(
                    text = "${currentMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${currentMonth.year}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onNextMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next Month"
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Weekday labels: Sun Mon Tue Wed Thu Fri Sat
            val daysOfWeek = listOf(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                daysOfWeek.forEach { dayOfWeek ->
                    Text(
                        text = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Days Grid
            val firstDayOfMonth = currentMonth.atDay(1)
            val firstDayOfWeekValue = firstDayOfMonth.dayOfWeek.value % 7 // Sunday = 0
            val daysInMonth = currentMonth.lengthOfMonth()

            val totalCells = firstDayOfWeekValue + daysInMonth
            val totalRows = (totalCells + 6) / 7

            for (row in 0 until totalRows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    for (col in 0..6) {
                        val cellIndex = row * 7 + col
                        val dayNumber = cellIndex - firstDayOfWeekValue + 1

                        if (dayNumber in 1..daysInMonth) {
                            val date = currentMonth.atDay(dayNumber)
                            val isSelected = date == selectedDate
                            val status = dayStatusMap[date] ?: CalendarDayStatus.NONE

                            DayCell(
                                dayNumber = dayNumber,
                                isSelected = isSelected,
                                status = status,
                                onClick = { onSelectDate(date) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            // Blank cell
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DayCell(
    dayNumber: Int,
    isSelected: Boolean,
    status: CalendarDayStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dotColor = when (status) {
        CalendarDayStatus.ALL_TAKEN -> Color(0xFF2E7D32) // Green
        CalendarDayStatus.SOME_LATE -> Color(0xFFE65100) // Orange
        CalendarDayStatus.MISSED -> Color(0xFFC62828)    // Red
        CalendarDayStatus.NONE -> Color(0xFFBDBDBD)      // Grey
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .then(
                if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = dayNumber.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}

@Composable
fun CalendarLegendRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDotItem(color = Color(0xFF2E7D32), label = "All taken")
        LegendDotItem(color = Color(0xFFE65100), label = "Late / Unverified")
        LegendDotItem(color = Color(0xFFC62828), label = "Missed")
        LegendDotItem(color = Color(0xFFBDBDBD), label = "None")
    }
}

@Composable
fun LegendDotItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun CalendarDoseCard(
    item: CalendarDoseItem,
    onClick: () -> Unit,
    onLogLate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ofPattern("hh:mm a")
        .withZone(ZoneId.systemDefault())
    val scheduledFormatted = formatter.format(Instant.ofEpochMilli(item.dose.scheduledAt))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.medicine?.name ?: "Unknown Medicine",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    item.medicine?.let { med ->
                        Text(
                            text = "${med.dosageText} • ${med.foodRelation.name.replace("_", " ").lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Scheduled at $scheduledFormatted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                CalendarStatusChip(dose = item.dose)
            }

            // Late dose actions
            if (item.canLogLate) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onLogLate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("I took this late", style = MaterialTheme.typography.labelMedium)
                }
            } else if (item.isLocked) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Locked (Late entry window expired)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun CalendarStatusChip(dose: com.meditrack.app.domain.model.DoseEvent) {
    val (label, containerColor, textColor) = when {
        dose.manualUnverified -> Triple(
            "Reported without photo",
            Color(0xFFFFECB3),
            Color(0xFFE65100)
        )
        dose.status == DoseStatus.TAKEN_LATE -> Triple(
            "Taken Late",
            Color(0xFFFFE0B2),
            Color(0xFFE65100)
        )
        dose.status == DoseStatus.TAKEN -> Triple(
            "Taken",
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32)
        )
        dose.status == DoseStatus.MISSED -> Triple(
            "Missed",
            Color(0xFFFFEBEE),
            Color(0xFFC62828)
        )
        dose.status == DoseStatus.SKIPPED -> Triple(
            "Skipped",
            Color(0xFFEEEEEE),
            Color(0xFF616161)
        )
        dose.status == DoseStatus.UNVERIFIED -> Triple(
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
fun CalendarDoseDetailDialog(
    item: CalendarDoseItem,
    onDismiss: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a")
        .withZone(ZoneId.systemDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = item.medicine?.name ?: "Medicine Detail", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item.medicine?.let { med ->
                    Text(
                        text = "Dosage: ${med.dosageText}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Instructions: ${med.foodRelation.name.replace("_", " ").lowercase()}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = "Scheduled: ${formatter.format(Instant.ofEpochMilli(item.dose.scheduledAt))}",
                    style = MaterialTheme.typography.bodySmall
                )

                if (item.dose.takenAt != null) {
                    val isLate = item.dose.status == DoseStatus.TAKEN_LATE || item.dose.lateEntry
                    Text(
                        text = "Recorded at: ${formatter.format(Instant.ofEpochMilli(item.dose.takenAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isLate) Color(0xFFE65100) else Color(0xFF2E7D32)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Status: ", style = MaterialTheme.typography.bodyMedium)
                    CalendarStatusChip(dose = item.dose)
                }

                if (!item.dose.verifiedPhotoPath.isNullOrBlank()) {
                    val file = File(item.dose.verifiedPhotoPath)
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
                                .height(180.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Back to Calendar")
            }
        }
    )
}
