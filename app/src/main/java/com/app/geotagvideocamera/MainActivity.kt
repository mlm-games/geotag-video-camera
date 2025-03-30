package com.app.geotagvideocamera

import android.Manifest
import android.R
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private lateinit var locationManager: LocationManager
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingScreen = false
    private var screenDensity = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    var debugLocationListener: LocationListener? = null

    private val SCREEN_CAPTURE_REQUEST_CODE = 1001

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startApp()
            } else {
                Toast.makeText(
                    this,
                    "Permissions required for full functionality",
                    Toast.LENGTH_LONG
                ).show()
                startApp()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize location services
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Get screen metrics for recording
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Initialize MediaProjectionManager for screen recording
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Request required permissions
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        requestPermissions.launch(requiredPermissions.toTypedArray())
    }

    @Deprecated("This method has been deprecated in favor of using the Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                startScreenRecording()
            } else {
                Toast.makeText(
                    this,
                    "Screen recording permission denied. Using regular recording.",
                    Toast.LENGTH_SHORT
                ).show()
                startRegularRecording()
            }
        }
    }

    private fun startApp() {
        Toast.makeText(
            this,
            "Take a screenshot or use screen recording to capture the geo-tag overlay",
            Toast.LENGTH_LONG
        ).show()

        setContent {
            VideoRecorderApp(
                locationManager = locationManager,
                onVideoCaptureReady = { videoCapture = it },
                onRecordButtonClick = ::handleRecordButtonClick,
                onEnableDebugLocation = ::enableDebugTestLocation,
                onDisableDebugLocation = ::disableDebugTestLocation
            )
        }
    }

    fun enableDebugTestLocation() {
        Toast.makeText(this, "Debug location enabled - Golden Gate Bridge", Toast.LENGTH_SHORT)
            .show()

        val testLocation = Location("debug_provider").apply {
            latitude = 37.8199
            longitude = -122.4783
            altitude = 75.0
            speed = 5.0f
            accuracy = 3.0f
            time = System.currentTimeMillis()
        }
        debugLocationListener?.onLocationChanged(testLocation)
    }

    fun disableDebugTestLocation() {
        Toast.makeText(this, "Debug location disabled - using real GPS", Toast.LENGTH_SHORT).show()
    }

    private fun handleRecordButtonClick() {
        if (isRecordingScreen || recording != null) {
            stopRecording()
        } else {
            mediaProjectionManager?.createScreenCaptureIntent()?.let {
                startActivityForResult(
                    it,
                    SCREEN_CAPTURE_REQUEST_CODE
                )
            }
        }
    }

    private fun stopRecording() {
        if (isRecordingScreen) {
            stopScreenRecording()
        } else {
            recording?.stop()
            recording = null
        }
    }

    private fun startRegularRecording() {
        recording = startRecording(
            context = this,
            videoCapture = videoCapture,
            executor = ContextCompat.getMainExecutor(this)
        )
    }

    private fun startScreenRecording() {
        try {
            prepareMediaRecorder()

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GeotagCamera",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null,
                null
            )

            mediaRecorder?.start()
            isRecordingScreen = true

            Toast.makeText(this, "Screen recording with overlay started", Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Log.e("ScreenRecording", "Error starting screen recording: ${e.message}")
            e.printStackTrace()
            Toast.makeText(
                this,
                "Screen recording failed. Using regular recording.",
                Toast.LENGTH_SHORT
            ).show()
            startRegularRecording()
        }
    }

    private fun stopScreenRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            virtualDisplay?.release()
            mediaProjection?.stop()
            isRecordingScreen = false
            Toast.makeText(
                this,
                "Recording saved to Movies/GeotagCamera",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e("ScreenRecording", "Error stopping screen recording: ${e.message}")
        }
    }

    private fun prepareMediaRecorder() {
        val videoFilePath =
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)}/GeotagCamera"
        val videoFile = File(videoFilePath)
        if (!videoFile.exists()) {
            videoFile.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault())
            .format(System.currentTimeMillis())
        val outputFile = "$videoFilePath/GeotagVideo_$timestamp.mp4"

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(screenWidth, screenHeight)
            setVideoFrameRate(30)
            setOutputFile(outputFile)
            prepare()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.release()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}

// Location utilities
private fun useGooglePlayServicesLocation(context: Context, locationListener: LocationListener) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    val locationRequest = LocationRequest.create().apply {
        interval = 1000
        fastestInterval = 500
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }

    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        locationListener.onLocationChanged(location)
                    }
                }
            },
            Looper.getMainLooper()
        )
    }
}

private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val latDiff = abs(lat1 - lat2) * 111000
    val lonDiff = abs(lon1 - lon2) * 111000 * cos(Math.toRadians(lat1))
    return sqrt(latDiff * latDiff + lonDiff * lonDiff)
}

