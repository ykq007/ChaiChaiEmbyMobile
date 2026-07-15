package dev.chaichai.mobile

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.design.system.LocalReducedMotion
import dev.chaichai.mobile.feature.home.HomeScreen
import dev.chaichai.mobile.feature.libraries.LibrariesScreen
import dev.chaichai.mobile.feature.search.SearchScreen
import dev.chaichai.mobile.feature.settings.SettingsScreen
import dev.chaichai.mobile.platform.adaptive.AdaptiveNavigationPolicy
import dev.chaichai.mobile.platform.adaptive.NavigationPlacement
import dev.chaichai.mobile.platform.adaptive.WindowCharacteristics
import kotlin.math.roundToInt

enum class HingeOrientation { Vertical, Horizontal }

data class SeparatingHinge(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
    val orientation: HingeOrientation,
)

private enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Default.Home),
    Libraries("libraries", "Libraries", Icons.AutoMirrored.Filled.List),
    Search("search", "Search", Icons.Default.Search),
    Settings("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun MobileApp(
    boundaries: AppBoundaries,
    separatingHinge: SeparatingHinge?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val navController = rememberNavController()
        val density = LocalDensity.current
        when (separatingHinge?.orientation) {
            HingeOrientation.Vertical -> {
                val leftWidth = with(density) { separatingHinge.leftPx.toDp() }
                val rightWidth = maxWidth - with(density) { separatingHinge.rightPx.toDp() }
                val useLeft = leftWidth >= rightWidth
                Box(
                    modifier = Modifier
                        .width(if (useLeft) leftWidth else rightWidth)
                        .fillMaxHeight()
                        .align(if (useLeft) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight),
                ) {
                    AdaptiveShell(boundaries, navController, isHingeSeparated = true)
                }
            }

            HingeOrientation.Horizontal -> {
                val topHeight = with(density) { separatingHinge.topPx.toDp() }
                val bottomHeight = maxHeight - with(density) { separatingHinge.bottomPx.toDp() }
                val useTop = topHeight >= bottomHeight
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (useTop) topHeight else bottomHeight)
                        .align(if (useTop) Alignment.TopCenter else Alignment.BottomCenter),
                ) {
                    AdaptiveShell(boundaries, navController, isHingeSeparated = true)
                }
            }

            null -> AdaptiveShell(boundaries, navController, isHingeSeparated = false)
        }
    }
}

@Composable
private fun AdaptiveShell(
    boundaries: AppBoundaries,
    navController: NavHostController,
    isHingeSeparated: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
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
                hasSeparatingVerticalHinge = isHingeSeparated,
            ),
        )
        val gatewayState by boundaries.gateway.connectionState.collectAsState()
        val isPlaying by boundaries.playback.isPlaying.collectAsState()
        val isOnline by boundaries.connectivity.isOnline.collectAsState()
        val checkedAt = remember(boundaries.clock) { boundaries.clock.now() }
        val status = listOf(
            if (isOnline) "Online" else "Offline",
            if (gatewayState == GatewayConnectionState.Connected) "Connected" else "No server",
            if (isPlaying) "Playback active" else "Playback idle",
            "Checked ${checkedAt.toString().substring(11, 16)} UTC",
        ).joinToString(" • ")

        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val reducedMotion = LocalReducedMotion.current
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
                    HomeScreen(isHeightConstrained = layout.isHeightConstrained, status = status)
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
                        NavigationRailItem(
                            selected = currentDestination.isSelected(destination),
                            onClick = { navigate(destination) },
                            icon = { DestinationIcon(destination) },
                            label = { Text(destination.label) },
                        )
                    }
                }
                content(Modifier.weight(1f))
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        TopLevelDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = currentDestination.isSelected(destination),
                                onClick = { navigate(destination) },
                                icon = { DestinationIcon(destination) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
        }
    }
}

private fun NavDestination?.isSelected(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.route == destination.route } == true

@Composable
private fun DestinationIcon(destination: TopLevelDestination) {
    Icon(destination.icon, contentDescription = null)
}
