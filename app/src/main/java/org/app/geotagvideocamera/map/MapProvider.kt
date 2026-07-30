package org.app.geotagvideocamera.map

import android.content.Context
import android.net.Uri
import org.app.geotagvideocamera.settings.SettingsState

sealed class MapProvider {
    data object MapLibre : MapProvider()
    data object MapTiler : MapProvider()
    data object Geoapify : MapProvider()
}

fun providerFrom(index: Int): MapProvider = when (index) {
    0 -> MapProvider.MapLibre
    1 -> MapProvider.MapTiler
    else -> MapProvider.Geoapify
}

private const val FALLBACK_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val FALLBACK_DARK = "https://tiles.openfreemap.org/styles/dark"

private fun buildUrl(base: String, paramName: String, key: String): String =
    Uri.parse(base).buildUpon().appendQueryParameter(paramName, key).build().toString()

fun resolveStyleUrl(s: SettingsState, context: Context): String =
    resolveStyleUrl(s, s.isDarkTheme(context))

fun resolveStyleUrl(s: SettingsState, isDark: Boolean = false): String {
    val fallback = if (isDark) FALLBACK_DARK else FALLBACK_LIGHT
    return when (providerFrom(s.mapProviderIndex)) {
        MapProvider.MapLibre -> {
            s.styleUrl.ifBlank { fallback }
        }
        MapProvider.MapTiler -> {
            val key = s.maptilerApiKey
            if (key.isBlank()) fallback
            else buildUrl("https://api.maptiler.com/maps/streets/style.json", "key", key)
        }
        MapProvider.Geoapify -> {
            val key = s.geoapifyApiKey
            if (key.isBlank()) fallback
            else buildUrl("https://maps.geoapify.com/v1/styles/osm-carto/style.json", "apiKey", key)
        }
    }
}