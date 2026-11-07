package com.meditrack.app.feature.medicine

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meditrack.app.R
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditMedicineScreen(
    medicineId: String? = null,
    prefillName: String? = null,
    prefillDosage: String? = null,
    prefillFrequency: String? = null,
    prefillTimes: List<String>? = null,
    prefillIntervalHours: String? = null,
    prefillFoodRelation: String? = null,
    prefillInstructions: String? = null,
    prefillStock: String? = null,
    prefillPillsPerDose: String? = null,
    prefillDaysOfWeek: String? = null,
    prefillDurationDays: String? = null,
    onConsumePrefill: () -> Unit = {},
    onNavigateBack: () -> Unit,
    onNavigateToHome: () -> Unit,
    onNavigateToMedicinesList: () -> Unit,
    viewModel: AddEditMedicineViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.currentStep == AddEditStep.ENROLLMENT_PHOTO) {
        viewModel.onBackToForm()
    }
    BackHandler(enabled = uiState.currentStep == AddEditStep.SUCCESS) {
        onNavigateToHome()
    }

    LaunchedEffect(medicineId, prefillName) {
        if (!medicineId.isNullOrBlank()) {
            viewModel.loadMedicine(medicineId)
        } else if (!prefillName.isNullOrBlank()) {
            viewModel.prefillFromScan(
                name = prefillName,
                dosage = prefillDosage,
                pillsPerDose = prefillPillsPerDose,
                initialStock = prefillStock,
                frequency = prefillFrequency,
                times = prefillTimes,
                intervalHours = prefillIntervalHours,
                daysOfWeek = prefillDaysOfWeek,
                durationDays = prefillDurationDays,
                foodRelation = prefillFoodRelation,
                instructions = prefillInstructions
            )
            onConsumePrefill()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (uiState.currentStep) {
                            AddEditStep.FORM -> if (uiState.isEditing) {
                                stringResource(R.string.edit_medicine_title)
                            } else {
                                stringResource(R.string.add_medicine_title)
                            }
                            AddEditStep.ENROLLMENT_PHOTO -> stringResource(R.string.enrollment_photo_title)
                            AddEditStep.SUCCESS -> stringResource(R.string.app_name)
                        }
                    )
                },
                navigationIcon = {
                    if (uiState.currentStep == AddEditStep.ENROLLMENT_PHOTO) {
                        IconButton(onClick = viewModel::onBackToForm) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to form")
                        }
                    } else if (uiState.currentStep == AddEditStep.FORM) {
                        IconButton(onClick = {
                            onConsumePrefill()
                            onNavigateBack()
                        }) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (uiState.isEditing && uiState.currentStep == AddEditStep.FORM) {
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.btn_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        when (uiState.currentStep) {
            AddEditStep.ENROLLMENT_PHOTO -> {
                EnrollmentPhotoView(
                    onPhotoCaptured = viewModel::onPhotoCaptured,
                    onSkip = viewModel::onSkipPhoto,
                    onConfirmSave = viewModel::onConfirmPhotoAndSave,
                    modifier = modifier.padding(innerPadding)
                )
            }
            AddEditStep.SUCCESS -> {
                MedicineSavedSuccessView(
                    isEditing = uiState.isEditing,
                    medicineName = uiState.name,
                    generatedDosesCount = uiState.savedDoses.size,
                    onBackToHome = onNavigateToHome,
                    onViewMedicines = onNavigateToMedicinesList,
                    modifier = modifier.padding(innerPadding)
                )
            }
            AddEditStep.FORM -> {
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (uiState.errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = uiState.errorMessage!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // Medicine Name
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = viewModel::onNameChange,
                        label = { Text(stringResource(R.string.medicine_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Dosage Text
                    OutlinedTextField(
                        value = uiState.dosageText,
                        onValueChange = viewModel::onDosageChange,
                        label = { Text(stringResource(R.string.dosage_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Pills per dose and Initial stock
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.pillsPerDoseText,
                            onValueChange = viewModel::onPillsPerDoseChange,
                            label = { Text(stringResource(R.string.pills_per_dose_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = uiState.initialStockText,
                            onValueChange = viewModel::onInitialStockChange,
                            label = { Text(stringResource(R.string.initial_stock_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Food Relation
                    Text(
                        text = stringResource(R.string.food_relation_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.foodRelation == FoodRelation.BEFORE,
                            onClick = { viewModel.onFoodRelationChange(FoodRelation.BEFORE) },
                            label = { Text(stringResource(R.string.food_before)) }
                        )
                        FilterChip(
                            selected = uiState.foodRelation == FoodRelation.AFTER,
                            onClick = { viewModel.onFoodRelationChange(FoodRelation.AFTER) },
                            label = { Text(stringResource(R.string.food_after)) }
                        )
                        FilterChip(
                            selected = uiState.foodRelation == FoodRelation.ANY,
                            onClick = { viewModel.onFoodRelationChange(FoodRelation.ANY) },
                            label = { Text(stringResource(R.string.food_any)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Frequency
                    Text(
                        text = stringResource(R.string.frequency_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.frequencyType == FrequencyType.DAILY,
                            onClick = { viewModel.onFrequencyTypeChange(FrequencyType.DAILY) },
                            label = { Text(stringResource(R.string.freq_daily)) }
                        )
                        FilterChip(
                            selected = uiState.frequencyType == FrequencyType.HOURLY,
                            onClick = { viewModel.onFrequencyTypeChange(FrequencyType.HOURLY) },
                            label = { Text(stringResource(R.string.freq_hourly)) }
                        )
                        FilterChip(
                            selected = uiState.frequencyType == FrequencyType.SPECIFIC_DAYS,
                            onClick = { viewModel.onFrequencyTypeChange(FrequencyType.SPECIFIC_DAYS) },
                            label = { Text(stringResource(R.string.freq_specific_days)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Frequency-specific controls
                    when (uiState.frequencyType) {
                        FrequencyType.DAILY -> {
                            Text(
                                text = stringResource(R.string.times_of_day_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                uiState.timesOfDay.forEach { time ->
                                    val timeStr = DateTimeFormatter.ofPattern("hh:mm a").format(time)
                                    InputChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text(timeStr) },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { viewModel.removeTimeOfDay(time) },
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                Icon(imageVector = Icons.Default.Close, contentDescription = "Remove")
                                            }
                                        }
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        val cal = Calendar.getInstance()
                                        TimePickerDialog(
                                            context,
                                            { _, hour, minute ->
                                                viewModel.addTimeOfDay(LocalTime.of(hour, minute))
                                            },
                                            cal.get(Calendar.HOUR_OF_DAY),
                                            cal.get(Calendar.MINUTE),
                                            false
                                        ).show()
                                    }
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(stringResource(R.string.add_time_button))
                                }
                            }
                        }

                        FrequencyType.HOURLY -> {
                            OutlinedTextField(
                                value = uiState.intervalHoursText,
                                onValueChange = viewModel::onIntervalHoursChange,
                                label = { Text(stringResource(R.string.interval_hours_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        FrequencyType.SPECIFIC_DAYS -> {
                            Text(
                                text = "Days of Week",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val dayNames = listOf("M", "T", "W", "T", "F", "S", "S")
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                (1..7).forEach { day ->
                                    FilterChip(
                                        selected = uiState.daysOfWeek.contains(day),
                                        onClick = { viewModel.toggleDayOfWeek(day) },
                                        label = { Text(dayNames[day - 1]) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Start and End Dates
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val startDateStr = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(uiState.startDate))

                        OutlinedButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply { timeInMillis = uiState.startDate }
                                DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val pickedDate = LocalDate.of(year, month + 1, dayOfMonth)
                                        val millis = pickedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                        viewModel.onStartDateChange(millis)
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(startDateStr)
                        }

                        val endDateStr = uiState.endDate?.let {
                            DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(it))
                        } ?: "No End Date"

                        OutlinedButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    timeInMillis = uiState.endDate ?: uiState.startDate
                                }
                                DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val pickedDate = LocalDate.of(year, month + 1, dayOfMonth)
                                        val millis = pickedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                        viewModel.onEndDateChange(millis)
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(endDateStr)
                        }
                    }

                    if (uiState.endDate != null) {
                        TextButton(
                            onClick = { viewModel.onEndDateChange(null) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Clear End Date", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Instructions
                    OutlinedTextField(
                        value = uiState.instructions,
                        onValueChange = viewModel::onInstructionsChange,
                        label = { Text(stringResource(R.string.instructions_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Set Reminders Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.set_reminders_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = uiState.reminderEnabled,
                            onCheckedChange = viewModel::onReminderEnabledChange
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Action Buttons
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else if (uiState.isEditing) {
                        Button(
                            onClick = viewModel::saveDirectlyIfEditing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.btn_save_medicine))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { showDeleteConfirmation = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.btn_delete))
                        }
                    } else {
                        Button(
                            onClick = viewModel::onContinueToPhoto,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.btn_continue_to_photo))
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.delete_medicine_title)) },
            text = { Text(stringResource(R.string.delete_medicine_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteMedicine {
                            showDeleteConfirmation = false
                            onNavigateToMedicinesList()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun MedicineSavedSuccessView(
    isEditing: Boolean,
    medicineName: String,
    generatedDosesCount: Int,
    onBackToHome: () -> Unit,
    onViewMedicines: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isEditing) stringResource(R.string.medicine_updated_success) else stringResource(R.string.medicine_added_success),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "$medicineName has been saved. $generatedDosesCount upcoming dose event(s) scheduled for the rolling 7-day horizon.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onBackToHome,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.btn_back_to_home))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onViewMedicines,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.btn_view_medicines))
        }
    }
}