@Composable
fun VideoRecorderApp(
    locationManager: LocationManager,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onRecordButtonClick: () -> Unit,
    onEnableDebugLocation: () -> Unit,
    onDisableDebugLocation: () -> Unit,
) {
    val context = LocalContext.current
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    // API key state for Mapbox
    var mapboxApiKey by remember { mutableStateOf("") }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    // Location state
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }
    var gpsStatus by remember { mutableStateOf("Searching...") }

    // Dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }

    // On startup, check SharedPreferences for the saved Mapbox API key.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("GeotagPrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("mapbox_api_key", "") ?: ""
        if (savedKey.isEmpty()) {
            showApiKeyDialog = true
        } else {
            mapboxApiKey = savedKey
        }
    }

    // Update time every second
    LaunchedEffect(Unit) {
        while (true) {
            currentTime =
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    // Setup location updates
    DisposableEffect(Unit) {
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                currentSpeed = location.speed * 3.6f
                gpsStatus =
                    if (location.accuracy <= 10) "GPS Fixed" else "GPS Active (${location.accuracy.toInt()}m)"
            }

            override fun onProviderEnabled(provider: String) {
                gpsStatus = "GPS Enabled"
            }

            override fun onProviderDisabled(provider: String) {
                gpsStatus = "GPS Disabled"
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        if (context is MainActivity) {
            context.debugLocationListener = locationListener
        }

        var locationJob: Job? = null

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000,
                        0f,
                        locationListener
                    )
                    locationJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(10000)
                        if (currentLocation == null) {
                            locationManager.removeUpdates(locationListener)
                            useGooglePlayServicesLocation(context, locationListener)
                            gpsStatus = "Using Google Play Services Location"
                        }
                    }
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000,
                        0f,
                        locationListener
                    )
                    gpsStatus = "Using Network Location"
                    locationJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(10000)
                        if (currentLocation == null) {
                            locationManager.removeUpdates(locationListener)
                            useGooglePlayServicesLocation(context, locationListener)
                            gpsStatus = "Using Google Play Services Location"
                        }
                    }
                } else {
                    useGooglePlayServicesLocation(context, locationListener)
                    gpsStatus = "Using Google Play Services Location"
                }
            } catch (e: Exception) {
                useGooglePlayServicesLocation(context, locationListener)
                gpsStatus = "Location Error, Using Google Play Services"
            }
        } else {
            gpsStatus = "Location Permission Denied"
        }
        onDispose {
            locationManager.removeUpdates(locationListener)
            locationJob?.cancel()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        showSettingsDialog = true
                    }
                )
            }
    ) {
        // Camera Preview
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onVideoCaptureReady = onVideoCaptureReady
        )

        // Top status bar with time and GPS information
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentTime,
                    color = Color.White,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(
                            color = when {
                                gpsStatus.contains("Fixed") -> Color.Green
                                gpsStatus.contains("Active") -> Color(0xFFFFAA00)
                                else -> Color.Red
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = gpsStatus,
                        color = Color.White,
                        style = TextStyle(fontSize = 12.sp)
                    )
                }
            }
        }

        // Map overlay at bottom center using Mapbox static map API if key provided.
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(150.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                .background(Color(0xFFE8E8E8))
        ) {
            val location = currentLocation
            if (location != null) {
                var address by remember { mutableStateOf("Loading address...") }
                var mapBitmap by remember { mutableStateOf<Bitmap?>(null) }

                // Get address from location with postal code
                LaunchedEffect(location) {
                    try {
                        withContext(Dispatchers.IO) {
                            val geocoder =
                                android.location.Geocoder(context, Locale.getDefault())
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                                    if (addresses.isNotEmpty()) {
                                        val firstAddress = addresses[0]
                                        address = listOfNotNull(
                                            firstAddress.featureName,
                                            firstAddress.thoroughfare,
                                            firstAddress.subLocality,
                                            firstAddress.locality,
                                            firstAddress.postalCode,
                                            firstAddress.adminArea
                                        ).joinToString(", ")
                                        Log.d("MapDebug", "Full address: $address")
                                    } else {
                                        address = "Unknown location"
                                    }
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val addresses =
                                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                                if (addresses != null && addresses.isNotEmpty()) {
                                    val firstAddress = addresses[0]
                                    address = listOfNotNull(
                                        firstAddress.featureName,
                                        firstAddress.thoroughfare,
                                        firstAddress.subLocality,
                                        firstAddress.locality,
                                        firstAddress.postalCode,
                                        firstAddress.adminArea
                                    ).joinToString(", ")
                                    Log.d("MapDebug", "Full address: $address")
                                } else {
                                    address = "Unknown location"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Geocoder", "Error getting address", e)
                        address = "Address lookup failed"
                    }
                }

                // Load map image using Mapbox
                LaunchedEffect(location, mapboxApiKey) {
                    // Only load map if we have a valid API key.
                    if (mapboxApiKey.isNotEmpty()) {
                        val zoomLevel = 14
                        val width = 240
                        val height = 150
                        val staticMapUrl = "https://api.mapbox.com/styles/v1/mapbox/streets-v11/static/" +
                                "${location.longitude},${location.latitude},$zoomLevel/" +
                                "${width}x$height?access_token=$mapboxApiKey"
                        Log.d("MapDebug", "Loading static map from URL: $staticMapUrl")
                        try {
                            withContext(Dispatchers.IO) {
                                val url = URL(staticMapUrl)
                                val connection = url.openConnection() as HttpURLConnection
                                connection.doInput = true
                                connection.connect()
                                val input = connection.inputStream
                                mapBitmap = BitmapFactory.decodeStream(input)
                                if (mapBitmap != null) {
                                    Log.d("MapDebug", "Successfully loaded map image")
                                } else {
                                    Log.e("MapDebug", "Failed to decode bitmap from stream")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MapDebug", "Error loading map image: ${e.message}", e)
                        }
                    }
                }

                if (mapBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = mapBitmap!!.asImageBitmap(),
                        contentDescription = "Map of current location",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (mapboxApiKey.isEmpty())
                                "Map API key missing."
                            else "Loading map...",
                            color = Color.Gray
                        )
                    }
                }

                // Address display on top of the map
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = address,
                        color = Color.White,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Coordinates display at bottom of map
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format("%.6f°, %.6f°", location.latitude, location.longitude),
                        color = Color.White,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Waiting for location...",
                        color = Color.Gray
                    )
                }
            }
        }

        // Settings dialog (opened on double tap)
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = {
                    showSettingsDialog = false
                },
                title = {
                    Text("Debug Settings")
                },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                onEnableDebugLocation()
                                showSettingsDialog = false
                            }
                        ) {
                            Text("Enable Debug Location (Golden Gate Bridge)")
                        }
                        TextButton(
                            onClick = {
                                onDisableDebugLocation()
                                showSettingsDialog = false
                            }
                        ) {
                            Text("Disable Debug Location")
                        }
                        TextButton(
                            onClick = {
                                showApiKeyDialog = true
                                showSettingsDialog = false
                            }
                        ) {
                            Text("Set/Update Mapbox API Key")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showSettingsDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // API key input dialog
        if (showApiKeyDialog) {
            var tempKey by remember { mutableStateOf(mapboxApiKey) }
            AlertDialog(
                onDismissRequest = { showApiKeyDialog = false },
                title = { Text("Set Mapbox API Key") },
                text = {
                    Column {
                        Text("Please enter your Mapbox API key:")
                        TextField(
                            value = tempKey,
                            onValueChange = { tempKey = it },
                            placeholder = { Text("Your API key") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        mapboxApiKey = tempKey
                        // Save the provided key so it persists
                        val prefs = context.getSharedPreferences("GeotagPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("mapbox_api_key", mapboxApiKey).apply()
                        showApiKeyDialog = false
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showApiKeyDialog = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
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

        preview.surfaceProvider = previewView.surfaceProvider

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
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                withAudioEnabled()
            }
        }
        .start(executor) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    Toast.makeText(context, "Recording started", Toast.LENGTH_SHORT).show()
                }
                is VideoRecordEvent.Finalize -> {
                    if (recordEvent.hasError()) {
                        Toast.makeText(
                            context,
                            "Recording error: ${recordEvent.error}",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Recording saved: ${recordEvent.outputResults.outputUri}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
fun VideoRecorderAppPreview() {
    MapOverlayPreview()
}

@Composable
fun MapOverlayPreview() {
    val mockLat = 37.7749
    val mockLon = -122.4194
    val mockAlt = 12.5
    val mockSpeed = 18.7f

    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "12:34:56",
                    color = Color.White,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.Green, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "GPS Fixed",
                        color = Color.White,
                        style = TextStyle(fontSize = 12.sp)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(150.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                .background(Color(0xFFE0E0E0))
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Map Preview",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$mockLat, $mockLon",
                        fontSize = 12.sp
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 180.dp)
                .align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = String.format("%.6f°, %.6f°", mockLat, mockLon),
                    color = Color.White,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = String.format("Alt: %.1f m", mockAlt),
                        color = Color.White,
                        style = TextStyle(fontSize = 12.sp)
                    )
                    Text(
                        text = String.format("Speed: %.1f km/h", mockSpeed),
                        color = Color.White,
                        style = TextStyle(fontSize = 12.sp)
                    )
                }
            }
        }
    }
}