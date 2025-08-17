package com.app.geotagvideocamera.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class LocationUi(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val address: String?
)

class LocationTracker(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _state = MutableStateFlow<LocationUi?>(null)
    val state: StateFlow<LocationUi?> = _state

    private var geocoder: Geocoder? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            val ui = LocationUi(
                latitude = l.latitude,
                longitude = l.longitude,
                accuracyMeters = l.accuracy,
                speedMps = if (l.hasSpeed()) l.speed else null,
                address = null // filled async below
            )
            _state.value = ui
            // resolve address asynchronously to avoid jank
            scope.launch(Dispatchers.IO) {
                try {
                    if (geocoder == null) geocoder = Geocoder(context, Locale.getDefault())
                    val g = geocoder ?: return@launch
                    val addresses = g.getFromLocation(l.latitude, l.longitude, 1)
                    val line = addresses?.firstOrNull()?.getAddressLine(0)
                    if (line != null) {
                        _state.value = _state.value?.copy(address = line)
                    }
                } catch (_: Throwable) {
                    // ignore geocoder failures (no network etc.)
                }
            }
        }
    }

    fun pushDebugLocation(lat: Double, lon: Double) {
        val ui = LocationUi(
            latitude = lat,
            longitude = lon,
            accuracyMeters = 5f,
            speedMps = null,
            address = null
        )
        _state.value = ui

        scope.launch(Dispatchers.IO) {
            try {
                if (geocoder == null) geocoder = Geocoder(context, Locale.getDefault())
                val g = geocoder ?: return@launch
                val addresses = g.getFromLocation(lat, lon, 1)
                val line = addresses?.firstOrNull()?.getAddressLine(0)
                if (line != null) {
                    _state.value = _state.value?.copy(address = line)
                } else {
                    // fallback label if geocoder returns nothing
                    _state.value = _state.value?.copy(address = "Golden Gate Bridge, San Francisco, CA")
                }
            } catch (_: Throwable) {
                _state.value = _state.value?.copy(address = "Golden Gate Bridge, San Francisco, CA")
            }
        }
    }


    @SuppressLint("MissingPermission")
    fun start() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(600L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }
}

/* Formatting helpers */
fun formatLatLon(lat: Double, lon: Double): String =
    String.format(Locale.getDefault(), "%.5f, %.5f", lat, lon)

fun formatSpeed(mps: Float, unitsIndex: Int): String {
    return if (unitsIndex == 0) {
        // metric
        val kmh = mps * 3.6f
        String.format(Locale.getDefault(), "%.1f km/h", kmh)
    } else {
        val mph = mps * 2.2369363f
        String.format(Locale.getDefault(), "%.1f mph", mph)
    }
}