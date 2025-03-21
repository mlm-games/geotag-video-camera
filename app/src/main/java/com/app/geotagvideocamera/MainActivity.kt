package com.app.geotagvideocamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

//TODO: Add in readme, for recording below android 10, use an external screen recorder instead (as the default android screen recorder isn't present).
// TODO: Double tapping the screen should open settings, the map should be smaller in height, the settings should have the hide status bar and hide nav bar options?
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

    private val SCREEN_CAPTURE_REQUEST_CODE = 1001

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

        // Initialize location services
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Get screen metrics for recording
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Initialize MediaProjectionManager for screen recording
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

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

        // Add screen recording permissions for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        requestPermissions.launch(requiredPermissions.toTypedArray())
    }

    @Deprecated("This method has been deprecated in favor of using the Activity Result API\n      which brings increased type safety via an {@link ActivityResultContract} and the prebuilt\n      contracts for common intents available in\n      {@link androidx.activity.result.contract.ActivityResultContracts}, provides hooks for\n      testing, and allow receiving results in separate, testable classes independent from your\n      activity. Use\n      {@link #registerForActivityResult(ActivityResultContract, ActivityResultCallback)}\n      with the appropriate {@link ActivityResultContract} and handling the result in the\n      {@link ActivityResultCallback#onActivityResult(Object) callback}.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Start recording with projection permission
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                startScreenRecording()
            } else {
                // Fall back to regular video recording
                Toast.makeText(this, "Screen recording permission denied. Using regular recording.", Toast.LENGTH_SHORT).show()
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
                onDoubleTapSettings = {
                    // Show a simple settings dialog or toast for now
                    Toast.makeText(this, "Settings (coming soon)", Toast.LENGTH_SHORT).show()
                }

            )
        }
    }

    private fun handleRecordButtonClick() {
        if (isRecordingScreen || recording != null) {
            stopRecording()
        } else {
            // Request screen capture permission
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

            // Create virtual display for recording
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

            // Start recording
            mediaRecorder?.start()
            isRecordingScreen = true

            Toast.makeText(this, "Screen recording with overlay started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ScreenRecording", "Error starting screen recording: ${e.message}")
            e.printStackTrace()

            // Fall back to regular video recording
            Toast.makeText(this, "Screen recording failed. Using regular recording.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Recording saved to Movies/GeotagCamera", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ScreenRecording", "Error stopping screen recording: ${e.message}")
        }
    }

    private fun prepareMediaRecorder() {
        val videoFilePath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)}/GeotagCamera"
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
        // Clean up resources
        mediaRecorder?.release()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}

// Location utilities
private fun useGooglePlayServicesLocation(context: Context, locationListener: LocationListener) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    val locationRequest = LocationRequest.create().apply {
        interval = 1000 // Update interval in milliseconds
        fastestInterval = 500
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }

    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED) {

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        // Use the same listener to maintain consistent behavior
                        locationListener.onLocationChanged(location)
                    }
                }
            },
            Looper.getMainLooper()
        )
    }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// Map utilities
private var lastMapUpdateLat = 0.0
private var lastMapUpdateLon = 0.0
private var lastMapUpdateTime = 0L

private fun shouldUpdateMap(lat: Double, lon: Double): Boolean {
    val now = System.currentTimeMillis()
    // Only update if moved more than 50 meters or 10 seconds passed
    val distanceMoved = calculateDistance(lastMapUpdateLat, lastMapUpdateLon, lat, lon)
    val timePassed = now - lastMapUpdateTime

    return if (distanceMoved > 50 || timePassed > 10000) {
        lastMapUpdateLat = lat
        lastMapUpdateLon = lon
        lastMapUpdateTime = now
        true
    } else {
        false
    }
}

private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    // Simple distance calculation
    val latDiff = abs(lat1 - lat2) * 111000 // approx meters per degree of latitude
    val lonDiff = abs(lon1 - lon2) * 111000 * cos(Math.toRadians(lat1))
    return sqrt(latDiff * latDiff + lonDiff * lonDiff)
}

