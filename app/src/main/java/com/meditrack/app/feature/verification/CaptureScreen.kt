package com.meditrack.app.feature.verification

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.meditrack.app.BuildConfig
import com.meditrack.app.feature.verification.dataset.DatasetCollector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meditrack.app.MediTrackApp
import com.meditrack.app.feature.verification.quality.ImageQualityGate
import com.meditrack.app.feature.verification.quality.QualityMetrics
import com.meditrack.app.feature.verification.quality.QualityResult
import com.meditrack.app.feature.verification.util.ProofStorage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onPhotoVerifiedQuality: (photoPath: String, pHash: Long, metrics: QualityMetrics) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionDeniedMessage by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var isFlashOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var qualityFailure by remember { mutableStateOf<QualityResult?>(null) }
    var pendingLabelBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingQualityMetrics by remember { mutableStateOf<QualityMetrics?>(null) }
    var labelStripType by remember { mutableStateOf("blister_strip") }
    var labelFullCells by remember { mutableStateOf("10") }
    var labelEmptyCells by remember { mutableStateOf("0") }

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
            permissionDeniedMessage = "Dose stays pending. A photo is needed to record a dose."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Take Verification Photo") },
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
                // Camera Permission Denied View
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = permissionDeniedMessage ?: "MediTrack requires the camera to take live photos of blister strips for intake verification.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Camera Permission")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Settings")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            // Stage 5 requirement: return home with message
                            onNavigateHome()
                        }
                    ) {
                        Text("Return to Home", color = Color.White)
                    }
                }
            } else {
                // Live CameraX Preview + Framing Guide
                val imageCapture = remember {
                    ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                }

                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val cameraSelector = when {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                                else -> CameraSelector.DEFAULT_BACK_CAMERA
                            }
                            try {
                                cameraProvider.unbindAll()
                                camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageCapture
                                )
                            } catch (e: Exception) {
                                Log.e(MediTrackApp.TAG, "Failed to bind CameraX lifecycle: ${e.message}", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Strip-shaped Framing Guide Overlay
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height

                    // Cutout dimensions: horizontal blister strip (~85% width, aspect ratio 2.2:1)
                    val guideWidth = canvasWidth * 0.88f
                    val guideHeight = guideWidth / 2.2f
                    val guideLeft = (canvasWidth - guideWidth) / 2f
                    val guideTop = (canvasHeight - guideHeight) / 2.3f

                    val cutoutRect = RoundRect(
                        rect = Rect(
                            offset = Offset(guideLeft, guideTop),
                            size = Size(guideWidth, guideHeight)
                        ),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                    )

                    val cutoutPath = Path().apply {
                        addRoundRect(cutoutRect)
                    }

                    // Draw darkened overlay with transparent cutout
                    clipPath(cutoutPath, clipOp = ClipOp.Difference) {
                        drawRect(color = Color(0x99000000))
                    }

                    // Draw guide border
                    drawRoundRect(
                        color = Color(0xFF4CAF50),
                        topLeft = Offset(guideLeft, guideTop),
                        size = Size(guideWidth, guideHeight),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                // Top Tips Banner
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Lay the strip flat, whole strip visible, good light",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                // Bottom Controls (Capture button only - NO gallery button)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        IconButton(
                            onClick = {
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
                                                    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                                                    Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                                                } else {
                                                    rawBitmap
                                                }
                                                image.close()

                                                // Run ImageQualityGate
                                                val qualityResult = ImageQualityGate.analyze(bitmap)
                                                if (!qualityResult.passed) {
                                                    qualityFailure = qualityResult
                                                    isCapturing = false
                                                } else {
                                                    if (BuildConfig.DEBUG && DatasetCollector.isDataCollectionEnabled) {
                                                        pendingLabelBitmap = bitmap
                                                        pendingQualityMetrics = qualityResult.metrics
                                                        isCapturing = false
                                                    } else {
                                                        val savedPath = ProofStorage.saveProof(context, bitmap)
                                                        val pHash = com.meditrack.app.feature.verification.quality.PerceptualHasher.computeHash(bitmap)
                                                        isCapturing = false
                                                        onPhotoVerifiedQuality(savedPath, pHash, qualityResult.metrics)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(MediTrackApp.TAG, "Error processing captured photo: ${e.message}", e)
                                                isCapturing = false
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e(MediTrackApp.TAG, "Photo capture failed: ${exception.message}", exception)
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
                                contentDescription = "Capture Photo",
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            }

            // Quality Failure Dialog (Free retake - does NOT consume attempt)
            qualityFailure?.let { failure ->
                AlertDialog(
                    onDismissRequest = { qualityFailure = null },
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
                                text = failure.friendlyMessage ?: "The photo could not be verified due to quality issues.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Quality issue: ${failure.reason?.name ?: "Unknown"}\n(This does not count as a verification attempt)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { qualityFailure = null },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Retake Photo")
                        }
                    }
                )
            }

            // Data Collection Labeling Dialog (Debug Builds Only)
            pendingLabelBitmap?.let { bitmap ->
                AlertDialog(
                    onDismissRequest = {
                        // If dismissed, proceed without saving dataset sample
                        val savedPath = ProofStorage.saveProof(context, bitmap)
                        val pHash = com.meditrack.app.feature.verification.quality.PerceptualHasher.computeHash(bitmap)
                        pendingLabelBitmap = null
                        pendingQualityMetrics?.let { metrics ->
                            onPhotoVerifiedQuality(savedPath, pHash, metrics)
                        }
                    },
                    title = {
                        Text(
                            text = "Label Dataset Sample",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Data collection mode is active. Label this sample to train ML in Stage 13.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            OutlinedTextField(
                                value = labelStripType,
                                onValueChange = { labelStripType = it },
                                label = { Text("Strip Type") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = labelFullCells,
                                onValueChange = { labelFullCells = it },
                                label = { Text("Number of Full Cells") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = labelEmptyCells,
                                onValueChange = { labelEmptyCells = it },
                                label = { Text("Number of Empty Cells") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val full = labelFullCells.toIntOrNull() ?: 10
                                val empty = labelEmptyCells.toIntOrNull() ?: 0
                                DatasetCollector.saveSample(
                                    context = context,
                                    bitmap = bitmap,
                                    stripType = labelStripType.ifBlank { "blister_strip" },
                                    fullCells = full,
                                    emptyCells = empty
                                )
                                val savedPath = ProofStorage.saveProof(context, bitmap)
                                val pHash = com.meditrack.app.feature.verification.quality.PerceptualHasher.computeHash(bitmap)
                                val metrics = pendingQualityMetrics ?: QualityMetrics(100.0, 128.0, 0.0)
                                pendingLabelBitmap = null
                                onPhotoVerifiedQuality(savedPath, pHash, metrics)
                            }
                        ) {
                            Text("Save Sample")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = {
                                val savedPath = ProofStorage.saveProof(context, bitmap)
                                val pHash = com.meditrack.app.feature.verification.quality.PerceptualHasher.computeHash(bitmap)
                                val metrics = pendingQualityMetrics ?: QualityMetrics(100.0, 128.0, 0.0)
                                pendingLabelBitmap = null
                                onPhotoVerifiedQuality(savedPath, pHash, metrics)
                            }
                        ) {
                            Text("Skip Label")
                        }
                    }
                )
            }
        }
    }
}
