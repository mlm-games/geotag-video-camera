package com.app.geotagvideocamera

import android.Manifest
import android.view.GestureDetector
import android.view.MotionEvent

import android.app.Activity
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
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
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

            // Fall back to regular recording
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
    // Only update if moved more than 10 meters or 5 seconds passed
    val distanceMoved = calculateDistance(lastMapUpdateLat, lastMapUpdateLon, lat, lon)
    val timePassed = now - lastMapUpdateTime

    return if (distanceMoved > 10 || timePassed > 5000) {
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

private fun getErrorHtml(errorMsg: String): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <style>
                body { font-family: Arial, sans-serif; text-align: center; padding: 20px; 
                       background: #f0f0f0; color: #333; }
                .error { color: #d32f2f; font-weight: bold; margin: 20px 0; }
                .details { font-size: 14px; margin-bottom: 20px; }
            </style>
        </head>
        <body>
            <div class="error">Map loading failed</div>
            <div class="details">$errorMsg</div>
            <div>Check your internet connection and try again</div>
        </body>
        </html>
    """.trimIndent()
}

private fun getMapHtml(lat: Double, lon: Double, zoom: Int = 15): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <style>
                body, html { margin: 0; padding: 0; height: 100%; overflow: hidden; }
                #map { width: 100%; height: 100%; background: #f0f0f0; }
                .marker { position: absolute; top: 50%; left: 50%; width: 20px; height: 20px; 
                          margin-left: -10px; margin-top: -20px; color: red; font-size: 24px; }
            </style>
        </head>
        <body>
            <div id="map">
                <img src="https://maps.geoapify.com/v1/staticmap?style=osm-carto&width=400&height=400&center=lonlat:$lon,$lat&zoom=$zoom&marker=lonlat:$lon,$lat;color:%23ff0000;size:medium" 
                     width="100%" height="100%" alt="Map" 
                     onerror="this.onerror=null;this.src='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';document.body.innerHTML += '<div style=\'position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;\'><strong>Map loading failed</strong><br>Check internet connection</div>'"/>
            </div>
        </body>
        </html>
    """.trimIndent()
}

// Composable function for the main UI
@Composable
fun VideoRecorderApp(
    locationManager: LocationManager,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onRecordButtonClick: () -> Unit,
    onDoubleTapSettings: () -> Unit
) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf<Recording?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // Location state
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }
    var gpsStatus by remember { mutableStateOf("Searching...") }

    // UI state
    var showMap by remember { mutableStateOf(true) }
    var mapOpacity by remember { mutableFloatStateOf(0.5f) }
    var isMapLoaded by remember { mutableStateOf(false) }
    var mapLoadError by remember { mutableStateOf<String?>(null) }

    // WebView State
    val webView = remember { mutableStateOf<WebView?>(null) }

    // Update time every second
    LaunchedEffect(Unit) {
        while(true) {
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
            // Cancel the timeout job if it's still active
            locationJob?.cancel()
            // Also clean up Google Play Services location client if used
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onVideoCaptureReady = onVideoCaptureReady,
            onDoubleTap = onDoubleTapSettings

        )

        AnimatedVisibility(
            visible = showMap,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .width(360.dp)
                .height(80.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = mapOpacity))
            ) {
                if (currentLocation != null) {
                    // Show map with location
                    val lat = currentLocation?.latitude ?: 0.0
                    val lon = currentLocation?.longitude ?: 0.0
                    val shouldUpdate = shouldUpdateMap(lat, lon)

                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webView.value = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isMapLoaded = true
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        val errorMessage = error?.description?.toString() ?: "Unknown error"
                                        mapLoadError = "Failed to load map: $errorMessage"
                                        val errorHtml = getErrorHtml(errorMessage)
                                        view?.loadDataWithBaseURL(
                                            null,
                                            errorHtml,
                                            "text/html",
                                            "UTF-8",
                                            null
                                        )
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: SslErrorHandler?,
                                        error: SslError?
                                    ) {
                                        super.onReceivedSslError(view, handler, error)
                                        val errorMessage = error?.toString() ?: "SSL Error"
                                        mapLoadError = "SSL Error: $errorMessage"
                                        val errorHtml = getErrorHtml(errorMessage)
                                        view?.loadDataWithBaseURL(
                                            null,
                                            errorHtml,
                                            "text/html",
                                            "UTF-8",
                                            null
                                        )
                                    }
                                }

                                loadDataWithBaseURL(
                                    null,
                                    getMapHtml(lat, lon),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        },
                        update = { webView ->
                            if (shouldUpdate && currentLocation != null) {
                                val lat = currentLocation?.latitude ?: 0.0
                                val lon = currentLocation?.longitude ?: 0.0

                                webView.loadDataWithBaseURL(
                                    null,
                                    getMapHtml(lat, lon),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                                isMapLoaded = false
                                mapLoadError = null
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Show loading indicator
                    if (!isMapLoaded && mapLoadError == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.Blue)
                        }
                    } else if (mapLoadError != null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Map loading failed: $mapLoadError",
                                color = Color.Red,
                                style = TextStyle(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                } else {
                    // Show "Waiting for location" message
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Waiting for GPS location...",
                                color = Color.Black,
                                style = TextStyle(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            CircularProgressIndicator(color = Color.Blue)
                        }
                    }
                }

//                 Map opacity control
//                if (showMap) {
//                    Row(
//                        modifier = Modifier
//                            .align(Alignment.BottomCenter)
//                            .fillMaxWidth()
//                            .background(Color.Black.copy(alpha = 0.3f))
//                            .padding(4.dp),
//                        verticalAlignment = Alignment.CenterVertically
//                    ) {
//                        Text(
//                            "Opacity",
//                            color = Color.White,
//                            fontSize = 10.sp,
//                            modifier = Modifier.padding(horizontal = 4.dp)
//                        )
//                        Slider(
//                            value = mapOpacity,
//                            onValueChange = { mapOpacity = it },
//                            valueRange = 0.2f..0.8f,
//                            modifier = Modifier.weight(1f)
//                        )
//                    }
//                }
            }
        }

