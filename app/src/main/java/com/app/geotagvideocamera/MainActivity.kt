package com.app.geotagvideocamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError


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

    @Deprecated("This method has been deprecated in favor of using the Activity Result API")
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
                onEnableDebugLocation = ::enableDebugTestLocation,
                onDisableDebugLocation = ::disableDebugTestLocation
            )
        }
    }

    fun enableDebugTestLocation() {
        Toast.makeText(this, "Debug location enabled - Golden Gate Bridge", Toast.LENGTH_SHORT).show()

        // Create a test location
        val testLocation = Location("debug_provider").apply {
            latitude = 37.8199  // Golden Gate Bridge
            longitude = -122.4783
            altitude = 75.0
            speed = 5.0f  // ~18 km/h
            accuracy = 3.0f
            time = System.currentTimeMillis()
        }

        // Send it to the current location listener
        debugLocationListener?.onLocationChanged(testLocation)
    }

    fun disableDebugTestLocation() {
        Toast.makeText(this, "Debug location disabled - using real GPS", Toast.LENGTH_SHORT).show()
        // The app will continue using real GPS updates
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

// Map utilities
private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    // Simple distance calculation
    val latDiff = abs(lat1 - lat2) * 111000 // approx meters per degree of latitude
    val lonDiff = abs(lon1 - lon2) * 111000 * cos(Math.toRadians(lat1))
    return sqrt(latDiff * latDiff + lonDiff * lonDiff)
}

fun getLeafletMapHtml(lat: Double, lon: Double, zoom: Int = 15): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <style>
                body, html { margin: 0; padding: 0; height: 100%; width: 100%; overflow: hidden; }
                #map { width: 100%; height: 100%; background: #e0e0e0; }
                #debug { position: absolute; bottom: 0; left: 0; background: rgba(0,0,0,0.7); color: white; padding: 4px; 
                          font-size: 10px; z-index: 1000; max-width: 100%; overflow: hidden; }
            </style>
        </head>
        <body>
            <div id="debug">Loading map...</div>
            <div id="map"></div>
            
            <script>
                // Log function to display messages in the debug div
                function log(msg) {
                    document.getElementById('debug').innerHTML += "<br>" + msg;
                    console.log(msg);
                }
                
                log("Script started");
                
                // Define the map variables globally
                var map, osmLayer;
                var mapZoom = $zoom;
                var mapLat = $lat;
                var mapLon = $lon;
                
                // Load Leaflet dynamically
                function loadScript(url, callback) {
                    var script = document.createElement('script');
                    script.type = 'text/javascript';
                    script.src = url;
                    script.onload = function() {
                        log("Loaded script: " + url);
                        callback();
                    };
                    script.onerror = function() {
                        log("Error loading script: " + url);
                    };
                    document.head.appendChild(script);
                }
                
                function loadCSS(url) {
                    var link = document.createElement('link');
                    link.rel = 'stylesheet';
                    link.href = url;
                    link.onload = function() {
                        log("Loaded CSS: " + url);
                    };
                    link.onerror = function() {
                        log("Error loading CSS: " + url);
                    };
                    document.head.appendChild(link);
                }
                
                // First load CSS
                loadCSS('https://unpkg.com/leaflet@1.9.4/dist/leaflet.css');
                
                // Then load JS
                loadScript('https://unpkg.com/leaflet@1.9.4/dist/leaflet.js', function() {
                    log("Initializing map");
                    
                    try {
                        // Initialize map
                        map = L.map('map', {
                            zoomControl: false,
                            attributionControl: false
                        }).setView([mapLat, mapLon], mapZoom);
                        
                        log("Map object created");
                        
                        // Log an example tile URL
                        var exampleUrl = 'https://a.tile.openstreetmap.org/' + mapZoom + '/0/0.png';
                        log("Example tile URL: " + exampleUrl);
                        
                        // Add OpenStreetMap tile layer
                        osmLayer = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                            maxZoom: 19,
                            subdomains: ['a', 'b', 'c']
                        }).addTo(map);
                        
                        log("Tile layer added");
                        
                        // Add a marker
                        L.marker([mapLat, mapLon]).addTo(map);
                        
                        log("Marker added");
                        
                        // Force resize
                        setTimeout(function() {
                            map.invalidateSize(true);
                            log("Map resized");
                            
                            // Check if tiles are loading
                            var tileCount = document.querySelectorAll('.leaflet-tile').length;
                            log("Tile elements found: " + tileCount);
                            
                            // Try to fetch a test tile
                            var testImg = new Image();
                            testImg.onload = function() { log("Test tile loaded successfully"); };
                            testImg.onerror = function() { log("Test tile failed to load"); };
                            testImg.src = exampleUrl;
                        }, 500);
                    } catch (e) {
                        log("Error initializing map: " + e.message);
                        log("Stack: " + e.stack);
                    }
                });
            </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
fun VideoRecorderApp(
    locationManager: LocationManager,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onRecordButtonClick: () -> Unit,
    onEnableDebugLocation: () -> Unit,
    onDisableDebugLocation: () -> Unit
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

// Dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }

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

        // Save the listener for debug purposes
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
                            databaseEnabled = true
                            loadsImagesAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT  // Changed to use cache
                            setGeolocationEnabled(true)

                            // Add these settings to ensure proper rendering
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            defaultTextEncodingName = "utf-8"

                            // Allow network loading
                            blockNetworkImage = false
                            blockNetworkLoads = false
                        }

                        // Add a WebChromeClient to handle console messages for debugging
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                Log.d("WebView", "${message.message()} -- From line ${message.lineNumber()} of ${message.sourceId()}")
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                Log.d("WebView", "Page started loading: $url")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.d("WebView", "Page finished loading: $url")
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    Log.e("WebView", "Error loading ${request?.url}: ${error?.errorCode} ${error?.description}")
                                } else {
                                    Log.e("WebView", "Error loading resource")
                                }
                            }
                        }

                        // Set a background color
                        setBackgroundColor(android.graphics.Color.rgb(224, 224, 224))

                        // Load the map data
                        loadDataWithBaseURL(
                            "https://openstreetmap.org/",
                            leafletMapHtml,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(
                        "https://openstreetmap.org/",
                        leafletMapHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
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

        // Settings dialog
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
@Preview(showBackground = true)
fun VideoRecorderAppPreview() {
    // Simple map preview that doesn't depend on context or system services
    MapOverlayPreview()
}

@Composable
fun MapOverlayPreview() {
    // Mock location data for San Francisco
    val mockLat = 37.7749
    val mockLon = -122.4194
    val mockAlt = 12.5
    val mockSpeed = 18.7f // km/h

    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray)) {
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

        // Map overlay at bottom center
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

        // Location coordinates display just above the map
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