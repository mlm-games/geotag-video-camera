package com.app.geotagvideocamera

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

        // Check if this is the first run to prompt for API key
        if (isFirstRun(this)) {
            showFirstRunApiKeyDialog()
        } else {
            requestPermissionsAndStart()
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
            val prefs = context.getSharedPreferences("geotag_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(keyType, apiKey).apply()
        }

        // Retrieve API key from SharedPreferences
        fun getStoredApiKey(context: Context, keyType: String): String? {
            val prefs = context.getSharedPreferences("geotag_prefs", Context.MODE_PRIVATE)
            return prefs.getString(keyType, null)
        }

        // Check if this is first run
        fun isFirstRun(context: Context): Boolean {
            val prefs = context.getSharedPreferences("geotag_prefs", Context.MODE_PRIVATE)
            val isFirstRun = prefs.getBoolean("first_run", true)
            if (isFirstRun) {
                prefs.edit().putBoolean("first_run", false).apply()
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

@Composable
fun VideoRecorderApp(
    locationManager: LocationManager,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit,
    onEnableDebugLocation: () -> Unit,
    onDisableDebugLocation: () -> Unit
) {
    val context = LocalContext.current

    // Location state
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }
    var gpsStatus by remember { mutableStateOf("Searching...") }

    // Dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Initialize Leaflet Map HTML

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
                .background(Color(0xFFE8E8E8))
        ) {
            val location = currentLocation

            if (location != null) {
                // Address state
                var address by remember { mutableStateOf("Loading address...") }
                var mapBitmap by remember { mutableStateOf<Bitmap?>(null) }

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
                LaunchedEffect(location) {
                    try {
                        // Try Mapbox first
                        val mapboxKey = MainActivity.getStoredApiKey(context, "mapbox_key")
                        if (!mapboxKey.isNullOrEmpty()) {
                            withContext(Dispatchers.IO) {
                                val mapboxUrl = "https://api.mapbox.com/styles/v1/mapbox/streets-v11/static/" +
                                        "pin-s+ff0000(${location.longitude},${location.latitude})/" +
                                        "${location.longitude},${location.latitude},14,0/" +
                                        "240x150@2x" +
                                        "?access_token=$mapboxKey"

                                Log.d("MapDebug", "Loading Mapbox map")

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
                    } catch (e: Exception) {
                        // If Mapbox fails, try Geoapify
                        try {
                            val geoapifyKey = MainActivity.getStoredApiKey(context, "geoapify_key")
                            if (!geoapifyKey.isNullOrEmpty()) {
                                withContext(Dispatchers.IO) {
                                    val geoapifyUrl = "https://maps.geoapify.com/v1/staticmap" +
                                            "?style=osm-carto" +
                                            "&width=240&height=150" +
                                            "&center=lonlat:${location.longitude},${location.latitude}" +
                                            "&zoom=14" +
                                            "&marker=lonlat:${location.longitude},${location.latitude};color:%23ff0000;size:medium" +
                                            "&apiKey=$geoapifyKey"

                                    Log.d("MapDebug", "Trying Geoapify map")

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
                    // Show loading indicator if map image is not yet loaded
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

                // Address display at top of map
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
                    Column {
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