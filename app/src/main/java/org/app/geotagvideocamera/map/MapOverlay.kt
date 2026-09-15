package org.app.geotagvideocamera.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.app.geotagvideocamera.settings.SettingsState
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
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

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(latitude = initialLat, longitude = initialLon),
            zoom = settings.mapZoom.toDouble()
        )
    )

    var recenterSeq by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lat, lon, settings.mapZoom) {
        if (lat != null && lon != null) {
            val mySeq = ++recenterSeq
            delay(350)
            if (mySeq != recenterSeq) return@LaunchedEffect
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
        options = MapOptions(
            renderOptions = RenderOptions(
                preferredRenderMode = RenderOptions.RenderMode.Texture
            )
        ),
        modifier = modifier
    )
}
