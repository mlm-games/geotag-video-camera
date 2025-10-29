package org.app.geotagvideocamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.app.geotagvideocamera.settings.SettingsScreen
import org.app.geotagvideocamera.settings.SettingsViewModel
import org.app.geotagvideocamera.settings.SettingsViewModelFactory
import org.app.geotagvideocamera.ui.theme.GeotagVideoCameraTheme
import org.app.geotagvideocamera.camera.CameraAndOverlayScreen

class MainActivity : ComponentActivity() {

    private val settingsVm: SettingsViewModel by viewModels {
        SettingsViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        setContent {
            val appState by settingsVm.state.collectAsStateWithLifecycle()
            GeotagVideoCameraTheme(
                darkTheme = when (appState.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() },
                dynamicColor = appState.dynamicColors
            ) {
                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(vm = settingsVm, onClose = { showSettings = false })
                } else {
                    CameraAndOverlayScreen(settingsVm = settingsVm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    private fun hideSystemBars() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // Use the cutout area on devices with a notch
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

}