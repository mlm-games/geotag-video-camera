package org.app.geotagvideocamera.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.app.geotagvideocamera.camera.CameraAndOverlayScreen
import org.app.geotagvideocamera.settings.SettingsScreen
import org.app.geotagvideocamera.settings.SettingsViewModel

sealed interface Route : NavKey {
    @Serializable
    data object Camera : Route

    @Serializable
    data object Settings : Route
}

@Composable
fun AppNavHost(
    settingsVm: SettingsViewModel,
    modifier: Modifier = Modifier,
    startDestination: Route = Route.Camera
) {
    val backStack = rememberNavBackStack(startDestination)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        // handy for viewModel() (components)
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        // onBack is optional, pops last item by default
        entryProvider = entryProvider {
            entry<Route.Camera> {
                CameraAndOverlayScreen(
                    settingsVm = settingsVm,
                    onOpenSettings = { backStack.add(Route.Settings) }
                )
            }
            entry<Route.Settings> {
                SettingsScreen(
                    vm = settingsVm,
                    onClose = { backStack.removeLastOrNull() }
                )
            }
        }
    )
}