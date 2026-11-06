package com.meditrack.app.feature.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meditrack.app.R
import com.meditrack.app.domain.model.FrequencyType
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPrescriptionScreen(
    onNavigateBack: () -> Unit,
    onContinueToAddMedicine: (
        name: String,
        dosage: String,
        frequency: FrequencyType,
        times: List<LocalTime>,
        pillsPerDose: Int,
        instructions: String
    ) -> Unit,
    viewModel: ScanPrescriptionViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isCapturing by remember { mutableStateOf(false) }
    var isFlashOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            onNavigateBack()
        }
    }

    // System Photo Picker (Stage 11 ONLY - never used in verification pipeline)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                viewModel.onGalleryImageSelected(bitmap)
            } catch (e: Exception) {
                Log.e("MediTrack", "Failed to load image from gallery: ${e.message}", e)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_prescription_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (hasCameraPermission) {
                        IconButton(onClick = {
                            val next = !isFlashOn
                            camera?.cameraControl?.enableTorch(next)
                            isFlashOn = next
                        }) {
                            Icon(
                                imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Toggle Flash",
                                tint = if (isFlashOn) Color.Yellow else Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            if (!hasCameraPermission) {
                // Camera Permission Rationale & Request View
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.scan_permission_rationale),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.scan_permission_grant))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.scan_gallery_button))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = onNavigateBack,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.7f))
                    ) {
                        Text("Back to Home")
                    }
                }
            } else {
                // Camera Live Preview & Capture View
                var imageCaptureInstance by remember { mutableStateOf<ImageCapture?>(null) }

                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            imageCaptureInstance = imageCapture

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageCapture
                                )
                            } catch (e: Exception) {
                                Log.e("MediTrack", "Failed to bind camera: ${e.message}", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Viewfinder Framing Overlay
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val frameWidth = size.width * 0.85f
                    val frameHeight = size.height * 0.55f
                    val left = (size.width - frameWidth) / 2f
                    val top = (size.height - frameHeight) / 2.3f

                    val frameRect = Rect(Offset(left, top), Size(frameWidth, frameHeight))
                    val cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())

                    val path = Path().apply {
                        addRoundRect(RoundRect(frameRect, cornerRadius))
                    }

                    clipPath(path, clipOp = ClipOp.Difference) {
                        drawRect(Color(0x80000000))
                    }

                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.9f),
                        topLeft = Offset(left, top),
                        size = Size(frameWidth, frameHeight),
                        cornerRadius = cornerRadius,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                // Guide text at top
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.scan_instruction_hint),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                // Bottom Controls Row: Gallery picker + Camera capture
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Gallery button (System Photo Picker)
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = stringResource(R.string.scan_gallery_button),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Capture photo button
                        if (isCapturing || uiState.isProcessing) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(56.dp))
                        } else {
                            IconButton(
                                onClick = {
                                    val imageCapture = imageCaptureInstance ?: return@IconButton
                                    isCapturing = true
                                    val executor = ContextCompat.getMainExecutor(context)
                                    imageCapture.takePicture(
                                        executor,
                                        object : ImageCapture.OnImageCapturedCallback() {
                                            override fun onCaptureSuccess(image: ImageProxy) {
                                                try {
                                                    val rawBitmap = image.toBitmap()
                                                    val rotation = image.imageInfo.rotationDegrees
                                                    val bitmap = if (rotation != 0) {
                                                        val matrix = android.graphics.Matrix().apply {
                                                            postRotate(rotation.toFloat())
                                                        }
                                                        Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                                                    } else {
                                                        rawBitmap
                                                    }
                                                    image.close()
                                                    isCapturing = false
                                                    viewModel.onCapturedBitmap(bitmap)
                                                } catch (e: Exception) {
                                                    Log.e("MediTrack", "Error processing photo: ${e.message}", e)
                                                    isCapturing = false
                                                }
                                            }

                                            override fun onError(exception: ImageCaptureException) {
                                                Log.e("MediTrack", "Capture failed: ${exception.message}", exception)
                                                isCapturing = false
                                            }
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(Color.White, CircleShape)
                                    .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = stringResource(R.string.scan_capture_button),
                                    tint = Color.Black,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        // Empty spacer to balance the row layout
                        Spacer(modifier = Modifier.size(52.dp))
                    }
                }
            }

            // Quality Gate Failure Dialog
            uiState.qualityFailure?.let { failure ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissQualityFailure() },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "Photo Quality Check Failed",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = failure.friendlyMessage
                                    ?: "The photo could not be read clearly due to blur, glare, or poor lighting.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = { viewModel.dismissQualityFailure() }) {
                            Text(stringResource(R.string.scan_retake_button))
                        }
                    }
                )
            }

            // Extracted Review Dialog (Editable Form)
            if (uiState.showReviewDialog) {
                Dialog(
                    onDismissRequest = { viewModel.dismissReviewDialog() },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .padding(vertical = 24.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = stringResource(R.string.scan_review_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Please confirm or edit the information detected from your prescription.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Medicine Name
                            OutlinedTextField(
                                value = uiState.medicineName,
                                onValueChange = { viewModel.onNameChange(it) },
                                label = { Text("Medicine Name *") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Dosage
                            OutlinedTextField(
                                value = uiState.dosageText,
                                onValueChange = { viewModel.onDosageChange(it) },
                                label = { Text("Dosage (e.g. 500mg, 10ml) *") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Pills Per Dose
                            OutlinedTextField(
                                value = uiState.pillsPerDoseText,
                                onValueChange = { viewModel.onPillsPerDoseTextChange(it) },
                                label = { Text("Pills Per Dose") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Frequency Selection
                            Text(
                                text = "Frequency",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = uiState.frequencyType == FrequencyType.DAILY,
                                    onClick = { viewModel.onFrequencyTypeChange(FrequencyType.DAILY) },
                                    label = { Text("Daily") }
                                )
                                FilterChip(
                                    selected = uiState.frequencyType == FrequencyType.HOURLY,
                                    onClick = { viewModel.onFrequencyTypeChange(FrequencyType.HOURLY) },
                                    label = { Text("Hourly") }
                                )
                                FilterChip(
                                    selected = uiState.frequencyType == FrequencyType.SPECIFIC_DAYS,
                                    onClick = { viewModel.onFrequencyTypeChange(FrequencyType.SPECIFIC_DAYS) },
                                    label = { Text("Specific Days") }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Instructions
                            OutlinedTextField(
                                value = uiState.instructions,
                                onValueChange = { viewModel.onInstructionsChange(it) },
                                label = { Text("Instructions / Directions") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Action buttons: YES / Continue vs NO / Retake
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.dismissReviewDialog() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.scan_retake_button))
                                }

                                Button(
                                    onClick = {
                                        viewModel.dismissReviewDialog()
                                        onContinueToAddMedicine(
                                            uiState.medicineName,
                                            uiState.dosageText,
                                            uiState.frequencyType,
                                            uiState.timesOfDay,
                                            uiState.pillsPerDose,
                                            uiState.instructions
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.scan_confirm_button))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
