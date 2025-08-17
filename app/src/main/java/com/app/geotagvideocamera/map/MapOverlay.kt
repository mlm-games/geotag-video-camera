package com.app.geotagvideocamera.map

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.geotagvideocamera.settings.SettingsState
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

@Composable
fun MapOverlay(
    settings: SettingsState,
    lat: Double?,
    lon: Double?,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var styleReady by remember { mutableStateOf(false) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    val mapView = remember {
        MapView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onCreate(null)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val current = lifecycleOwner.lifecycle.currentState
        if (current.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (current.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val styleUrl = remember(
        settings.mapProviderIndex, settings.styleUrl, settings.maptilerApiKey, settings.geoapifyApiKey
    ) { resolveStyleUrl(settings) }

    // Helper: absolute recenter with non-null target (need to reset zoom option in settings
    fun recenter(map: MapLibreMap, lat: Double?, lon: Double?, zoom: Double) {
        val target: LatLng = when {
            lat != null && lon != null -> LatLng(lat, lon)
            map.cameraPosition.target != null -> map.cameraPosition.target!! // safe due to check
            else -> LatLng(0.0, 0.0)
        }
        map.moveCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(target, zoom)
        )
    }

    LaunchedEffect(styleUrl) {
        styleReady = false
        mapView.getMapAsync { map ->
            mapRef = map

            map.uiSettings.focalPoint = null

            map.setStyle(styleUrl) {
                styleReady = true
                recenter(map, lat, lon, settings.mapZoom.toDouble())
            }
        }
    }

    // Apply updates after style is ready
    LaunchedEffect(lat, lon, settings.mapZoom, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        recenter(map, lat, lon, settings.mapZoom.toDouble())
    }

    AndroidView(modifier = modifier, factory = { mapView })
}