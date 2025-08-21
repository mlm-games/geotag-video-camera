package com.app.geotagvideocamera.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.drawToBitmap
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.geotagvideocamera.CameraMode
import com.app.geotagvideocamera.CameraModeToggle
import com.app.geotagvideocamera.MediaUtils
import com.app.geotagvideocamera.location.LocationTracker
import com.app.geotagvideocamera.location.LocationUi
import com.app.geotagvideocamera.location.formatLatLon
import com.app.geotagvideocamera.location.formatSpeed
import com.app.geotagvideocamera.map.MapOverlay
import com.app.geotagvideocamera.settings.SettingsState
import com.app.geotagvideocamera.settings.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
fun CameraAndOverlayScreen(
    settingsVm: SettingsViewModel,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootView = LocalView.current

    // Observe settings
    val settings by settingsVm.state.collectAsStateWithLifecycle()

    // Request permissions (CAMERA + FINE LOCATION)
    val hasCamera = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val hasLocation = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    var camGranted by remember { mutableStateOf(hasCamera) }
    var locGranted by remember { mutableStateOf(hasLocation) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        camGranted = result[Manifest.permission.CAMERA] == true || camGranted
        locGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || locGranted
    }
    var timeText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            timeText = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(System.currentTimeMillis())
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        val req = buildList {
            if (!camGranted) add(Manifest.permission.CAMERA)
            if (!locGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (req.isNotEmpty()) permLauncher.launch(req.toTypedArray())
    }

    // CameraX preview setup
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        cameraProvider.value = context.getCameraProvider()
    }

    // Location tracker
    val tracker = remember { LocationTracker(context) }

    LaunchedEffect(locGranted, settings.debugLocation) {
        if (settings.debugLocation) {
            tracker.stop()
            // Golden Gate Bridge
            tracker.pushDebugLocation(lat = 37.8199, lon = -122.4783)
        } else {
            if (locGranted) tracker.start() else tracker.stop()
        }
    }

    val locationUi by tracker.state.collectAsStateWithLifecycle()

    // UI states
    var mode by remember { mutableStateOf(CameraMode.PHOTO) }
    var isCapturing by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartNanos by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    // MediaProjection / Recording plumbing
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    var mediaProjection by remember { mutableStateOf<MediaProjection?>(null) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var virtualDisplay by remember { mutableStateOf<VirtualDisplay?>(null) }

    fun stopRecording() {
        runCatching { virtualDisplay?.release() }.onFailure { /* ignore */ }
        virtualDisplay = null
        runCatching { mediaProjection?.stop() }.onFailure { /* ignore */ }
        mediaProjection = null
        runCatching {
            mediaRecorder?.apply {
                try { stop() } catch (_: Throwable) { /* ignore */ }
                reset()
                release()
            }
        }.onFailure { /* ignore */ }
        mediaRecorder = null
        isRecording = false
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val mp = projectionManager.getMediaProjection(res.resultCode, res.data!!)
            mediaProjection = mp

            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val recorder = MediaUtils.createMediaRecorder(context, width, height)
            mediaRecorder = recorder

            val surface = recorder.surface
            virtualDisplay = mp?.createVirtualDisplay(
                "GeotagScreenRecord",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            try {
                recorder.start()
                recordingStartNanos = System.nanoTime()
                isRecording = true
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
                stopRecording()
            }
        } else {
            Toast.makeText(context, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // UI
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onOpenSettings() }
                )
            }
    ) {
        val previewView = remember {
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // TextureView-based implementation → lets us include it in screenshots.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }

        // Bind whenever provider/permission/mode changes
        LaunchedEffect(camGranted, cameraProvider.value, mode) {
            if (!camGranted) return@LaunchedEffect
            val provider = cameraProvider.value ?: return@LaunchedEffect

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // Prefer back camera; fall back to front if needed (e.g., some emulators)
            val selector = runCatching {
                when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
            }.getOrNull() ?: return@LaunchedEffect

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview)
            }.onFailure { e ->
                android.util.Log.e("CameraPreview", "Failed to bind camera", e)
            }
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().zIndex(0f),
        )

        if (settings.showTopBar) {
            TopStatusBar(
                timeText = timeText,
                accuracy = locationUi?.accuracyMeters,
                dense = settings.compactUi
            )
        }

        if (settings.showMap) {
            MapCard(
                settings = settings,
                loc = locationUi
            )
        }

        OverlayHud(
            settings = settings,
            loc = locationUi
        )

        //cam button hidden during screenshot/recording
        val controlsHidden = isCapturing || isRecording
        if (!settings.hideModeButton && !controlsHidden) {
            CameraModeToggle(
                currentMode = mode,
                onModeChanged = { mode = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )
        }

        // Action button:
        // - PHOTO mode → Screenshot
        if (!settings.hideModeButton && !controlsHidden) {
            FloatingActionButton(
                onClick = {
                    when (mode) {
                        CameraMode.PHOTO -> {
                            isCapturing = true
                            // Hide the buttons before capture
                            scope.launch {
                                withFrameNanos { /* wait one frame so controls are hidden */ }
                                val bmp = safeDrawToBitmap(rootView)
                                if (bmp != null) {
                                    val uri = MediaUtils.saveBitmapToPictures(context, bmp)
                                    if (uri != null) {
                                        Toast.makeText(context, "Screenshot saved", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Failed to capture screenshot", Toast.LENGTH_SHORT).show()
                                }
                                isCapturing = false
                            }
                        }
                        CameraMode.VIDEO -> {
                              Toast.makeText(context, "Use android's screen recording feature (from Settings or Quick Tiles", Toast.LENGTH_SHORT).show()
//                            val intent = projectionManager.createScreenCaptureIntent()
//                            screenCaptureLauncher.launch(intent)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = Color(0xFF1E88E5),
                contentColor = Color.White
            ) {
                when (mode) {
                    CameraMode.PHOTO -> Icon(Icons.Outlined.CameraAlt, contentDescription = "Screenshot")
                    CameraMode.VIDEO -> Icon(Icons.Filled.RadioButtonChecked, contentDescription = "Start recording")
                }
            }
        }

        // Recording bar (shown only while recording)
        if (isRecording) {
            RecordingBar(
                startNanos = recordingStartNanos,
                onStop = {
                    stopRecording()
                    Toast.makeText(context, "Recording saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            )
        }

        // One-time notice: using demo tiles (MapLibre demo) → suggest MapTiler/Geoapify
        val usingDemoTiles = remember(settings) { usesDemoTiles(settings) }
        if (usingDemoTiles && !settings.demoNoticeShown) {
            AlertDialog(
                onDismissRequest = { settingsVm.markDemoNoticeShown() },
                title = { Text("Map tiles notice") },
                text = {
                    Text(
                        "The app initially starts with MapLibre demo tiles. They are minimal maps that do not show terrain ( only for demonstration purposes) " +
                                "For better maps, switch to MapTiler or Geoapify in Settings → Map and enter your API key. " +
                                "Double‑tap anywhere to open Settings."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { settingsVm.markDemoNoticeShown() }) {
                        Text("Got it")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        settingsVm.markDemoNoticeShown()
                        onOpenSettings()
                    }) {
                        Text("Open Settings")
                    }
                }
            )
        }
    }
}

@Composable
private fun RecordingBar(
    startNanos: Long,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(250)
            now = System.nanoTime()
        }
    }
    val elapsedSec = ((now - startNanos) / 1_000_000_000L).coerceAtLeast(0)
    val mm = (elapsedSec / 60).toString().padStart(2, '0')
    val ss = (elapsedSec % 60).toString().padStart(2, '0')

    Surface(
        color = Color(0xB3B00020), // semi-transparent red
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color.White)
            Text(text = "$mm:$ss REC", color = Color.White)
            TextButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Color.White)
                Spacer(Modifier.size(6.dp))
                Text("Stop", color = Color.White)
            }
        }
    }
}

private fun usesDemoTiles(s: SettingsState): Boolean {
    return when (s.mapProviderIndex) {
        0 -> s.styleUrl.isBlank() // MapLibre provider with no custom style → demo tiles
        1 -> s.maptilerApiKey.isBlank() // MapTiler with no key → fallback demo
        else -> s.geoapifyApiKey.isBlank() // Geoapify no key → fallback demo
    }
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { cont.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }

private fun safeDrawToBitmap(view: View): android.graphics.Bitmap? =
    runCatching { view.rootView.drawToBitmap() }.getOrNull()

@Composable
private fun TopStatusBar(timeText: String, accuracy: Float?, dense: Boolean) {
    val padV = if (dense) 4.dp else 6.dp
    val corner = if (dense) 6.dp else 8.dp
    val timeSize = if (dense) 12.sp else 14.sp
    val accSize = if (dense) 10.sp else 12.sp

    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(corner),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 8.dp, end = 8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = padV),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(timeText, color = Color.White, fontSize = timeSize)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val col = when {
                    (accuracy ?: Float.MAX_VALUE) <= 10f -> Color(0xFF00C853) // green
                    (accuracy ?: Float.MAX_VALUE) <= 30f -> Color(0xFFFFAB00) // amber
                    else -> Color(0xFFD50000) // red
                }
                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
                    drawCircle(color = col)
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "±${accuracy?.toInt() ?: 0} m",
                    color = Color.White,
                    fontSize = accSize
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MapCard(
    settings: SettingsState,
    loc: LocationUi?
) {
    val cardWidth = if (settings.compactUi) 200.dp else 240.dp
    val cardHeight = if (settings.compactUi) 220.dp else 280.dp

    val address = loc?.address ?: "—"
    val showAddress = settings.showAddress
    val addrPos = settings.addressPositionIndex // 0 = inside top, 1 = inside bottom, 2 = above map

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 100.dp)
            .size(cardWidth, cardHeight)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, Color.White, RoundedCornerShape(12.dp))
    ) {
        MapOverlay(
            settings = settings,
            lat = loc?.latitude,
            lon = loc?.longitude,
            modifier = Modifier.fillMaxSize()
        )

        if (showAddress && addrPos in 0..1) {
            val alignment = if (addrPos == 0) Alignment.TopCenter else Alignment.BottomCenter
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .align(alignment)
                    .fillMaxWidth()
            ) {
                Text(
                    text = address,
                    color = Color.White,
                    fontSize = if (settings.compactUi) 9.sp else 10.sp,
                    modifier = Modifier.padding(6.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (settings.showCoordinates) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                val coordText = loc?.let { formatLatLon(it.latitude, it.longitude) } ?: "—"
                Text(
                    text = coordText,
                    color = Color.White,
                    fontSize = if (settings.compactUi) 9.sp else 10.sp,
                    modifier = Modifier.padding(6.dp),
                    maxLines = 1
                )
            }
        }
    }

    if (showAddress && addrPos == 2) {
        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (settings.compactUi) 44.dp else 50.dp)
        ) {
            Text(
                text = address,
                color = Color.White,
                fontSize = if (settings.compactUi) 9.sp else 10.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OverlayHud(
    settings: SettingsState,
    loc: LocationUi?
) {
    val chipFont = if (settings.compactUi) 12.sp else 14.sp
    val chipPadH = if (settings.compactUi) 8.dp else 10.dp
    val chipPadV = if (settings.compactUi) 4.dp else 6.dp
    val chipCorner = if (settings.compactUi) 6.dp else 8.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (settings.showSpeed) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(chipCorner)
                ) {
                    Text(
                        text = loc?.let { formatSpeed(it.speedMps ?: 0f, settings.unitsIndex) } ?: "—",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = chipPadH, vertical = chipPadV),
                        fontSize = chipFont
                    )
                }
            }

            if (settings.showGpsStatus) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(chipCorner)
                ) {
                    Text(
                        text = "±${loc?.accuracyMeters?.toInt() ?: 0} m",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = chipPadH, vertical = chipPadV),
                        fontSize = chipFont
                    )
                }
            }
        }
    }
}