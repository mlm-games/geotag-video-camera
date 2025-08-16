package com.app.geotagvideocamera.map

import com.app.geotagvideocamera.settings.SettingsState

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

/**
 * Build a style URL for the selected provider.
 * - MapLibre: use user styleUrl if provided; otherwise a safe demo style.
 * - MapTiler/Geoapify: require API keys and use a standard streets/carto style.
 */
fun resolveStyleUrl(s: SettingsState): String {
    return when (providerFrom(s.mapProviderIndex)) {
        MapProvider.MapLibre -> {
            s.styleUrl.ifBlank { "https://demotiles.maplibre.org/style.json" }
        }
        MapProvider.MapTiler -> {
            val key = s.maptilerApiKey
            if (key.isBlank()) "https://demotiles.maplibre.org/style.json"
            else "https://api.maptiler.com/maps/streets/style.json?key=$key"
        }
        MapProvider.Geoapify -> {
            val key = s.geoapifyApiKey
            if (key.isBlank()) "https://demotiles.maplibre.org/style.json"
            else "https://maps.geoapify.com/v1/styles/osm-carto/style.json?apiKey=$key"
        }
    }
}