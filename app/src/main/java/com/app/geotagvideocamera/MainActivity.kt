package com.app.geotagvideocamera

import android.Manifest
import android.app.AlertDialog
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : ComponentActivity() {
    private lateinit var locationManager: LocationManager
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var screenDensity = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var videoCapture: VideoCapture<Recorder>? = null
    var debugLocationListener: LocationListener? = null

    private lateinit var cameraExecutor: ExecutorService
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private val REQUEST_CODE_SCREEN_CAPTURE = 1001
    val recordingModeState = mutableStateOf(false)

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

        // Hide system UI (status bar and navigation bar)
        hideSystemUI()

        // Executor initialization
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize location services
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Get screen metrics for recording
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Initialize MediaProjectionManager for screen recording
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Check if this is the first run to prompt for API key
        if (isFirstRun(this)) {
            showFirstRunApiKeyDialog()
        } else {
            requestPermissionsAndStart()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopScreenRecording()
    }

    fun prepareForScreenRecording() {
        // Signal to the UI to hide controls
        recordingModeState.value = true

        // Give UI time to update before starting recording
        Handler(Looper.getMainLooper()).postDelayed({
            startScreenRecording()
        }, 300) // Short delay to ensure UI updates
    }

    private fun startScreenRecording() {
        if (mediaProjectionManager == null) {
            mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        }

        val captureIntent = mediaProjectionManager!!.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE)

        Toast.makeText(
            this,
            "Recording mode active. Long-press anywhere to stop recording.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                // Got permission to record the screen
                mediaRecorder = MediaUtils.createMediaRecorder(this, screenWidth, screenHeight)

                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                createVirtualDisplay()

                mediaRecorder?.start()
                isRecording = true

                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
            } else {
                // User canceled, restore UI
                recordingModeState.value = false
            }
        }
    }

    private fun createVirtualDisplay() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface, null, null
        )
    }

    fun stopScreenRecording() {
        if (!isRecording) return

        // First restore the UI
        recordingModeState.value = false

        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }

            virtualDisplay?.release()
            mediaProjection?.stop()

            mediaRecorder = null
            virtualDisplay = null
            mediaProjection = null

            isRecording = false

            Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping recording", e)
            Toast.makeText(this, "Error saving recording", Toast.LENGTH_SHORT).show()
        }
    }


    private fun hideSystemUI() {
        // Keep screen on during recording
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // We need to postpone the UI hiding until the window is attached
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // For Android 11+ (API 30+)
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                // For older Android versions
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                        )
            }

            // Let the layout extend into the cutout area if device has one
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            // Make sure we use edge-to-edge
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    private fun showFirstRunApiKeyDialog() {
        val builder = AlertDialog.Builder(this)
        val input = EditText(this)
        input.hint = "Enter Mapbox API key"
        input.inputType = InputType.TYPE_CLASS_TEXT

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(50, 30, 50, 10)
        container.addView(input)

        builder.setView(container)
        builder.setTitle("API Key Required")
        builder.setMessage("This app requires a Mapbox API key for maps functionality. You can get a free key from mapbox.com")

        builder.setPositiveButton("Save") { dialog, _ ->
            val apiKey = input.text.toString()
            if (apiKey.isNotEmpty()) {
                storeApiKey(this, "mapbox_key", apiKey)
                Toast.makeText(this, "API key saved. Thank you!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No API key provided. Maps will be limited.", Toast.LENGTH_LONG).show()
            }
            requestPermissionsAndStart()
        }

        builder.setNegativeButton("Skip") { dialog, _ ->
            Toast.makeText(this, "No API key provided. Maps will be limited.", Toast.LENGTH_LONG).show()
            requestPermissionsAndStart()
        }

        builder.setCancelable(false) // Prevent dismissing by tapping outside
        builder.show()
    }

    private fun requestPermissionsAndStart() {
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

        requestPermissions.launch(requiredPermissions.toTypedArray())
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


    // API Key Management Functions
    companion object {
        // Store API key in SharedPreferences
        fun storeApiKey(context: Context, keyType: String, apiKey: String) {
            val prefs = context.getSharedPreferences("geotag_prefs", MODE_PRIVATE)
            prefs.edit { putString(keyType, apiKey) }
        }

        // Retrieve API key from SharedPreferences
        fun getStoredApiKey(context: Context, keyType: String): String? {
            val prefs = context.getSharedPreferences("geotag_prefs", MODE_PRIVATE)
            return prefs.getString(keyType, null)
        }

        // Save map zoom level
        fun saveMapZoom(context: Context, zoomLevel: Int) {
            val prefs = context.getSharedPreferences("geotag_prefs", MODE_PRIVATE)
            prefs.edit { putInt("map_zoom", zoomLevel) }
        }

        // Get map zoom level with default of 14
        fun getMapZoom(context: Context): Int {
            val prefs = context.getSharedPreferences("geotag_prefs", MODE_PRIVATE)
            return prefs.getInt("map_zoom", 14)
        }

        // Check if this is first run
        fun isFirstRun(context: Context): Boolean {
            val prefs = context.getSharedPreferences("geotag_prefs", MODE_PRIVATE)
            val isFirstRun = prefs.getBoolean("first_run", true)
            if (isFirstRun) {
                prefs.edit { putBoolean("first_run", false) }
            }
            return isFirstRun
        }

        // Show Mapbox API Key Dialog
        fun showMapboxApiKeyDialog(context: Context) {
            val builder = AlertDialog.Builder(context)
            val input = EditText(context)
            val currentKey = getStoredApiKey(context, "mapbox_key") ?: ""
            input.setText(currentKey)
            input.hint = "Enter Mapbox API key"
            input.inputType = InputType.TYPE_CLASS_TEXT

            val container = LinearLayout(context)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding(50, 30, 50, 10)
            container.addView(input)

            builder.setView(container)
            builder.setTitle("Mapbox API Key")
            builder.setMessage("Enter your Mapbox API key (get one from mapbox.com)")

            builder.setPositiveButton("Save") { dialog, _ ->
                val apiKey = input.text.toString()
                if (apiKey.isNotEmpty()) {
                    storeApiKey(context, "mapbox_key", apiKey)
                    Toast.makeText(context, "Mapbox API key saved", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }

            builder.setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }

            builder.show()
        }

        // Show Geoapify API Key Dialog
        fun showGeoapifyApiKeyDialog(context: Context) {
            val builder = AlertDialog.Builder(context)
            val input = EditText(context)
            val currentKey = getStoredApiKey(context, "geoapify_key") ?: ""
            input.setText(currentKey)
            input.hint = "Enter Geoapify API key"
            input.inputType = InputType.TYPE_CLASS_TEXT

            val container = LinearLayout(context)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding(50, 30, 50, 10)
            container.addView(input)

            builder.setView(container)
            builder.setTitle("Geoapify API Key")
            builder.setMessage("Enter your Geoapify API key (get one from geoapify.com)")

            builder.setPositiveButton("Save") { dialog, _ ->
                val apiKey = input.text.toString()
                if (apiKey.isNotEmpty()) {
                    storeApiKey(context, "geoapify_key", apiKey)
                    Toast.makeText(context, "Geoapify API key saved", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }

            builder.setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }

            builder.show()
        }

        fun saveInAppRecordingSettings(context: Context, useInAppRecording: Boolean) {
            val prefs = context.getSharedPreferences("geotag_prefs", MODE_PRIVATE)
            prefs.edit {
                putBoolean("use_in_app_recording", useInAppRecording)
            }
        }

        fun getInAppRecordingSettings(context: Context): Boolean {
            val prefs = context.getSharedPreferences("geotag_prefs", MODE_PRIVATE)
            return prefs.getBoolean("use_in_app_recording", false)
        }

        fun showInAppRecordingWarningDialog(context: Context, onConfirm: () -> Unit) {
            AlertDialog.Builder(context)
                .setTitle("Performance Warning")
                .setMessage("In-app video recording with overlay may affect performance on some devices, especially during longer recordings. The system screen recorder is recommended for better performance.\n\nDo you want to continue?")
                .setPositiveButton("Enable Anyway") { _, _ ->
                    onConfirm()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}

// Location utilities
private fun useGooglePlayServicesLocation(context: Context, locationListener: LocationListener) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    val locationRequest = LocationRequest.create().apply {
        interval = 1000 // Update interval in milliseconds
        fastestInterval = 500
        priority = Priority.PRIORITY_HIGH_ACCURACY
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

@Composable
fun VideoRecorderApp(
    locationManager: LocationManager,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onEnableDebugLocation: () -> Unit,
    onDisableDebugLocation: () -> Unit
) {
    val context = LocalContext.current
    val mainActivity = context as MainActivity

    // Location state
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }
    var gpsStatus by remember { mutableStateOf("Searching...") }
//    var address by remember { mutableStateOf("Loading address...") }
//    var mapBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Camera state
    var cameraMode by remember { mutableStateOf(CameraMode.VIDEO) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    // UI state
    val isRecordingMode = mainActivity.recordingModeState.value
    var showSettingsDialog by remember { mutableStateOf(false) }
    var useInAppRecording by remember { mutableStateOf(MainActivity.getInAppRecordingSettings(context)) }

    // Map zoom state
    var mapZoom by remember { mutableIntStateOf(MainActivity.getMapZoom(context)) }

    // Settings for UI
    val uiSettings = MainActivity.getUiVisibilitySettings(context)
    var showTopBar by remember { mutableStateOf(uiSettings["show_top_bar"] as Boolean) }
    var showMap by remember { mutableStateOf(uiSettings["show_map"] as Boolean) }
    var showCoordinates by remember { mutableStateOf(uiSettings["show_coordinates"] as Boolean) }
    var showAddress by remember { mutableStateOf(uiSettings["show_address"] as Boolean) }
    var addressPosition by remember { mutableStateOf(uiSettings["address_position"] as String) }

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
            } catch (_: Exception) {
                // On any error, try Google Play Services as fallback
                useGooglePlayServicesLocation(context, locationListener)
                gpsStatus = "Location Error, Using Google Play Services"
//                println(e)
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
                        if (!isRecordingMode) {
                            showSettingsDialog = true
                        }
                    },
                    onLongPress = {
                        // Long press to stop recording
                        if (isRecordingMode) {
                            mainActivity.stopScreenRecording()
                        }
                    }
                )
            }
    ) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            cameraMode = cameraMode,
            onVideoCaptureReady = {
                videoCapture = it
                onVideoCaptureReady(it)
            },
            onImageCaptureReady = { imageCapture = it }
        )

        if (showTopBar) {
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
        }

        var address by remember { mutableStateOf("Loading address...") }
        var mapBitmap by remember { mutableStateOf<Bitmap?>(null) }

// Map overlay - always show if enabled, even during recording
        if (showMap) {
            // If address should be shown above the map
            if (showAddress && addressPosition == "above_map" && currentLocation != null) {
                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 300.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(6.dp),
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
            }

            // The map box itself
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(280.dp)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                    .background(Color(0xFFE8E8E8))
            ) {
                val location = currentLocation

                if (location != null) {
                    // Get address from location with postal code
                    LaunchedEffect(location) {
                        try {
                            withContext(Dispatchers.IO) {
                                val geocoder = android.location.Geocoder(context, Locale.getDefault())

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    // Use the new API for Android 13+
                                    geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                                        if (addresses.isNotEmpty()) {
                                            val firstAddress = addresses[0]
                                            // Include postal code and all available address components
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
                                    // Use the deprecated API for older Android versions
                                    @Suppress("DEPRECATION")
                                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                                    if (!addresses.isNullOrEmpty()) {
                                        val firstAddress = addresses[0]
                                        // Include postal code and all available address components
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

                    // Load map image using coroutines
                    LaunchedEffect(location, mapZoom) {
                        try {
                            // Try Mapbox first
                            val mapboxKey = MainActivity.getStoredApiKey(context, "mapbox_key")
                            if (!mapboxKey.isNullOrEmpty()) {
                                withContext(Dispatchers.IO) {
                                    val mapboxUrl = "https://api.mapbox.com/styles/v1/mapbox/streets-v11/static/" +
                                            "pin-s+ff0000(${location.longitude},${location.latitude})/" +
                                            "${location.longitude},${location.latitude},${mapZoom},0/" +
                                            "240x180@2x" +
                                            "?access_token=$mapboxKey"

                                    Log.d("MapDebug", "Loading Mapbox map with zoom level $mapZoom")

                                    try {
                                        val url = URL(mapboxUrl)
                                        val connection = url.openConnection() as HttpURLConnection
                                        connection.doInput = true
                                        connection.connect()
                                        val input = connection.inputStream
                                        mapBitmap = BitmapFactory.decodeStream(input)

                                        if (mapBitmap != null) {
                                            Log.d("MapDebug", "Successfully loaded Mapbox map")
                                            return@withContext // Successfully loaded Mapbox map, exit early
                                        } else {
                                            Log.e("MapDebug", "Failed to decode Mapbox map bitmap")
                                            throw Exception("Failed to decode Mapbox map")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MapDebug", "Error loading Mapbox map: ${e.message}", e)
                                        throw Exception("Failed to load Mapbox map: ${e.message}")
                                    }
                                }
                            } else {
                                Log.d("MapDebug", "No Mapbox API key provided, trying Geoapify")
                                throw Exception("No Mapbox API key provided")
                            }
                        } catch (_: Exception) {
                            // If Mapbox fails, try Geoapify
                            try {
                                val geoapifyKey = MainActivity.getStoredApiKey(context, "geoapify_key")
                                if (!geoapifyKey.isNullOrEmpty()) {
                                    withContext(Dispatchers.IO) {
                                        val geoapifyUrl = "https://maps.geoapify.com/v1/staticmap" +
                                                "?style=osm-carto" +
                                                "&width=240&height=180" +
                                                "&center=lonlat:${location.longitude},${location.latitude}" +
                                                "&zoom=${mapZoom}" +
                                                "&marker=lonlat:${location.longitude},${location.latitude};color:%23ff0000;size:medium" +
                                                "&apiKey=$geoapifyKey"
//TODO: Be able to set map size and padding in settings?
                                        Log.d("MapDebug", "Trying Geoapify map with zoom level $mapZoom")

                                        val url = URL(geoapifyUrl)
                                        val connection = url.openConnection() as HttpURLConnection
                                        connection.doInput = true
                                        connection.connect()
                                        val input = connection.inputStream
                                        mapBitmap = BitmapFactory.decodeStream(input)

                                        if (mapBitmap != null) {
                                            Log.d("MapDebug", "Successfully loaded Geoapify map")
                                        } else {
                                            Log.e("MapDebug", "Failed to decode Geoapify map bitmap")
                                        }
                                    }
                                } else {
                                    Log.e("MapDebug", "No API keys provided for map services")
                                }
                            } catch (e: Exception) {
                                Log.e("MapDebug", "All map loading attempts failed: ${e.message}", e)
                            }
                        }
                    }

                    // Display map image if loaded
                    if (mapBitmap != null) {
                        Image(
                            bitmap = mapBitmap!!.asImageBitmap(),
                            contentDescription = "Map of current location",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Show loading indicator
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Loading map...\nDouble-tap to set API key",
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Only show address inside the map if that position is selected
                    if (showAddress && (addressPosition == "top" || addressPosition == "bottom")) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(if (addressPosition == "top") Alignment.TopCenter else Alignment.BottomCenter)
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
                    }

                    // Only show coordinates if enabled
                    if (showCoordinates) {
                        // Position coordinates at bottom if address is not there, otherwise position them appropriately
                        val coordinatesAlignment = when {
                            !showAddress -> Alignment.BottomCenter
                            addressPosition == "bottom" -> Alignment.BottomCenter
                            else -> Alignment.BottomCenter
                        }

                        Box(
                            modifier = Modifier
                                .align(coordinatesAlignment)
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
                    }
                } else {
                    // Show waiting for location
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Waiting for location...\nDouble-tap for settings",
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Camera controls - only show when not recording
        if (!isRecordingMode) {
            // Mode toggle - top right
            CameraModeToggle(
                currentMode = cameraMode,
                onModeChanged = { newMode ->
                    cameraMode = newMode
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )

            // Photo or video button - bottom center
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            ) {
                if (cameraMode == CameraMode.PHOTO) {
                    // Photo capture button
                    IconButton(
                        onClick = {
                            imageCapture?.let { capture ->
                                MediaUtils.capturePhoto(
                                    context = context,
                                    imageCapture = capture,
                                    location = currentLocation,
                                    onPhotoSaved = { uri ->
                                        Toast.makeText(context, "Photo saved with location data", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { error ->
                                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = "Take Photo",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else if (useInAppRecording) {
                    // Video recording button - only if in-app recording is enabled
                    IconButton(
                        onClick = {
                            mainActivity.prepareForScreenRecording()
                        },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = "Start Recording",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else {
                    // Instruction for system recorder
//                    Box(
//                        modifier = Modifier
//                            .clip(RoundedCornerShape(16.dp))
//                            .background(Color.Black.copy(alpha = 0.7f))
//                            .padding(8.dp)
//                    ) {
//                        Text(
//                            text = "Use system screen recorder",
//                            color = Color.White,
//                            fontSize = 12.sp
//                        )
//                    }
                }
            }
        }

        // Minimal recording indicator
        if (isRecordingMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(8.dp)
                    .background(Color.Red, CircleShape)
            )
        }

        // Settings dialog
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = {
                    showSettingsDialog = false
                },
                title = {
                    Text("Settings")
                },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Set a maximum height to ensure scrolling
                            .heightIn(max = 400.dp)
                    ) {
                        item {
                            // Location Settings
                            Text("Location Settings", fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp))

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

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        item {
                            // Map Zoom Settings
                            Text("Map Zoom Level: $mapZoom", fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp))

                            // Add zoom slider (1-19 range for zoom levels)
                            Slider(
                                value = mapZoom.toFloat(),
                                onValueChange = {
                                    mapZoom = it.toInt()
                                },
                                onValueChangeFinished = {
                                    // Save zoom level when user finishes adjusting
                                    MainActivity.saveMapZoom(context, mapZoom)
                                },
                                valueRange = 1f..19f,
                                steps = 17, // 19 possible values (1-19), so 18 steps between them
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Far", fontSize = 12.sp, color = Color.Gray)
                                Text("Close", fontSize = 12.sp, color = Color.Gray)
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        item {
                            // UI Visibility Settings
                            Text("UI Visibility", fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Show Top Status Bar")
                                androidx.compose.material3.Switch(
                                    checked = showTopBar,
                                    onCheckedChange = {
                                        showTopBar = it
                                        MainActivity.saveUiVisibilitySettings(
                                            context, showTopBar, showMap, showCoordinates,
                                            showAddress, addressPosition
                                        )
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Show Map")
                                androidx.compose.material3.Switch(
                                    checked = showMap,
                                    onCheckedChange = {
                                        showMap = it
                                        MainActivity.saveUiVisibilitySettings(
                                            context, showTopBar, showMap, showCoordinates,
                                            showAddress, addressPosition
                                        )
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Show Coordinates")
                                androidx.compose.material3.Switch(
                                    checked = showCoordinates,
                                    onCheckedChange = {
                                        showCoordinates = it
                                        MainActivity.saveUiVisibilitySettings(
                                            context, showTopBar, showMap, showCoordinates,
                                            showAddress, addressPosition
                                        )
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Show Address")
                                androidx.compose.material3.Switch(
                                    checked = showAddress,
                                    onCheckedChange = {
                                        showAddress = it
                                        MainActivity.saveUiVisibilitySettings(
                                            context, showTopBar, showMap, showCoordinates,
                                            showAddress, addressPosition
                                        )
                                    }
                                )
                            }
                        }

                        item {
                            Text("Address Position",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(
                                    onClick = {
                                        addressPosition = "top"
                                        MainActivity.saveUiVisibilitySettings(
                                            context, showTopBar, showMap, showCoordinates,
                                            showAddress, addressPosition
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = if (addressPosition == "top")
                                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                                        else Color.Gray
                                    )
                                ) {
                                    Text("Inside Top")
                                }

                                TextButton(
                                    onClick = {
                                        addressPosition = "bottom"
                                        MainActivity.saveUiVisibilitySettings(
                                            context, showTopBar, showMap, showCoordinates,
                                            showAddress, addressPosition
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = if (addressPosition == "bottom")
                                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                                        else Color.Gray
                                    )
                                ) {
                                    Text("Inside Bottom")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(
                                    onClick = {
                                        addressPosition = "above_map"
                                        MainActivity.saveUiVisibilitySettings(
                                            context, showTopBar, showMap, showCoordinates,
                                            showAddress, addressPosition
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = if (addressPosition == "above_map")
                                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                                        else Color.Gray
                                    )
                                ) {
                                    Text("Above Map")
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        item {
                            // Recording Options
                            Text("Recording Options", fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp, top = 8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Use In-App Video Recording")
                                    Text(
                                        "May affect performance",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }

                                androidx.compose.material3.Switch(
                                    checked = useInAppRecording,
                                    onCheckedChange = { newValue ->
                                        if (newValue) {
                                            // Show warning dialog first
                                            showSettingsDialog = false // Hide settings temporarily
                                            MainActivity.showInAppRecordingWarningDialog(context) {
                                                useInAppRecording = true
                                                MainActivity.saveInAppRecordingSettings(context, true)
                                                showSettingsDialog = true // Show settings again
                                            }
                                        } else {
                                            useInAppRecording = false
                                            MainActivity.saveInAppRecordingSettings(context, false)
                                        }
                                    }
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        item {
                            Text("API Keys", fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp))

                            TextButton(
                                onClick = {
                                    showSettingsDialog = false
                                    MainActivity.showMapboxApiKeyDialog(context)
                                }
                            ) {
                                Text("Set Mapbox API Key")
                            }

                            TextButton(
                                onClick = {
                                    showSettingsDialog = false
                                    MainActivity.showGeoapifyApiKeyDialog(context)
                                }
                            ) {
                                Text("Set Geoapify API Key")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showSettingsDialog = false }
                    ) {
                        Text("Close")
                    }
                }
            )
        }

    }
}



fun MainActivity.Companion.saveUiVisibilitySettings(context: Context, showTopBar: Boolean, showMap: Boolean,
                             showCoordinates: Boolean, showAddress: Boolean, addressPosition: String) {
    val prefs = context.getSharedPreferences("geotag_prefs", Context.MODE_PRIVATE)
    prefs.edit {
        putBoolean("show_top_bar", showTopBar)
        putBoolean("show_map", showMap)
        putBoolean("show_coordinates", showCoordinates)
        putBoolean("show_address", showAddress)
        putString("address_position", addressPosition)
    }
}

fun MainActivity.Companion.getUiVisibilitySettings(context: Context): Map<String, Any> {
    val prefs = context.getSharedPreferences("geotag_prefs", Context.MODE_PRIVATE)
    return mapOf(
        "show_top_bar" to prefs.getBoolean("show_top_bar", true),
        "show_map" to prefs.getBoolean("show_map", true),
        "show_coordinates" to prefs.getBoolean("show_coordinates", true),
        "show_address" to prefs.getBoolean("show_address", true),
        "address_position" to prefs.getString("address_position", "top")!!
    )
}


@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraMode: CameraMode = CameraMode.VIDEO,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onImageCaptureReady: (ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(previewView, cameraMode) {
        val cameraProvider = context.getCameraProvider()
        val preview = androidx.camera.core.Preview.Builder().build()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        preview.surfaceProvider = previewView.surfaceProvider

        try {
            cameraProvider.unbindAll()

            if (cameraMode == CameraMode.VIDEO) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )

                onVideoCaptureReady(videoCapture)
            } else {
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                onImageCaptureReady(imageCapture)
            }
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

//TODO: choose styles based on api? Not needed