package com.meditrack.app.feature.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meditrack.app.domain.model.FoodRelation
import com.meditrack.app.domain.model.FrequencyType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanMedicineScreen(
    viewModel: ScanMedicineViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onProceedToAddMedicine: (
        name: String,
        dosage: String,
        frequency: String,
        times: List<String>,
        intervalHours: String,
        daysOfWeek: List<Int>,
        durationDays: String,
        foodRelation: String,
        instructions: String,
        stock: String,
        pillsPerDose: String
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.processUri(context, uri)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isScanning) "Scan Medicine / Rx" else "Review Scanned Rx")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!uiState.isScanning) {
                        IconButton(onClick = { viewModel.rescan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isProcessing) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Analyzing prescription with ML Kit OCR...",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (!hasCameraPermission && uiState.isScanning) {
                CameraPermissionDeniedContent(
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onPickFromGallery = {
                        galleryPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onBackToHome = onNavigateBack
                )
            } else if (uiState.isScanning) {
                CameraScanContent(
                    context = context,
                    isProcessing = uiState.isProcessing,
                    onImageCaptured = { bitmap ->
                        viewModel.processBitmap(bitmap)
                    },
                    onPickFromGallery = {
                        galleryPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onError = { err ->
                        viewModel.onCaptureError(err)
                    }
                )
            } else {
                ReviewPrescriptionContent(
                    uiState = uiState,
                    onNameChange = viewModel::onNameChange,
                    onDosageChange = viewModel::onDosageChange,
                    onPillsChange = viewModel::onPillsChange,
                    onStockChange = viewModel::onStockChange,
                    onFrequencyChange = viewModel::onFrequencyChange,
                    onIntervalHoursChange = viewModel::onIntervalHoursChange,
                    onToggleDay = viewModel::onToggleDay,
                    onDurationDaysChange = viewModel::onDurationDaysChange,
                    onFoodRelationChange = viewModel::onFoodRelationChange,
                    onInstructionsChange = viewModel::onInstructionsChange,
                    onAddTime = viewModel::addTimeOfDay,
                    onRemoveTime = viewModel::removeTimeOfDay,
                    onRescan = viewModel::rescan,
                    onConfirm = {
                        val timeStrings = uiState.timesOfDay.map {
                            String.format("%02d:%02d", it.hour, it.minute)
                        }
                        onProceedToAddMedicine(
                            uiState.name,
                            uiState.dosageText,
                            uiState.frequencyType?.name ?: FrequencyType.DAILY.name,
                            timeStrings,
                            uiState.intervalHoursText,
                            uiState.daysOfWeek,
                            uiState.durationDaysText,
                            uiState.foodRelation.name,
                            uiState.instructions,
                            uiState.initialStockText,
                            uiState.pillsPerDoseText
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionDeniedContent(
    onRequestPermission: () -> Unit,
    onPickFromGallery: () -> Unit,
    onBackToHome: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var hasRequestedOnce by remember { mutableStateOf(false) }
    val isPermanentlyDenied = hasRequestedOnce && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "MediTrack needs camera access to scan pharmacy labels, medicine blisters, and paper prescriptions.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isPermanentlyDenied) {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Settings")
            }
        } else {
            Button(
                onClick = {
                    hasRequestedOnce = true
                    onRequestPermission()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Camera Permission")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickFromGallery,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose from Gallery instead")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = onBackToHome,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Home")
        }
    }
}

@Composable
private fun CameraScanContent(
    context: Context,
    isProcessing: Boolean = false,
    onImageCaptured: (Bitmap) -> Unit,
    onPickFromGallery: () -> Unit,
    onError: (String) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // F5: Shut down camera executor when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        imageCapture = capture

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            capture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        onError("Camera failed to initialize: ${e.localizedMessage ?: "Unknown error"}. You can choose an image from your gallery.")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay with scanning frame
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            )
        }

        // Instructions banner
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Align prescription or blister label inside the box",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Bottom control row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery picker button (allowed ONLY in Scan stage per README)
            IconButton(
                onClick = onPickFromGallery,
                enabled = !isProcessing,
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "Pick from gallery",
                    tint = Color.White
                )
            }

            // Capture Shutter Button
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    capture.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bitmap = imageProxyToBitmap(image)
                                image.close()
                                ContextCompat.getMainExecutor(context).execute {
                                    if (bitmap != null) {
                                        onImageCaptured(bitmap)
                                    } else {
                                        onError("Could not decode camera image. Please retake.")
                                    }
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                exception.printStackTrace()
                                ContextCompat.getMainExecutor(context).execute {
                                    onError("Camera capture failed: ${exception.localizedMessage ?: "Capture error"}")
                                }
                            }
                        }
                    )
                },
                enabled = !isProcessing,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Capture",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.size(52.dp))
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotationDegrees = image.imageInfo.rotationDegrees
    return if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } else {
        bitmap
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewPrescriptionContent(
    uiState: ScanMedicineUiState,
    onNameChange: (String) -> Unit,
    onDosageChange: (String) -> Unit,
    onPillsChange: (String) -> Unit,
    onStockChange: (String) -> Unit,
    onFrequencyChange: (FrequencyType) -> Unit,
    onIntervalHoursChange: (String) -> Unit,
    onToggleDay: (Int) -> Unit,
    onDurationDaysChange: (String) -> Unit,
    onFoodRelationChange: (FoodRelation) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onAddTime: (LocalTime) -> Unit,
    onRemoveTime: (LocalTime) -> Unit,
    onRescan: () -> Unit,
    onConfirm: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // C10: As-needed (PRN/SOS) explanatory banner
        if (uiState.isAsNeeded) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "As-needed medicines have no automatic schedule; choose how you want to be reminded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Warning Banner when fields require manual attention
        if (uiState.requiresManualReview || uiState.reviewReasons.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Manual Review Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    uiState.reviewReasons.forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Prescription Scanned Successfully!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verify the detected fields below and edit anything incorrect before continuing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Medicine Name
        OutlinedTextField(
            value = uiState.name,
            onValueChange = onNameChange,
            label = { Text("Medicine Name *") },
            isError = uiState.name.isBlank(),
            supportingText = if (uiState.name.isBlank()) {
                { Text("Medicine name is required") }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Dosage Text and Pills Per Dose
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = uiState.dosageText,
                onValueChange = onDosageChange,
                label = { Text("Dosage / Strength *") },
                placeholder = { Text("e.g. 500mg") },
                isError = uiState.dosageText.isBlank(),
                supportingText = if (uiState.dosageText.isBlank()) {
                    { Text("Dosage is required") }
                } else null,
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = uiState.pillsPerDoseText,
                onValueChange = onPillsChange,
                label = { Text("Pills / Dose *") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        // Initial Stock (explicitly required, never silently 10)
        OutlinedTextField(
            value = uiState.initialStockText,
            onValueChange = onStockChange,
            label = { Text("Stock Quantity (Pill / Strip count) *") },
            placeholder = { Text("Enter total pills in package") },
            isError = uiState.initialStockText.toIntOrNull() == null || (uiState.initialStockText.toIntOrNull() ?: 0) <= 0,
            supportingText = if (uiState.initialStockText.toIntOrNull() == null || (uiState.initialStockText.toIntOrNull() ?: 0) <= 0) {
                { Text("Valid stock count required (> 0)") }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Frequency Selector
        Text(
            text = "Frequency *",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.frequencyType == FrequencyType.DAILY,
                onClick = { onFrequencyChange(FrequencyType.DAILY) },
                label = { Text("Daily") }
            )
            FilterChip(
                selected = uiState.frequencyType == FrequencyType.HOURLY,
                onClick = { onFrequencyChange(FrequencyType.HOURLY) },
                label = { Text("Hourly / Interval") }
            )
            FilterChip(
                selected = uiState.frequencyType == FrequencyType.SPECIFIC_DAYS,
                onClick = { onFrequencyChange(FrequencyType.SPECIFIC_DAYS) },
                label = { Text("Specific Days") }
            )
        }

        if (uiState.frequencyType == null) {
            Text(
                text = "Please select a frequency above",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        } else if (uiState.frequencyType == FrequencyType.HOURLY) {
            OutlinedTextField(
                value = uiState.intervalHoursText,
                onValueChange = onIntervalHoursChange,
                label = { Text("Repeat Every (Hours) *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        } else {
            // A1: Specific Days selector chip row
            if (uiState.frequencyType == FrequencyType.SPECIFIC_DAYS) {
                Text(
                    text = "Days of Week *",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val days = listOf(
                        1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
                        5 to "Fri", 6 to "Sat", 7 to "Sun"
                    )
                    days.forEach { (dayVal, dayLabel) ->
                        val selected = uiState.daysOfWeek.contains(dayVal)
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleDay(dayVal) },
                            label = { Text(dayLabel, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
                if (uiState.daysOfWeek.isEmpty()) {
                    Text(
                        text = "Please select at least one day",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Times of Day Chips
            Text(
                text = "Reminder Times",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uiState.timesOfDay.forEach { time ->
                    val formatter = remember { DateTimeFormatter.ofPattern("hh:mm a") }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = time.format(formatter),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            if (uiState.timesOfDay.size > 1) {
                                IconButton(
                                    onClick = { onRemoveTime(time) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove time", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Food Relation
        Text(
            text = "Food Relation",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.foodRelation == FoodRelation.BEFORE,
                onClick = { onFoodRelationChange(FoodRelation.BEFORE) },
                label = { Text("Before Food") }
            )
            FilterChip(
                selected = uiState.foodRelation == FoodRelation.AFTER,
                onClick = { onFoodRelationChange(FoodRelation.AFTER) },
                label = { Text("After Food") }
            )
            FilterChip(
                selected = uiState.foodRelation == FoodRelation.ANY,
                onClick = { onFoodRelationChange(FoodRelation.ANY) },
                label = { Text("Any / None") }
            )
        }

        OutlinedTextField(
            value = uiState.durationDaysText,
            onValueChange = onDurationDaysChange,
            label = { Text("Course length in days (optional)") },
            placeholder = { Text("e.g. 5") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = uiState.instructions,
            onValueChange = onInstructionsChange,
            label = { Text("Special Instructions") },
            placeholder = { Text("e.g. Take with plenty of water") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        // Raw OCR text preview (read-only)
        if (!uiState.rawText.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Extracted Raw OCR Text",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        uiState.rawText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Action Buttons: Enabled ONLY when all required fields are valid
        Button(
            onClick = onConfirm,
            enabled = uiState.isReadyToProceed,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Continue to Add Medicine")
        }

        OutlinedButton(
            onClick = onRescan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan Again")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
