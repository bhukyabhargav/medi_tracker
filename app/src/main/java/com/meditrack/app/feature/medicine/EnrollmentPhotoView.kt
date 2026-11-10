package com.meditrack.app.feature.medicine

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.meditrack.app.feature.verification.pipeline.StripDetector
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.meditrack.app.MediTrackApp
import com.meditrack.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EnrollmentPhotoView(
    onPhotoCaptured: (path: String, baselineEmptyCells: Int) -> Unit,
    onSkip: () -> Unit,
    onConfirmSave: () -> Unit,
    modifier: Modifier = Modifier,
    stripDetector: StripDetector? = null
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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            // If denied via launcher, open settings as fallback
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        }
    }

    val scope = rememberCoroutineScope()
    var capturedPhotoPath by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var qualityError by remember { mutableStateOf<String?>(null) }
    var pendingPhotoPath by remember { mutableStateOf<String?>(null) }
    var pendingEmptyCells by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    fun validateAndAccept(path: String) {
        scope.launch(Dispatchers.Default) {
            val bitmap = try {
                com.meditrack.app.core.util.BitmapLoader.decodeSampled(path, maxSidePx = 1920)
            } catch (t: Throwable) {
                null
            }
            if (bitmap == null) {
                withContext(Dispatchers.Main) {
                    qualityError = "Failed to load captured photo due to memory constraints."
                    isCapturing = false
                }
                return@launch
            }

            // 1) Level-1 quality check on whole image (decode, min size, not near-black/near-white)
            val level1 = com.meditrack.app.feature.verification.quality.ImageQualityGate.analyzeLevel1(bitmap)
            if (!level1.passed) {
                withContext(Dispatchers.Main) {
                    pendingPhotoPath = null
                    pendingEmptyCells = 0
                    qualityError = level1.friendlyMessage ?: "Photo quality check failed"
                    isCapturing = false
                }
                return@launch
            }

            // 2) Is there a blister strip anywhere in the photo? This is mandatory and can never be bypassed:
            //    if the detector is missing or fails we cannot confirm a strip, so the photo is NOT accepted.
            var presence = com.meditrack.app.feature.verification.pipeline.BlisterPresenceValidator.Result(
                present = false,
                message = com.meditrack.app.feature.verification.pipeline.BlisterPresenceValidator.MSG_NOT_FOUND
            )
            var detection: com.meditrack.app.feature.verification.pipeline.Detection? = null
            if (stripDetector == null) {
                presence = presence.copy(message = "Blister check is unavailable right now. Please try again.")
            } else {
                try {
                    val d = stripDetector.detect(bitmap)
                    detection = d
                    presence = com.meditrack.app.feature.verification.pipeline.BlisterPresenceValidator.evaluate(d)
                } catch (e: Exception) {
                    Log.w("EnrollmentPhotoView", "Strip detector failed during enrollment: ${e.message}")
                    presence = presence.copy(message = "Could not check the photo for a blister strip. Please retake.")
                }
            }
            if (!presence.present || detection == null) {
                withContext(Dispatchers.Main) {
                    pendingPhotoPath = null // no "Use Photo Anyway" for a photo without a blister strip
                    pendingEmptyCells = 0
                    qualityError = presence.message
                    isCapturing = false
                }
                return@launch
            }
            val detectedEmptyCells = presence.validCells.count {
                it.state == com.meditrack.app.feature.verification.pipeline.CellState.EMPTY
            }

            // 3) Level-2 quality check on blister crop. A real strip with a mediocre photo may still be used if the person insists.
            val stripBox = com.meditrack.app.feature.verification.quality.ImageQualityGate.resolveStripBox(detection.stripBox, detection.cells)
            val level2Quality = com.meditrack.app.feature.verification.quality.ImageQualityGate.analyzeBlisterCrop(bitmap, stripBox)
            if (!level2Quality.passed) {
                withContext(Dispatchers.Main) {
                    pendingPhotoPath = path
                    pendingEmptyCells = detectedEmptyCells
                    qualityError = level2Quality.friendlyMessage ?: "Blister photo quality check failed"
                    isCapturing = false
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                capturedPhotoPath = path
                pendingPhotoPath = null
                isCapturing = false
                qualityError = null
                onPhotoCaptured(path, detectedEmptyCells)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isCapturing = true
            scope.launch(Dispatchers.IO) {
                try {
                    val baselineDir = File(context.filesDir, "baseline").apply { if (!exists()) mkdirs() }
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val destFile = File(baselineDir, "baseline_gallery_$timeStamp.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    validateAndAccept(destFile.absolutePath)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        qualityError = "Failed to load selected image: ${e.message}"
                        isCapturing = false
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.enrollment_photo_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.enrollment_photo_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.enrollment_photo_guidance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when {
                capturedPhotoPath != null -> {
                    // Preview of captured photo
                    AsyncImage(
                        model = File(capturedPhotoPath!!),
                        contentDescription = "Captured baseline blister pack",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                hasCameraPermission -> {
                    CameraXCapturePreview(
                        onImageSaved = { path ->
                            validateAndAccept(path)
                        },
                        isCapturing = isCapturing,
                        onError = {
                            isCapturing = false
                        }
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Camera permission needed to take baseline enrollment photo.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val activity = context as? Activity
                                    val shouldShow = activity?.let {
                                        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
                                    } ?: true
                                    if (!shouldShow) {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.permission_grant))
                            }
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                }
                            ) {
                                Text("Open Settings", color = Color.White)
                            }
                        }
                    }
                }
            }

            if (isCapturing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (capturedPhotoPath != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { capturedPhotoPath = null },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.btn_retake_photo))
                }
                Button(
                    onClick = onConfirmSave,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.btn_use_photo))
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (hasCameraPermission) {
                    Button(
                        onClick = { isCapturing = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCapturing
                    ) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.btn_take_photo))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCapturing
                ) {
                    Icon(imageVector = Icons.Default.Image, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Upload from Gallery")
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.btn_skip_photo),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        if (qualityError != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    qualityError = null
                    pendingPhotoPath = null
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(if (pendingPhotoPath != null) "Baseline Photo Quality Issue" else "No Blister Strip Found")
                },
                text = {
                    Text(qualityError ?: "The captured image did not pass quality checks.")
                },
                confirmButton = {
                    Button(onClick = {
                        qualityError = null
                        pendingPhotoPath = null
                    }) {
                        Text("Retake")
                    }
                },
                dismissButton = {
                    if (pendingPhotoPath != null) {
                        OutlinedButton(onClick = {
                            val p = pendingPhotoPath
                            val empty = pendingEmptyCells
                            qualityError = null
                            pendingPhotoPath = null
                            if (p != null) {
                                capturedPhotoPath = p
                                isCapturing = false
                                onPhotoCaptured(p, empty)
                            }
                        }) {
                            Text("Use Photo Anyway")
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun CameraXCapturePreview(
    onImageSaved: (String) -> Unit,
    isCapturing: Boolean,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    android.util.Size(1920, 1440),
                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                )
            )
            .build()

        ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setJpegQuality(90)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val previewView = remember { PreviewView(context) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val cameraSelector = when {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(MediTrackApp.TAG, "CameraX binding failed: ${e.message}", e)
                cameraError = e.message
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(isCapturing) {
        if (isCapturing) {
            val baselineDir = File(context.filesDir, "baseline").apply {
                if (!exists()) mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val photoFile = File(baselineDir, "baseline_$timeStamp.jpg")

            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Log.d(MediTrackApp.TAG, "Baseline photo saved at: ${photoFile.absolutePath}")
                        onImageSaved(photoFile.absolutePath)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(MediTrackApp.TAG, "Photo capture failed: ${exception.message}", exception)
                        onError(exception)
                    }
                }
            )
        }
    }

    if (cameraError != null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Camera is not available on this device/emulator.\nYou can tap 'Skip for now' below to proceed.",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay framing guide for blister strip
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            )
        }
    }
}
