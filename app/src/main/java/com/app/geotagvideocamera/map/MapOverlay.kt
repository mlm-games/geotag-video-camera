package com.app.geotagvideocamera.map

import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.geotagvideocamera.settings.SettingsState
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

@Composable
fun MapOverlay(
    settings: SettingsState,
    lat: Double?,   // pass from caller
    lon: Double?,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var styleReady by remember { mutableStateOf(false) }

    // Create MapView once
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
        // Advance to current state (because observer won't replay)
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
            // best-effort symmetrical shutdown
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Load style only when provider/URL changes
    val styleUrl = remember(settings.mapProviderIndex, settings.styleUrl, settings.maptilerApiKey, settings.geoapifyApiKey) {
        resolveStyleUrl(settings)
    }
    LaunchedEffect(styleUrl) {



        styleReady = false
        mapView.getMapAsync { map ->
            map.setStyle(styleUrl) {
                styleReady = true
                // set initial camera when style is ready
                val target = if (lat != null && lon != null) LatLng(lat, lon) else map.cameraPosition.target ?: LatLng(0.0, 0.0)
                map.cameraPosition = CameraPosition.Builder()
                    .target(target)
                    .zoom(settings.mapZoom.toDouble())
                    .build()
            }
        }
    }

    // Update camera when location or zoom changes (after style is ready)
    LaunchedEffect(lat, lon, settings.mapZoom, styleReady) {
        if (!styleReady) return@LaunchedEffect
        if (lat != null && lon != null) {
            mapView.getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(settings.mapZoom.toDouble())
                    .build()
            }
        } else {
            // still apply zoom to whatever the current target is
            mapView.getMapAsync { map ->
                val currentTarget = map.cameraPosition.target ?: LatLng(0.0, 0.0)
                map.cameraPosition = CameraPosition.Builder()
                    .target(currentTarget)
                    .zoom(settings.mapZoom.toDouble())
                    .build()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView }
    )
}