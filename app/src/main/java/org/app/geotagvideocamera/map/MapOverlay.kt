package org.app.geotagvideocamera.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.app.geotagvideocamera.settings.SettingsState
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@Composable
fun MapOverlay(
    settings: SettingsState,
    lat: Double?,
    lon: Double?,
    modifier: Modifier = Modifier
) {
    val styleUrl = remember(
        settings.mapProviderIndex,
        settings.styleUrl,
        settings.maptilerApiKey,
        settings.geoapifyApiKey
    ) {
        resolveStyleUrl(settings)
    }

    val initialLat = lat ?: 0.0
    val initialLon = lon ?: 0.0

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(latitude = initialLat, longitude = initialLon),
            zoom = settings.mapZoom.toDouble()
        )
    )

    // Recenter when coordinates or zoom change
    LaunchedEffect(lat, lon, settings.mapZoom) {
        if (lat != null && lon != null) {
            cameraState.animateTo(
                cameraState.position.copy(
                    target = Position(latitude = lat, longitude = lon),
                    zoom = settings.mapZoom.toDouble()
                )
            )
        }
    }

    MaplibreMap(
        baseStyle = BaseStyle.Uri(styleUrl),
        cameraState = cameraState,
        modifier = modifier
    )
}