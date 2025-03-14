package com.app.geotagvideocamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
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

@Composable
fun VideoRecorderApp(locationManager: LocationManager) {
    val context = LocalContext.current
    //val lifecycleOwner = LocalLifecycleOwner.current

    var recording by remember { mutableStateOf<Recording?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // Location state
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }
    var gpsStatus by remember { mutableStateOf("Searching...") }

    // UI state
    var showMap by remember { mutableStateOf(false) }
    var mapOpacity by remember { mutableFloatStateOf(0.5f) }

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
            onVideoCaptureReady = { videoCapture = it }
        )

        // OpenStreetMap overlay (conditionally shown)
        AnimatedVisibility(
            visible = showMap,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.TopEnd)
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
                    var isMapLoaded by remember { mutableStateOf(false) }

                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT

                                    webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isMapLoaded = true
                                    }
                                        // ADD THIS ERROR HANDLER:
                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                            super.onReceivedError(view, request, error)
                                            view?.loadData("<html><body><h3>Map loading failed</h3><p>Check your internet connection</p></body></html>", "text/html", "UTF-8")
                                        }
                                }

                                val lat = currentLocation?.latitude ?: 0.0
                                val lon = currentLocation?.longitude ?: 0.0

                                if (isNetworkAvailable(ctx)) {
                                    loadUrl(
                                        "https://www.openstreetmap.org/export/embed.html" +
                                                "?bbox=${lon - 0.005},${lat - 0.005}," +
                                                "${lon + 0.005},${lat + 0.005}" +
                                                "&layer=mapnik&marker=${lat},${lon}"
                                    )
                                } else {
                                    loadData(
                                        "<html><body><h3>No internet connection</h3></body></html>",
                                        "text/html",
                                        "UTF-8"
                                    )
                                }
                            }
                        },
                    update = { webView ->
                        val lat = currentLocation?.latitude ?: 0.0
                        val lon = currentLocation?.longitude ?: 0.0

                            webView.loadUrl(
                                "https://www.openstreetmap.org/export/embed.html" +
                                        "?bbox=${lon - 0.005},${lat - 0.005}," +
                                        "${lon + 0.005},${lat + 0.005}" +
                                        "&layer=mapnik&marker=${lat},${lon}"
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    LaunchedEffect(currentLocation) {
                        while (true) {
                            delay(3000)
                        }
                    }

                    // Show loading indicator
                    if (!isMapLoaded) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.Blue)
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

                // Map opacity control
                if (showMap) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        Text(
                            "Map Opacity",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = mapOpacity,
                            onValueChange = { mapOpacity = it },
                            valueRange = 0.2f..0.8f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Geotag overlay with improved UI
        EnhancedGeotagOverlay(
            location = currentLocation,
            speed = currentSpeed,
            time = currentTime,
            gpsStatus = gpsStatus,
            isRecording = recording != null,
            modifier = Modifier.fillMaxSize()
        )

        // Control buttons
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            // Map toggle button
            IconButton(
                onClick = {
                    showMap = !showMap
                    if (showMap) {
                        Toast.makeText(context, "Map overlay enabled", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.DarkGray.copy(alpha = 0.7f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = "Toggle Map",
                    tint = if (showMap) Color.Green else Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Record button
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.DarkGray.copy(alpha = 0.7f), CircleShape)
                    .border(2.dp, if (recording != null) Color.Red else Color.White, CircleShape)
                    .clickable {
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
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (recording != null) Color.Red else Color.White,
                            if (recording != null) RoundedCornerShape(8.dp) else CircleShape
                        )
                )
            }

            // Settings button (placeholder for future functionality)
            IconButton(
                onClick = {
                    Toast.makeText(context, "Settings (not implemented)", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.DarkGray.copy(alpha = 0.7f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
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
        // Top info panel with modern design
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
        ) {
            // Time and GPS status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = time,
                    color = Color.White,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(
                            color = when {
                                gpsStatus.contains("Fixed") -> Color.Green
                                gpsStatus.contains("Active") -> Color(0xFFFFAA00) // Orange
                                else -> Color.Red
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = gpsStatus,
                        color = Color.White,
                        style = TextStyle(fontSize = 14.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Location data
            location?.let {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(12.dp)
                ) {
                    // Coordinates
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LocationDataItem(
                            label = "Latitude",
                            value = String.format("%.6f°", it.latitude)
                        )

                        LocationDataItem(
                            label = "Longitude",
                            value = String.format("%.6f°", it.longitude)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Altitude and speed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LocationDataItem(
                            label = "Altitude",
                            value = String.format("%.1f m", it.altitude)
                        )

                        LocationDataItem(
                            label = "Speed",
                            value = String.format("%.1f km/h", speed)
                        )
                    }
                }
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
fun LocationDataItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            color = Color.LightGray,
            style = TextStyle(fontSize = 12.sp)
        )

        Text(
            text = value,
            color = Color.White,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
        )
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







//Looking at the error message "ERR_NAME_NOT_RESOLVED" in the map overlay, this is a DNS resolution problem that typically occurs when a web request can't find the server address.
//
//The issue is likely happening because the WebView in your app is trying to load the OpenStreetMap URL, but the device doesn't have proper internet connectivity when using the WebView component specifically.
//
//Here's what might be causing the issue:
//
//1. **Missing Internet Permission**: While you do have the internet permission in your AndroidManifest.xml, make sure it's properly applied.
//
//2. **Network Connectivity**: The app might be running when the device has limited or no internet connectivity.
//
//3. **WebView Configuration**: The WebView might need additional configuration to handle network connections properly.
//
//To fix this issue, try these solutions:
//
//```kt
//// In your WebView configuration, add these additional settings
//webView.apply {
//    settings.javaScriptEnabled = true
//    settings.domStorageEnabled = true  // Enable DOM storage
//    settings.cacheMode = WebSettings.LOAD_DEFAULT  // Use cache if possible
//
//    // Add a WebViewClient that handles errors
//    webViewClient = object : WebViewClient() {
//        override fun onPageFinished(view: WebView?, url: String?) {
//            super.onPageFinished(view, url)
//            isMapLoaded = true
//        }
//
//        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
//            super.onReceivedError(view, request, error)
//            // Show error message in the WebView
//            view?.loadData("<html><body><h3>Map loading failed</h3><p>Check your internet connection</p></body></html>", "text/html", "UTF-8")
//        }
//    }
//}
//```
//
//Also, consider implementing a network connectivity check before attempting to load the map:
//
//```kt
//// Add this function to check for internet connectivity
//private fun isNetworkAvailable(context: Context): Boolean {
//    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//        val network = connectivityManager.activeNetwork ?: return false
//        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
//        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//    } else {
//        val networkInfo = connectivityManager.activeNetworkInfo
//        return networkInfo != null && networkInfo.isConnected
//    }
//}
//
//// Then use it before loading the map
//if (isNetworkAvailable(context)) {
//    webView.loadUrl("https://www.openstreetmap.org/export/embed.html?bbox=...")
//} else {
//    webView.loadData("<html><body><h3>No internet connection</h3></body></html>", "text/html", "UTF-8")
//}
//```
//
//Would you like me to explain how these changes work?