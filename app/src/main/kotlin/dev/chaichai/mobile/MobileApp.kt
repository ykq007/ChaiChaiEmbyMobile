package dev.chaichai.mobile

import android.net.Uri
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.GatewayAuthenticationStatus
import dev.chaichai.mobile.core.contracts.HomeMediaAction
import dev.chaichai.mobile.design.system.EmptyDestination
import dev.chaichai.mobile.design.system.LocalReducedMotion
import dev.chaichai.mobile.feature.home.HomeScreen
import dev.chaichai.mobile.feature.home.HomeWindowClass
import dev.chaichai.mobile.feature.home.HomeUiState
import dev.chaichai.mobile.feature.home.rememberHomeUiState
import dev.chaichai.mobile.feature.libraries.LibrariesScreen
import dev.chaichai.mobile.feature.search.SearchScreen
import dev.chaichai.mobile.feature.settings.SettingsScreen
import dev.chaichai.mobile.feature.server.setup.ServerSetupScreen
import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.platform.adaptive.AdaptiveNavigationPolicy
import dev.chaichai.mobile.platform.adaptive.ContentWidthClass
import dev.chaichai.mobile.platform.adaptive.NavigationPlacement
import dev.chaichai.mobile.platform.adaptive.WindowCharacteristics
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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

private const val MediaActionRoute = "media/{serverId}/{itemId}/{intent}"

@Composable
fun MobileApp(
    boundaries: AppBoundaries,
    separatingHinge: SeparatingHinge?,
    modifier: Modifier = Modifier,
) {
    val serverSetup = boundaries.serverSetup
    val setupState = serverSetup?.state?.collectAsState()?.value
    if (serverSetup != null && setupState !is ServerSetupState.Authenticated) {
        ServerSetupScreen(serverSetup, modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
        return
    }
    val restoredDestination = (setupState as? ServerSetupState.Authenticated)
        ?.returnDestination
        ?.takeIf { route -> TopLevelDestination.entries.any { it.route == route } }
        ?: TopLevelDestination.Home.route
    val homeUiState = rememberHomeUiState()
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
                    AdaptiveShell(boundaries, navController, true, restoredDestination, homeUiState)
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
                    AdaptiveShell(boundaries, navController, true, restoredDestination, homeUiState)
                }
            }

            null -> AdaptiveShell(boundaries, navController, false, restoredDestination, homeUiState)
        }
    }
}

@Composable
private fun AdaptiveShell(
    boundaries: AppBoundaries,
    navController: NavHostController,
    isHingeSeparated: Boolean,
    restoredDestination: String,
    homeUiState: HomeUiState,
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
        val window = WindowCharacteristics(
            usableWidthDp = (maxWidth.value - horizontalInsetsDp).roundToInt(),
            usableHeightDp = (maxHeight.value - verticalInsetsDp).roundToInt(),
            hasSeparatingVerticalHinge = isHingeSeparated,
        )
        val layout = AdaptiveNavigationPolicy.layout(window)
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val reducedMotion = LocalReducedMotion.current
        val scope = rememberCoroutineScope()
        val adaptiveContentState = rememberSaveableStateHolder()
        val navigate: (TopLevelDestination) -> Unit = { destination ->
            scope.launch {
                if (boundaries.gateway.verifyAuthentication(destination.route) != GatewayAuthenticationStatus.Expired) {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
        val content: @Composable (Modifier) -> Unit = { contentModifier ->
            adaptiveContentState.SaveableStateProvider("adaptive-navigation-content") {
                NavHost(
                    navController = navController,
                    startDestination = restoredDestination,
                    modifier = contentModifier,
                    enterTransition = { if (reducedMotion) EnterTransition.None else fadeIn() },
                    exitTransition = { if (reducedMotion) ExitTransition.None else fadeOut() },
                    popEnterTransition = { if (reducedMotion) EnterTransition.None else fadeIn() },
                    popExitTransition = { if (reducedMotion) ExitTransition.None else fadeOut() },
                ) {
                composable(TopLevelDestination.Home.route) {
                    HomeScreen(
                        gateway = boundaries.gateway,
                        isHeightConstrained = layout.isHeightConstrained,
                        windowClass = when (layout.contentWidthClass) {
                            ContentWidthClass.Compact -> HomeWindowClass.Compact
                            ContentWidthClass.Medium -> HomeWindowClass.Medium
                            ContentWidthClass.Expanded -> HomeWindowClass.Expanded
                        },
                        onMediaAction = { action ->
                            boundaries.homeMediaActions.submit(action)
                            navController.navigate(action.navigationRoute())
                        },
                        uiState = homeUiState,
                    )
                }
                composable(MediaActionRoute) { entry ->
                    EmptyDestination(
                        title = "Opening media",
                        description = "Selected ${entry.arguments?.getString("serverId")} / " +
                            "${entry.arguments?.getString("itemId")} for ${entry.arguments?.getString("intent")}.",
                    )
                }
                composable(TopLevelDestination.Libraries.route) { LibrariesScreen() }
                composable(TopLevelDestination.Search.route) { SearchScreen() }
                composable(TopLevelDestination.Settings.route) { SettingsScreen() }
                }
            }
        }

        if (layout.navigationPlacement == NavigationPlacement.Rail) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                NavigationRail(Modifier.testTag("navigation-rail")) {
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
                    NavigationBar(Modifier.testTag("bottom-navigation")) {
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

private fun HomeMediaAction.navigationRoute(): String {
    val intent = when (this) {
        is HomeMediaAction.OpenDetails -> "details"
        is HomeMediaAction.Resume -> "resume"
    }
    return "media/${Uri.encode(identity.serverId)}/${Uri.encode(identity.itemId)}/$intent"
}

private fun NavDestination?.isSelected(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.route == destination.route } == true

@Composable
private fun DestinationIcon(destination: TopLevelDestination) {
    Icon(destination.icon, contentDescription = null)
}
