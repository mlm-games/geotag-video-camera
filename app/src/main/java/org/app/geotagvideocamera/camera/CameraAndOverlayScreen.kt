package org.app.geotagvideocamera.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.app.geotagvideocamera.CameraMode
import org.app.geotagvideocamera.CameraModeToggle
import org.app.geotagvideocamera.MediaUtils
import org.app.geotagvideocamera.R
import org.app.geotagvideocamera.location.LocationTracker
import org.app.geotagvideocamera.location.LocationUi
import org.app.geotagvideocamera.location.formatLatLon
import org.app.geotagvideocamera.location.formatSpeed
import org.app.geotagvideocamera.map.MapOverlay
import org.app.geotagvideocamera.settings.SettingsState
import org.app.geotagvideocamera.settings.SettingsViewModel
import kotlin.coroutines.resume

@Composable
fun CameraAndOverlayScreen(
    settingsVm: SettingsViewModel,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by settingsVm.state.collectAsStateWithLifecycle()

    // Permission handling
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
    var implMode by remember { mutableStateOf(ImplementationMode.EMBEDDED) }
    LaunchedEffect(Unit) {
        while (true) {
            timeText = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(System.currentTimeMillis())
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        val req = buildList {
            if (!camGranted) add(Manifest.permission.CAMERA)
            if (!locGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (req.isNotEmpty()) permLauncher.launch(req.toTypedArray())
    }

    // CameraX provider
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        cameraProvider.value = context.getCameraProvider()
    }

    // Location tracker
    val tracker = remember { LocationTracker(context) }

    LaunchedEffect(locGranted, settings.debugLocation) {
        if (settings.debugLocation) {
            tracker.stop()
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

    // CameraX Viewfinder state
    val surfaceRequestState = remember { mutableStateOf<SurfaceRequest?>(null) }

    // Build Preview use case
    val preview = remember {
        Preview.Builder().build().also { p ->
            p.setSurfaceProvider { request ->
                surfaceRequestState.value = request
            }
        }
    }

    // Bind camera when provider/permission/settings change
    LaunchedEffect(camGranted, cameraProvider.value, mode, settings.cameraFacing) {
        if (!camGranted) return@LaunchedEffect
        val provider = cameraProvider.value ?: return@LaunchedEffect

        val preferredSelector = if (settings.cameraFacing == 1) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val fallbackSelector = if (settings.cameraFacing == 1) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val selector = runCatching {
            when {
                provider.hasCamera(preferredSelector) -> preferredSelector
                provider.hasCamera(fallbackSelector) -> fallbackSelector
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

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onOpenSettings() }
                )
            }
    ) {
        // CameraX Viewfinder (EMBEDDED mode for screenshot capture, yet has a problem with the capture button)
        surfaceRequestState.value?.let { sr ->
            CameraXViewfinder(
                surfaceRequest = sr,
                implementationMode = implMode,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().zIndex(0f)
            )
        }

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
        } else if (settings.showLocationTextWithoutMap) {
            StandaloneLocationOverlay(
                settings = settings,
                loc = locationUi
            )
        }

        OverlayHud(
            settings = settings,
            loc = locationUi
        )

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

        if (!settings.hideModeButton && !controlsHidden) {
            FloatingActionButton(
                onClick = {
                    when (mode) {
                        CameraMode.PHOTO -> {
                            isCapturing = true
                            scope.launch {
                                withFrameNanos { }
                                delay(16)
                                val activity = context.findActivity()!!
                                val bmp = copyWindowBitmap(activity)
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
                            Toast.makeText(
                                context,
                                "Use Android's screen recording feature (from Settings or Quick Tiles)",
                                Toast.LENGTH_SHORT
                            ).show()
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
                    CameraMode.PHOTO -> Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_camera),
                        contentDescription = "Screenshot"
                    )
                    CameraMode.VIDEO -> Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_videocam),
                        contentDescription = "Start recording"
                    )
                }
            }
        }

        if (isRecording) {
            RecordingBar(
                startNanos = recordingStartNanos,
                onStop = {
                    isRecording = false
                    Toast.makeText(context, "Recording saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            )
        }

        val usingDemoTiles = remember(settings) { usesDemoTiles(settings) }
        if (usingDemoTiles && !settings.demoNoticeShown) {
            AlertDialog(
                onDismissRequest = { settingsVm.markDemoNoticeShown() },
                title = { Text("Map tiles notice") },
                text = {
                    Text(
                        "The app initially starts with MapLibre demo tiles. They are minimal maps that do not show terrain (only for demonstration purposes). " +
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
            delay(250)
            now = System.nanoTime()
        }
    }
    val elapsedSec = ((now - startNanos) / 1_000_000_000L).coerceAtLeast(0)
    val mm = (elapsedSec / 60).toString().padStart(2, '0')
    val ss = (elapsedSec % 60).toString().padStart(2, '0')

    Surface(
        color = Color(0xB3B00020),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_videocam),
                contentDescription = null,
                tint = Color.White
            )
            Text(text = "$mm:$ss REC", color = Color.White)
            TextButton(onClick = onStop) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_stop),
                    contentDescription = "Stop",
                    tint = Color.White
                )
                Spacer(Modifier.size(6.dp))
                Text("Stop", color = Color.White)
            }
        }
    }
}

private fun usesDemoTiles(s: SettingsState): Boolean {
    return when (s.mapProviderIndex) {
        0 -> s.styleUrl.isBlank()
        1 -> s.maptilerApiKey.isBlank()
        else -> s.geoapifyApiKey.isBlank()
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
                    (accuracy ?: Float.MAX_VALUE) <= 10f -> Color(0xFF00C853)
                    (accuracy ?: Float.MAX_VALUE) <= 30f -> Color(0xFFFFAB00)
                    else -> Color(0xFFD50000)
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

    // Inc. bottom padding for map card based on whether address is below
    val showAddressBelow = settings.showAddress && settings.addressPositionIndex == 2
    val mapBottomPadding = when {
        showAddressBelow && settings.compactUi -> 140.dp
        showAddressBelow -> 160.dp
        else -> 100.dp
    }

    val address = loc?.address ?: "—"
    val showAddress = settings.showAddress
    val addrPos = settings.addressPositionIndex

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = mapBottomPadding)
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
        val addressBottomPadding = if (settings.compactUi) 100.dp else 110.dp
        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = addressBottomPadding, start = 16.dp, end = 16.dp)
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
private fun BoxScope.StandaloneLocationOverlay(
    settings: SettingsState,
    loc: LocationUi?
) {
    val showCoords = settings.showCoordinates
    val showAddr = settings.showAddress

    if (!showCoords && !showAddr) return

    val fontSize = if (settings.compactUi) 11.sp else 13.sp
    val cornerRadius = if (settings.compactUi) 8.dp else 10.dp

    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(cornerRadius),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 100.dp, start = 16.dp, end = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showCoords) {
                val coordText = loc?.let { formatLatLon(it.latitude, it.longitude) } ?: "—"
                Text(
                    text = coordText,
                    color = Color.White,
                    fontSize = fontSize,
                    maxLines = 1,
                    textAlign = TextAlign.Right
                )
            }
            if (showAddr) {
                val address = loc?.address ?: "—"
                Text(
                    text = address,
                    color = Color.White,
                    fontSize = fontSize,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private suspend fun copyWindowBitmap(activity: Activity): Bitmap? {
    val decor = activity.window?.decorView ?: return null
    if (decor.width <= 0 || decor.height <= 0) return null

    val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)

    return suspendCancellableCoroutine { cont ->
        try {
            PixelCopy.request(
                activity.window,
                bmp,
                { result ->
                    if (result == PixelCopy.SUCCESS) cont.resume(bmp)
                    else cont.resume(null)
                },
                Handler(Looper.getMainLooper())
            )
        } catch (_: Throwable) {
            cont.resume(null)
        }
    }
}