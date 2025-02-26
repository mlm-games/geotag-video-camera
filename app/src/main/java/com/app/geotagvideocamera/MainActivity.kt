package com.app.geotagvideocamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : ComponentActivity() {
    private lateinit var locationManager: LocationManager

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            startApp()
        } else {
            Toast.makeText(this, "Permissions required for full functionality", Toast.LENGTH_LONG).show()
            startApp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        requestPermissions.launch(requiredPermissions.toTypedArray())
    }

    private fun startApp() {
        setContent {
            VideoRecorderApp(locationManager)
        }
    }
}

@Composable
fun VideoRecorderApp(locationManager: LocationManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var recording by remember { mutableStateOf<Recording?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // Location state
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentSpeed by remember { mutableStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }
    var gpsStatus by remember { mutableStateOf("Searching...") }

    // Update time every second
    LaunchedEffect(Unit) {
        while(true) {
            currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    // Setup location updates using Android's LocationManager (more reliable than Google Play Services)
    DisposableEffect(Unit) {
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                currentSpeed = location.speed * 3.6f // Convert m/s to km/h
                gpsStatus = if (location.accuracy <= 10) "GPS Fixed" else "GPS Active (${location.accuracy.toInt()}m)"
            }

            override fun onProviderEnabled(provider: String) {
                gpsStatus = "GPS Enabled"
            }

            override fun onProviderDisabled(provider: String) {
                gpsStatus = "GPS Disabled"
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                // Required for older Android versions
            }
        }

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {
            try {
                // Try to use GPS provider first
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000, // 1 second
                        0f, // 0 meters
                        locationListener
                    )
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    // Fall back to network provider if GPS is not available
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000,
                        0f,
                        locationListener
                    )
                    gpsStatus = "Using Network Location"
                } else {
                    gpsStatus = "Location Providers Disabled"
                }
            } catch (e: Exception) {
                gpsStatus = "Location Error: ${e.message}"
            }
        } else {
            gpsStatus = "Location Permission Denied"
        }

        onDispose {
            locationManager.removeUpdates(locationListener)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onVideoCaptureReady = { videoCapture = it }
        )

        // Geotag overlay
        GeotagOverlay(
            location = currentLocation,
            speed = currentSpeed,
            time = currentTime,
            gpsStatus = gpsStatus,
            isRecording = recording != null,
            modifier = Modifier.fillMaxSize()
        )

        Button(
            onClick = {
                if (recording != null) {
                    recording?.stop()
                    recording = null
                } else {
                    recording = startRecording(
                        context = context,
                        videoCapture = videoCapture,
                        executor = executor
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Text(if (recording == null) "Start Recording" else "Stop Recording")
        }
    }
}

@Composable
fun GeotagOverlay(
    location: Location?,
    speed: Float,
    time: String,
    gpsStatus: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Top info bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(8.dp)
        ) {
            Text(
                text = time,
                color = Color.White,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
            )

            Text(
                text = gpsStatus,
                color = if (gpsStatus.contains("Fixed") || gpsStatus.contains("Active")) Color.Green else Color.Yellow,
                style = TextStyle(fontSize = 14.sp)
            )

            location?.let {
                Text(
                    text = "Lat: ${String.format("%.6f", it.latitude)} Lon: ${String.format("%.6f", it.longitude)}",
                    color = Color.White,
                    style = TextStyle(fontSize = 14.sp)
                )

                Text(
                    text = "Alt: ${String.format("%.1f", it.altitude)}m Speed: ${String.format("%.1f", speed)} km/h",
                    color = Color.White,
                    style = TextStyle(fontSize = 14.sp)
                )
            }
        }

        // Recording indicator
        if (isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(Color.Red)
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "REC",
                    color = Color.Red,
                    style = TextStyle(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(previewView) {
        val cameraProvider = context.getCameraProvider()
        val preview = androidx.camera.core.Preview.Builder().build()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        preview.setSurfaceProvider(previewView.surfaceProvider)

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture
            )

            onVideoCaptureReady(videoCapture)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { future ->
        future.addListener({
            continuation.resume(future.get())
        }, ContextCompat.getMainExecutor(this))
    }
}

fun startRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>?,
    executor: Executor
): Recording? {
    if (videoCapture == null) return null

    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault())
        .format(System.currentTimeMillis())

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GeotagCamera")
        }
    }

    val mediaStoreOutputOptions = MediaStoreOutputOptions
        .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        .setContentValues(contentValues)
        .build()

    return videoCapture.output
        .prepareRecording(context, mediaStoreOutputOptions)
        .apply {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED) {
                withAudioEnabled()
            }
        }
        .start(executor) { recordEvent ->
            when(recordEvent) {
                is VideoRecordEvent.Start -> {
                    Toast.makeText(context, "Recording started", Toast.LENGTH_SHORT).show()
                }
                is VideoRecordEvent.Finalize -> {
                    if (recordEvent.hasError()) {
                        Toast.makeText(context, "Recording error: ${recordEvent.error}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Recording saved: ${recordEvent.outputResults.outputUri}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
}