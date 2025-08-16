package com.app.geotagvideocamera.settings

import androidx.annotation.StringRes
import com.app.geotagvideocamera.R

enum class SettingCategory(@param:StringRes val titleRes: Int) {
    APPEARANCE(R.string.cat_appearance),
    OVERLAY(R.string.cat_overlay),
    MAP(R.string.cat_map),
    CAMERA(R.string.cat_camera),
    SYSTEM(R.string.cat_system)
}

sealed interface SettingSpec<T> {
    val id: String
    val category: SettingCategory
    @get:StringRes val titleRes: Int
}

data class ToggleSpec(
    override val id: String,
    override val category: SettingCategory,
    @param:StringRes override val titleRes: Int,
    val default: Boolean = false,
    val enabledIf: (SettingsState) -> Boolean = { true }
) : SettingSpec<Boolean>

data class DropdownSpec(
    override val id: String,
    override val category: SettingCategory,
    @param:StringRes override val titleRes: Int,
    val entries: List<Int>,
    val defaultIndex: Int = 0,
    val enabledIf: (SettingsState) -> Boolean = { true }
) : SettingSpec<Int>

data class SliderSpec(
    override val id: String,
    override val category: SettingCategory,
    @param:StringRes override val titleRes: Int,
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float,
    val enabledIf: (SettingsState) -> Boolean = { true }
) : SettingSpec<Float>

data class TextSpec(
    override val id: String,
    override val category: SettingCategory,
    @param:StringRes override val titleRes: Int,
    val default: String = "",
    val enabledIf: (SettingsState) -> Boolean = { true }
) : SettingSpec<String>

data class SettingsState(
    // Appearance
    val dynamicColors: Boolean = true,
    val themeMode: Int = 0, // 0 system, 1 light, 2 dark
    val compactUi: Boolean = false,

    // Overlay
    val showMap: Boolean = true,
    val showCoordinates: Boolean = false,
    val showAddress: Boolean = true,
    val showSpeed: Boolean = false,
    val showGpsStatus: Boolean = false,
    val unitsIndex: Int = 0, // 0 metric, 1 imperial
    val mapZoom: Float = 15f,
    val showTopBar: Boolean = false,
    val addressPositionIndex: Int = 0,

    // Map
    val mapProviderIndex: Int = 0, // 0 MapLibre, 1 MapTiler, 2 Geoapify
    val styleUrl: String = "",     // custom style for MapLibre or advanced users (just
    val maptilerApiKey: String = "",
    val geoapifyApiKey: String = "",

    // Camera
    val hideModeButton: Boolean = true,

    // System
    val debugLocation: Boolean = false
)

val SettingsSpecs: List<SettingSpec<*>> = listOf(
    // Appearance
    ToggleSpec("dynamicColors", SettingCategory.APPEARANCE, R.string.use_dynamic_colors, default = true),
    DropdownSpec(
        id = "themeMode",
        category = SettingCategory.APPEARANCE,
        titleRes = R.string.theme,
        entries = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark),
        defaultIndex = 0
    ),
    ToggleSpec("compactUi", SettingCategory.APPEARANCE, R.string.compact_ui, default = false),

    // Overlay
    ToggleSpec("showMap", SettingCategory.OVERLAY, R.string.show_map, default = true),
    ToggleSpec("showCoordinates", SettingCategory.OVERLAY, R.string.show_coordinates, default = false),
    ToggleSpec("showAddress", SettingCategory.OVERLAY, R.string.show_address, default = true),
    ToggleSpec("showSpeed", SettingCategory.OVERLAY, R.string.show_speed, default = false),
    ToggleSpec("showGpsStatus", SettingCategory.OVERLAY, R.string.show_gps_status, default = false),
    DropdownSpec("unitsIndex", SettingCategory.OVERLAY, R.string.units, entries = listOf(R.string.units_metric, R.string.units_imperial), defaultIndex = 0),
    SliderSpec("mapZoom", SettingCategory.OVERLAY, R.string.map_zoom, min = 4f, max = 20f, step = 1f, default = 15f),
    ToggleSpec("showTopBar", SettingCategory.OVERLAY, R.string.show_top_bar, default = false),
    DropdownSpec(
        id = "addressPositionIndex",
        category = SettingCategory.OVERLAY,
        titleRes = R.string.address_position,
        entries = listOf(
            R.string.address_inside_top,
            R.string.address_inside_bottom,
            R.string.address_above_map
        ),
        defaultIndex = 0
    ),

    // Map (provider and keys)
    DropdownSpec(
        id = "mapProviderIndex",
        category = SettingCategory.MAP,
        titleRes = R.string.map_provider,
        entries = listOf(R.string.provider_maplibre, R.string.provider_maptiler, R.string.provider_geoapify),
        defaultIndex = 0
    ),
    TextSpec("styleUrl", SettingCategory.MAP, R.string.style_url),
    TextSpec("maptilerApiKey", SettingCategory.MAP, R.string.maptiler_api_key),
    TextSpec("geoapifyApiKey", SettingCategory.MAP, R.string.geoapify_api_key),

    // Camera
    ToggleSpec("hideModeButton", SettingCategory.CAMERA, R.string.hide_mode_button, default = true),

    // System
    ToggleSpec("debugLocation", SettingCategory.SYSTEM, R.string.debug_location, default = false)
)

fun SettingsState.isEnabled(spec: SettingSpec<*>): Boolean = when (spec) {
    is ToggleSpec -> spec.enabledIf(this)
    is DropdownSpec -> spec.enabledIf(this)
    is SliderSpec -> spec.enabledIf(this)
    is TextSpec -> spec.enabledIf(this)
}