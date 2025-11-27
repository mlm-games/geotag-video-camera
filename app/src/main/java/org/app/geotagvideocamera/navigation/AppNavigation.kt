package org.app.geotagvideocamera.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.app.geotagvideocamera.camera.CameraAndOverlayScreen
import org.app.geotagvideocamera.settings.SettingsScreen
import org.app.geotagvideocamera.settings.SettingsViewModel
import kotlinx.serialization.Serializable

private const val ANIM_DURATION = 300
private const val SCALE_INITIAL = 0.96f

private val enterAnim = fadeIn(tween(ANIM_DURATION)) + scaleIn(
    initialScale = SCALE_INITIAL,
    animationSpec = tween(ANIM_DURATION)
)

private val exitAnim = fadeOut(tween(ANIM_DURATION)) + scaleOut(
    targetScale = SCALE_INITIAL,
    animationSpec = tween(ANIM_DURATION)
)

sealed interface Route {
    @Serializable
    data object Camera : Route

    @Serializable
    data object Settings : Route
}

@Composable
fun AppNavHost(
    settingsVm: SettingsViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: Route = Route.Camera
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { enterAnim },
        exitTransition = { exitAnim },
        popEnterTransition = { enterAnim },
        popExitTransition = { exitAnim }
    ) {
        composable<Route.Camera> {
            CameraAndOverlayScreen(
                settingsVm = settingsVm,
                onOpenSettings = { navController.navigate(Route.Settings) }
            )
        }

        composable<Route.Settings> {
            SettingsScreen(
                vm = settingsVm,
                onClose = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Navigation actions helper (components)
 */
class NavigationActions(private val navController: NavHostController) {
    fun navigateToSettings() {
        navController.navigate(Route.Settings) {
            launchSingleTop = true
        }
    }

    fun navigateBack() {
        navController.popBackStack()
    }

    fun navigateToCamera() {
        navController.navigate(Route.Camera) {
            popUpTo(Route.Camera) { inclusive = true }
        }
    }
}