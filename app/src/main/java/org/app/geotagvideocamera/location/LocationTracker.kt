package org.app.geotagvideocamera.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

data class LocationUi(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val address: String?
)

class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    private val _state = MutableStateFlow<LocationUi?>(null)
    val state: StateFlow<LocationUi?> = _state

    @Volatile
    private var _lastRawLocation: Location? = null
    val lastRawLocation: Location? get() = _lastRawLocation

    private var geocoder: Geocoder? = null
    private var started = false
    private var lastGeocodeTimeMs = 0L
    private var lastGeocodeLocation: Location? = null
    private var geocodeSeq = 0L

    private fun shouldGeocode(location: Location): Boolean {
        val now = System.currentTimeMillis()
        val last = lastGeocodeLocation
        val movedEnough = last == null || last.distanceTo(location) >= 50f
        val oldEnough = now - lastGeocodeTimeMs >= 15_000L
        return movedEnough || oldEnough
    }

    private suspend fun geocode(lat: Double, lon: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val g = geocoder ?: Geocoder(appContext, Locale.getDefault()).also { geocoder = it }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                g.getFromLocation(lat, lon, 1) { addresses ->
                    cont.resume(addresses.firstOrNull())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            g.getFromLocation(lat, lon, 1)?.firstOrNull()
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            _lastRawLocation = l
            val ui = LocationUi(
                latitude = l.latitude,
                longitude = l.longitude,
                accuracyMeters = l.accuracy,
                speedMps = if (l.hasSpeed()) l.speed else null,
                address = null
            )
            _state.value = ui

            if (!shouldGeocode(l)) return

            lastGeocodeTimeMs = System.currentTimeMillis()
            lastGeocodeLocation = Location(l)
            val requestSeq = ++geocodeSeq
            val lat = l.latitude
            val lon = l.longitude

            scope.launch(Dispatchers.IO) {
                val line = geocode(lat, lon)?.getAddressLine(0)
                if (line != null && requestSeq == geocodeSeq) {
                    val current = _state.value
                    if (
                        current != null &&
                        current.latitude == lat &&
                        current.longitude == lon
                    ) {
                        _state.value = current.copy(address = line)
                    }
                }
            }
        }
    }

    fun pushDebugLocation(lat: Double, lon: Double) {
        val mockLocation = Location("debug").apply {
            this.latitude = lat
            this.longitude = lon
            accuracy = 5f
        }
        _lastRawLocation = mockLocation
        val ui = LocationUi(
            latitude = lat,
            longitude = lon,
            accuracyMeters = 5f,
            speedMps = null,
            address = null
        )
        _state.value = ui

        scope.launch(Dispatchers.IO) {
            val line = geocode(lat, lon)?.getAddressLine(0)
            _state.value = _state.value?.copy(
                address = line ?: "Golden Gate Bridge, San Francisco, CA"
            )
        }
    }


    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(600L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    fun stop() {
        if (!started) return
        started = false
        client.removeLocationUpdates(callback)
    }

    fun close() {
        stop()
        job.cancel()
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