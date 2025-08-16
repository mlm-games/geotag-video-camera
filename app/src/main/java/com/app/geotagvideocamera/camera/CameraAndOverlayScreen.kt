package com.app.geotagvideocamera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.geotagvideocamera.CameraMode
import com.app.geotagvideocamera.CameraModeToggle
import com.app.geotagvideocamera.location.LocationTracker
import com.app.geotagvideocamera.location.LocationUi
import com.app.geotagvideocamera.location.formatLatLon
import com.app.geotagvideocamera.location.formatSpeed
import com.app.geotagvideocamera.map.MapOverlay
import com.app.geotagvideocamera.settings.SettingsState
import com.app.geotagvideocamera.settings.SettingsViewModel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.camera.view.PreviewView
import androidx.compose.ui.zIndex


@SuppressLint("MissingPermission")
@Composable
fun CameraAndOverlayScreen(
    settingsVm: SettingsViewModel,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
    LaunchedEffect(locGranted) {
        if (locGranted) tracker.start() else tracker.stop()
    }
    val locationUi by tracker.state.collectAsStateWithLifecycle()

    // UI
    var mode by remember { mutableStateOf(CameraMode.PHOTO) }

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
            }.getOrNull()

            if (selector == null) return@LaunchedEffect

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview)
            }.onFailure { e ->
                android.util.Log.e("CameraPreview", "Failed to bind camera", e)
            }
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
                .zIndex(0f),
        )

        if (settings.showTopBar) {
            TopStatusBar(
                timeText = timeText,
                accuracy = locationUi?.accuracyMeters
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

        if (!settings.hideModeButton) {
            CameraModeToggle(
                currentMode = mode,
                onModeChanged = { mode = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun TopStatusBar(timeText: String, accuracy: Float?) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 8.dp, end = 8.dp)
//            .align(Alignment.TopCenter)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(timeText, color = Color.White, fontSize = 14.sp)
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
                    fontSize = 12.sp
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
    // Sizes roughly matching the old UI
    val cardWidth = 240.dp
    val cardHeight = 280.dp

    // Decide where to place the address text
    val address = loc?.address ?: "—"
    val showAddress = settings.showAddress
    val addrPos = settings.addressPositionIndex // 0 = inside top, 1 = inside bottom, 2 = above map

    // Container (bottom-center)
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 100.dp)
            .size(cardWidth, cardHeight)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, Color.White, RoundedCornerShape(12.dp))
    ) {
        // Map content
        MapOverlay(
            settings = settings,
            lat = loc?.latitude,
            lon = loc?.longitude,
            modifier = Modifier.fillMaxSize()
        )

        // Address inside map (top/bottom)
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
                    fontSize = 10.sp,
                    modifier = Modifier.padding(6.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Coordinates strip (like old, along bottom)
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
                    fontSize = 10.sp,
                    modifier = Modifier.padding(6.dp),
                    maxLines = 1
                )
            }
        }
    }

    // Address below map (if selected)
    if (showAddress && addrPos == 2) {
        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp) // roughly below the map card
        ) {
            Text(
                text = address,
                color = Color.White,
                fontSize = 10.sp,
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),

    ) {
        if (false) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = loc?.let { formatLatLon(it.latitude, it.longitude) } ?: "—",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (settings.showSpeed) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = loc?.let { formatSpeed(it.speedMps ?: 0f, settings.unitsIndex) } ?: "—",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 14.sp
                    )
                }
            }

            if (settings.showGpsStatus) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "±${loc?.accuracyMeters?.toInt() ?: 0} m",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (false) { // Address on top, old format is better, left it for a setting in future
            Spacer(Modifier.height(6.dp))
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = loc?.address ?: "—",
                    color = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/* Helpers */
private suspend fun android.content.Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { cont.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }