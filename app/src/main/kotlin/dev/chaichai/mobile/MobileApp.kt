package dev.chaichai.mobile

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.design.system.LocalReducedMotion
import dev.chaichai.mobile.feature.home.HomeScreen
import dev.chaichai.mobile.feature.libraries.LibrariesScreen
import dev.chaichai.mobile.feature.search.SearchScreen
import dev.chaichai.mobile.feature.settings.SettingsScreen
import dev.chaichai.mobile.platform.adaptive.AdaptiveNavigationPolicy
import dev.chaichai.mobile.platform.adaptive.NavigationPlacement
import dev.chaichai.mobile.platform.adaptive.WindowCharacteristics
import kotlin.math.roundToInt

private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Default.Home),
    Libraries("libraries", "Libraries", Icons.AutoMirrored.Filled.List),
    Search("search", "Search", Icons.Default.Search),
    Settings("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun MobileApp(
    boundaries: AppBoundaries,
    hasSeparatingVerticalHinge: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeDrawing = WindowInsets.safeDrawing
        val horizontalInsetsDp = with(density) {
            (safeDrawing.getLeft(density, layoutDirection) +
                safeDrawing.getRight(density, layoutDirection)).toDp().value
        }
        val verticalInsetsDp = with(density) {
            (safeDrawing.getTop(density) + safeDrawing.getBottom(density)).toDp().value
        }
        val layout = AdaptiveNavigationPolicy.layout(
            WindowCharacteristics(
                usableWidthDp = (maxWidth.value - horizontalInsetsDp).roundToInt(),
                usableHeightDp = (maxHeight.value - verticalInsetsDp).roundToInt(),
                hasSeparatingVerticalHinge = hasSeparatingVerticalHinge,
            ),
        )
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val snackbarHostState = remember { SnackbarHostState() }
        val reducedMotion = LocalReducedMotion.current
        @Suppress("UNUSED_VARIABLE")
        val acceptanceBoundaries = boundaries

        val navigate: (TopLevelDestination) -> Unit = { destination ->
            navController.navigate(destination.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        val content: @Composable (Modifier) -> Unit = { contentModifier ->
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.Home.route,
                modifier = contentModifier,
                enterTransition = { if (reducedMotion) EnterTransition.None else fadeIn() },
                exitTransition = { if (reducedMotion) ExitTransition.None else fadeOut() },
                popEnterTransition = { if (reducedMotion) EnterTransition.None else fadeIn() },
                popExitTransition = { if (reducedMotion) ExitTransition.None else fadeOut() },
            ) {
                composable(TopLevelDestination.Home.route) {
                    HomeScreen(isHeightConstrained = layout.isHeightConstrained)
                }
                composable(TopLevelDestination.Libraries.route) { LibrariesScreen() }
                composable(TopLevelDestination.Search.route) { SearchScreen() }
                composable(TopLevelDestination.Settings.route) { SettingsScreen() }
            }
        }

        if (layout.navigationPlacement == NavigationPlacement.Rail) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                NavigationRail {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigate(destination) },
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
                content(Modifier.weight(1f))
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigate(destination) },
                                icon = { Icon(destination.icon, contentDescription = destination.label) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
        }
    }
}
