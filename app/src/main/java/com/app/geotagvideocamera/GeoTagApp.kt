package com.app.geotagvideocamera

import android.app.Application
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class GeotagApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Default to the MapLibre well-known server. Style URLs loaded should still be full HTTP URLs and work fine.
        MapLibre.getInstance(this, /* apiKey = */ "", WellKnownTileServer.MapLibre)
    }
}