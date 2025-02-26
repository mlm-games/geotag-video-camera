package com.app.geotagvideocamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.google.android.gms.location.*
import com.google.android.gms.location.FusedLocationProviderClient
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startApp()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

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
            VideoRecorderApp(fusedLocationClient)
        }
    }
}

@Composable
fun VideoRecorderApp(fusedLocationClient: FusedLocationProviderClient) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var recording by remember { mutableStateOf<Recording?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // Location state
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentSpeed by remember { mutableStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }

    // Update time every second
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    // Setup location updates
    LaunchedEffect(Unit) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(1000)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    currentSpeed = location.speed * 3.6f // Convert m/s to km/h
                }
            }
        }

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                context.mainLooper
            )
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

            location?.let {
                Text(
                    text = "Lat: ${String.format("%.6f", it.latitude)} Lon: ${
                        String.format(
                            "%.6f",
                            it.longitude
                        )
                    }",
                    color = Color.White,
                    style = TextStyle(fontSize = 14.sp)
                )

                Text(
                    text = "Alt: ${
                        String.format(
                            "%.1f",
                            it.altitude
                        )
                    }m Speed: ${String.format("%.1f", speed)} km/h",
                    color = Color.White,
                    style = TextStyle(fontSize = 14.sp)
                )
            } ?: Text(
                text = "GPS Signal Searching...",
                color = Color.Yellow,
                style = TextStyle(fontSize = 14.sp)
            )
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
                    // Recording started
                }
                is VideoRecordEvent.Finalize -> {
                    if (recordEvent.hasError()) {
                        //TODO: Handle error
                    } else {
                        // Video saved successfully
                    }
                }
            }
        }
}