//        EnhancedGeotagOverlay(
//            location = currentLocation,
//            speed = currentSpeed,
//            time = currentTime,
//            gpsStatus = gpsStatus,
//            isRecording = recording != null,
//            modifier = Modifier.fillMaxSize()
//        )

        // Control buttons
//        Row(
//            horizontalArrangement = Arrangement.SpaceEvenly,
//            modifier = Modifier
//                .fillMaxWidth()
//                .align(Alignment.BottomCenter)
//                .padding(bottom = 32.dp)
//        ) {
//            // Map toggle button
//            IconButton(
//                onClick = {
//                    showMap = !showMap
//                    if (showMap) {
//                        Toast.makeText(context, "Map overlay enabled", Toast.LENGTH_SHORT).show()
//                    }
//                    isMapLoaded = false
//                    mapLoadError = null
//                },
//                modifier = Modifier
//                    .size(56.dp)
//                    .background(Color.DarkGray.copy(alpha = 0.7f), CircleShape)
//            ) {
//                Icon(
//                    imageVector = Icons.Default.Map,
//                    contentDescription = "Toggle Map",
//                    tint = if (showMap) Color.Green else Color.White,
//                    modifier = Modifier.size(32.dp)
//                )
//            }
//
//            // Record button
//            Box(
//                modifier = Modifier
//                    .size(72.dp)
//                    .background(Color.DarkGray.copy(alpha = 0.7f), CircleShape)
//                    .border(2.dp, if (recording != null) Color.Red else Color.White, CircleShape)
//                    .clickable(onClick = onRecordButtonClick),
//                contentAlignment = Alignment.Center
//            ) {
//                Box(
//                    modifier = Modifier
//                        .size(48.dp)
//                        .background(
//                            if (recording != null) Color.Red else Color.White,
//                            if (recording != null) RoundedCornerShape(8.dp) else CircleShape
//                        )
//                )
//            }
//
//            // Settings button (placeholder for future functionality)
//            IconButton(
//                onClick = {
//                    Toast.makeText(context, "Settings (not implemented)", Toast.LENGTH_SHORT).show()
//                },
//                modifier = Modifier
//                    .size(56.dp)
//                    .background(Color.DarkGray.copy(alpha = 0.7f), CircleShape)
//            ) {
//                Icon(
//                    imageVector = Icons.Default.Settings,
//                    contentDescription = "Settings",
//                    tint = Color.Transparent, // Color.White,
//                    modifier = Modifier.size(56.dp) //Modifier.size(32.dp)
//                )
//        }
    }
}

@Composable
fun EnhancedGeotagOverlay(
    location: Location?,
    speed: Float,
    time: String,
    gpsStatus: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
//         Simplified top info panel with clean design
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
        ) {
//             Time and GPS status in a single row
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
                    text = time,
                    color = Color.White,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
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

            Spacer(modifier = Modifier.height(4.dp))

            // Location data in a more compact format
            location?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format("%.6f°, %.6f°", it.latitude, it.longitude),
                        color = Color.White,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    )

                    Text(
                        text = String.format("%.1f m | %.1f km/h", it.altitude, speed),
                        color = Color.White,
                        style = TextStyle(fontSize = 14.sp)
                    )
                }
            }
        }

        // Minimal recording indicator
        if (isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(Color.Red)
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "REC",
                    color = Color.Red,
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp)
                )
            }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onDoubleTap: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    // Add double tap detector
    val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap()
            return true
        }
    })

    previewView.setOnTouchListener { _, event ->
        doubleTapDetector.onTouchEvent(event)
    }

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
