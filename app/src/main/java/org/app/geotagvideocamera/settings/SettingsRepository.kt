package org.app.geotagvideocamera.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("geotag.settings")

class SettingsRepository(private val context: Context) {

    val settingsFlow: Flow<SettingsState> =
        context.settingsDataStore.data.map { p ->
            SettingsState(
                // Appearance
                dynamicColors = p[booleanPreferencesKey("dynamicColors")] ?: true,
                themeMode = p[intPreferencesKey("themeMode")] ?: 0,
                compactUi = p[booleanPreferencesKey("compactUi")] ?: false,

                // Overlay
                showMap = p[booleanPreferencesKey("showMap")] ?: true,
                showCoordinates = p[booleanPreferencesKey("showCoordinates")] ?: false,
                showAddress = p[booleanPreferencesKey("showAddress")] ?: true,
                showSpeed = p[booleanPreferencesKey("showSpeed")] ?: false,
                showGpsStatus = p[booleanPreferencesKey("showGpsStatus")] ?: false,
                unitsIndex = p[intPreferencesKey("unitsIndex")] ?: 0,
                mapZoom = p[floatPreferencesKey("mapZoom")] ?: 15f,
                showTopBar = p[booleanPreferencesKey("showTopBar")] ?: false,
                addressPositionIndex = p[intPreferencesKey("addressPositionIndex")] ?: 2,
                showLocationTextWithoutMap = p[booleanPreferencesKey("showLocationTextWithoutMap")] ?: true,

                // Map
                mapProviderIndex = p[intPreferencesKey("mapProviderIndex")] ?: 0,
                styleUrl = p[stringPreferencesKey("styleUrl")] ?: "",
                maptilerApiKey = p[stringPreferencesKey("maptilerApiKey")] ?: "",
                geoapifyApiKey = p[stringPreferencesKey("geoapifyApiKey")] ?: "",

                // Camera
                hideModeButton = p[booleanPreferencesKey("hideModeButton")] ?: true,
                cameraFacing = p[intPreferencesKey("cameraFacing")] ?: 0,

                // System
                debugLocation = p[booleanPreferencesKey("debugLocation")] ?: false,
                demoNoticeShown = p[booleanPreferencesKey("demoNoticeShown")] ?: false
            )
        }

    suspend fun <T> update(spec: SettingSpec<T>, value: T) {
        context.settingsDataStore.edit { prefs ->
            when (spec) {
                is ToggleSpec -> prefs[booleanPreferencesKey(spec.id)] = (value as Boolean)
                is DropdownSpec -> prefs[intPreferencesKey(spec.id)] = (value as Int)
                is SliderSpec -> prefs[floatPreferencesKey(spec.id)] = alignToStep((value as Float), spec)
                is TextSpec -> prefs[stringPreferencesKey(spec.id)] = (value as String)
            }
        }
    }

    // Simple internal helper to set a boolean flag by id (for non-UI/system keys).
    suspend fun setFlag(id: String, value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(id)] = value
        }
    }

    private fun alignToStep(v: Float, spec: SliderSpec): Float {
        val clamped = v.coerceIn(spec.min, spec.max)
        val steps = ((clamped - spec.min) / spec.step).toInt()
        return spec.min + steps * spec.step
    }
}