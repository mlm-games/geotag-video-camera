package org.app.geotagvideocamera.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.app.geotagvideocamera.settings.SettingsState
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.map.renderMode
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@Composable
fun MapOverlay(
    settings: SettingsState,
    lat: Double?,
    lon: Double?,
    modifier: Modifier = Modifier
) {
    val isDark = settings.isDarkTheme(isSystemInDarkTheme())
    val styleUrl = remember(
        settings.mapProviderIndex,
        settings.styleUrl,
        settings.maptilerApiKey,
        settings.geoapifyApiKey,
        isDark
    ) {
        resolveStyleUrl(settings, isDark)
    }

    val initialLat = lat ?: 0.0
    val initialLon = lon ?: 0.0

    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(styleUrl),
        initialCameraPosition = CameraPosition(
            target = Position(latitude = initialLat, longitude = initialLon),
            zoom = settings.mapZoom.toDouble()
        )
    )

    // Texture view so Compose clipping/alpha apply over the camera preview,
    // equivalent to the pre-0.16 RenderOptions.RenderMode.Texture setting.
    val uiOptions = remember {
        MapUiOptions {
            renderMode = AndroidRenderMode.Texture
        }
    }

    // Recenter when coordinates or zoom change
    LaunchedEffect(lat, lon, settings.mapZoom) {
        if (lat != null && lon != null) {
            mapState.animateCameraPosition(
                mapState.cameraPosition.copy(
                    target = Position(latitude = lat, longitude = lon),
                    zoom = settings.mapZoom.toDouble()
                )
            )
        }
    }

    MaplibreMap(
        state = mapState,
        uiOptions = uiOptions,
        modifier = modifier
    )
}