private fun getLeafletMapHtml(lat: Double, lon: Double, zoom: Int = 15): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body, html { margin: 0; padding: 0; height: 100%; overflow: hidden; }
                #map { width: 100%; height: 100%; background: #f0f0f0; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                document.addEventListener('DOMContentLoaded', function() {
                    var map = L.map('map', {
                        zoomControl: false,
                        attributionControl: false
                    }).setView([$lat, $lon], $zoom);

                    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        maxZoom: 19
                    }).addTo(map);

                    // Add a marker
                    var marker = L.marker([$lat, $lon]).addTo(map);
                });
            </script>
        </body>
        </html>
    """.trimIndent()
}

private fun readAssetContent(context: Context, assetFileName: String): String? {
    return try {
        context.assets.open(assetFileName)
            .bufferedReader()
            .use { it.readText() }
    } catch (e: IOException) {
        Log.e("AssetError", "Error reading asset file: $assetFileName", e)
        null
    }
}

@Composable
fun VideoRecorderApp(
    locationManager: LocationManager,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onRecordButtonClick: () -> Unit,
    onDoubleTapSettings: () -> Unit
) {
    val context = LocalContext.current
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    // Location state
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }
    var gpsStatus by remember { mutableStateOf("Searching...") }

    // WebView state
    var leafletMapHtml by remember { mutableStateOf("") }

    // Initialize Leaflet Map HTML
    LaunchedEffect(Unit) {
        val initialLat = 0.0
        val initialLon = 0.0
        leafletMapHtml = getLeafletMapHtml(initialLat, initialLon)
    }

    // Update time every second
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    // Setup location updates
    DisposableEffect(Unit) {
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                currentSpeed = location.speed * 3.6f // Convert m/s to km/h
                gpsStatus =
                    if (location.accuracy <= 10) "GPS Fixed" else "GPS Active (${location.accuracy.toInt()}m)"

                // Update Leaflet Map HTML with the new location
                leafletMapHtml = getLeafletMapHtml(location.latitude, location.longitude)
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

        var locationJob: Job? = null

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                // Try to use GPS provider first
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000, // 1 second
                        0f, // 0 meters
                        locationListener
                    )

                    // Set a timeout using coroutine
                    locationJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(10000) // 10 seconds timeout
                        if (currentLocation == null) {
                            // No location after 10 seconds, switch to Google Play Services
                            locationManager.removeUpdates(locationListener)
                            useGooglePlayServicesLocation(context, locationListener)
                            gpsStatus = "Using Google Play Services Location"
                        }
                    }

                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    // Fall back to network provider if GPS is not available
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000,
                        0f,
                        locationListener
                    )
                    gpsStatus = "Using Network Location"

                    // Also set a timeout for network provider
                    locationJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(10000) // 10 seconds timeout
                        if (currentLocation == null) {
                            // No location after 10 seconds, switch to Google Play Services
                            locationManager.removeUpdates(locationListener)
                            useGooglePlayServicesLocation(context, locationListener)
                            gpsStatus = "Using Google Play Services Location"
                        }
                    }
                } else {
                    // If neither GPS nor network is available, use Google Play Services directly
                    useGooglePlayServicesLocation(context, locationListener)
                    gpsStatus = "Using Google Play Services Location"
                }
            } catch (e: Exception) {
                // On any error, try Google Play Services as fallback
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onVideoCaptureReady = onVideoCaptureReady,
//            onDoubleTap = onDoubleTapSettings
        )

        // Top status bar with GPS information
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
                                gpsStatus.contains("Active") -> Color(0xFFFFAA00) // Orange
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

        // Map overlay at bottom center
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(150.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                                val uri = request.url
                                if (uri.scheme == "file" && uri.path?.startsWith("/android_asset/") == true) {
                                    val assetPath = uri.path!!.substring("/android_asset/".length)
                                    return try {
                                        val inputStream = context.assets.open(assetPath)
                                        val mimeType = when {
                                            assetPath.endsWith(".css") -> "text/css"
                                            assetPath.endsWith(".js") -> "text/javascript"
                                            else -> "text/html" // default
                                        }
                                        WebResourceResponse(mimeType, "UTF-8", inputStream)
                                    } catch (e: IOException) {
                                        Log.e("WebView", "Error loading asset $assetPath", e)
                                        null
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        loadDataWithBaseURL("file:///android_asset/", leafletMapHtml, "text/html", "UTF-8", null)
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL("file:///android_asset/", leafletMapHtml, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Location coordinates display just above the map
        currentLocation?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 180.dp) // Adjusted for smaller map
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
                        text = String.format("%.6f°, %.6f°", it.latitude, it.longitude),
                        color = Color.White,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = String.format("Alt: %.1f m", it.altitude),
                            color = Color.White,
                            style = TextStyle(fontSize = 12.sp)
                        )

                        Text(
                            text = String.format("Speed: %.1f km/h", currentSpeed),
                            color = Color.White,
                            style = TextStyle(fontSize = 12.sp)
                        )
                    }
                }
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