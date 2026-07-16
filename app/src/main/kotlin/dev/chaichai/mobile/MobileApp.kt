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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MovieLibraryState
import dev.chaichai.mobile.core.contracts.SeriesLibraryState
import dev.chaichai.mobile.core.contracts.SearchMediaType
import dev.chaichai.mobile.core.contracts.SearchResult
import dev.chaichai.mobile.design.system.EmptyDestination
import dev.chaichai.mobile.design.system.LocalReducedMotion
import dev.chaichai.mobile.feature.home.HomeScreen
import dev.chaichai.mobile.feature.home.HomeWindowClass
import dev.chaichai.mobile.feature.home.HomeUiState
import dev.chaichai.mobile.feature.home.rememberHomeUiState
import dev.chaichai.mobile.feature.libraries.LibrariesScreen
import dev.chaichai.mobile.feature.libraries.HingeListDetailPanes
import dev.chaichai.mobile.feature.libraries.LibraryWindowClass
import dev.chaichai.mobile.feature.libraries.LibraryCollection
import dev.chaichai.mobile.feature.libraries.MovieDetailsScreen
import dev.chaichai.mobile.feature.libraries.SeriesDetailsScreen
import dev.chaichai.mobile.feature.libraries.EpisodeDetailsScreen
import dev.chaichai.mobile.feature.search.SearchScreen
import dev.chaichai.mobile.feature.search.SearchHingePanes
import dev.chaichai.mobile.feature.settings.SettingsScreen
import dev.chaichai.mobile.feature.server.setup.ServerSetupScreen
import dev.chaichai.mobile.feature.playback.PlaybackHost
import dev.chaichai.mobile.platform.playback.Media3VideoSurface
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.platform.adaptive.AdaptiveNavigationPolicy
import dev.chaichai.mobile.platform.adaptive.ContentWidthClass
import dev.chaichai.mobile.platform.adaptive.NavigationPlacement
import dev.chaichai.mobile.platform.adaptive.PlaybackTracksPresentation
import dev.chaichai.mobile.platform.adaptive.PlaybackSafePane
import dev.chaichai.mobile.platform.adaptive.WindowCharacteristics
import dev.chaichai.mobile.platform.adaptive.PlaybackSystemBars
import kotlin.math.roundToInt
import kotlin.math.max
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
private const val MovieDetailsRoute = "movies/{serverId}/{itemId}"
private const val SeriesDetailsRoute = "series/{serverId}/{itemId}"
private const val SeasonDetailsRoute = "series/{serverId}/{seriesId}/season/{seasonId}"
private const val EpisodeDetailsRoute = "episodes/{serverId}/{itemId}"

private data class VerticalHingePanes(
    val leftWidth: Dp,
    val hingeWidth: Dp,
    val rightWidth: Dp,
)

private data class ScopedLibrarySelection(
    val scope: HomeScope,
    val identity: MediaIdentity,
)

private val ScopedLibrarySelectionSaver = Saver<ScopedLibrarySelection?, List<String>>(
    save = { selection -> selection?.let { listOf(it.scope.serverId, it.scope.userId, it.identity.itemId) } },
    restore = {
        ScopedLibrarySelection(
            HomeScope(it[0], it[1]),
            MediaIdentity(it[0], it[2]),
        )
    },
)

private fun MovieLibraryState.scopeOrNull(): HomeScope? = when (this) {
    is MovieLibraryState.Ready -> scope
    is MovieLibraryState.EmptyLibrary -> scope
    is MovieLibraryState.EmptyFiltered -> scope
    is MovieLibraryState.Failure -> scope
    MovieLibraryState.Loading -> null
}

private fun SeriesLibraryState.scopeOrNull(): HomeScope? = when (this) {
    is SeriesLibraryState.Ready -> scope
    is SeriesLibraryState.EmptyLibrary -> scope
    is SeriesLibraryState.EmptyFiltered -> scope
    is SeriesLibraryState.Failure -> scope
    SeriesLibraryState.Loading -> null
}

@Composable
fun MobileApp(
    boundaries: AppBoundaries,
    separatingHinge: SeparatingHinge?,
    modifier: Modifier = Modifier,
    onTogglePlaybackOrientation: () -> Unit = {},
    onTogglePlaybackFullscreen: () -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onPlaybackSystemBarsChanged: (PlaybackSystemBars) -> Unit = {},
    keepPlaybackControlsVisible: Boolean = false,
) {
    val serverSetup = boundaries.serverSetup
    val setupState = serverSetup?.state?.collectAsState()?.value
    val movieLibraryState by boundaries.gateway.movieLibrary.collectAsState()
    val seriesLibraryState by boundaries.gateway.seriesLibrary.collectAsState()
    val activeLibraryScope = movieLibraryState.scopeOrNull() ?: seriesLibraryState.scopeOrNull()
    var libraryCollection by rememberSaveable { mutableStateOf(LibraryCollection.Movies) }
    var scopedLibrarySelection by rememberSaveable(
        "mobile-app-library-selection",
        stateSaver = ScopedLibrarySelectionSaver,
    ) { mutableStateOf<ScopedLibrarySelection?>(null) }
    val libraryGridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()
    val adaptiveContentState = rememberSaveableStateHolder()
    LaunchedEffect(activeLibraryScope, scopedLibrarySelection) {
        if (activeLibraryScope != null && scopedLibrarySelection?.scope != null &&
            scopedLibrarySelection?.scope != activeLibraryScope
        ) {
            scopedLibrarySelection = null
        }
    }
    if (serverSetup != null && setupState !is ServerSetupState.Authenticated) {
        ServerSetupScreen(serverSetup, modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
        return
    }
    val restoredDestination = (setupState as? ServerSetupState.Authenticated)
        ?.returnDestination
        ?.takeIf(::isRestorableDestination)
        ?: TopLevelDestination.Home.route
    val homeUiState = rememberHomeUiState()
    val playbackState by boundaries.playback.state.collectAsState()
    val librarySelection = scopedLibrarySelection
        ?.takeIf { it.scope == activeLibraryScope }
        ?.identity
    val onLibrarySelectionChanged: (MediaIdentity?) -> Unit = { identity ->
        scopedLibrarySelection = identity?.let { selected ->
            activeLibraryScope?.let { scope -> ScopedLibrarySelection(scope, selected) }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val navController = rememberNavController()
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeDrawing = WindowInsets.safeDrawing
        val leftInsetDp = with(density) { safeDrawing.getLeft(density, layoutDirection).toDp().value }
        val rightInsetDp = with(density) { safeDrawing.getRight(density, layoutDirection).toDp().value }
        val topInsetDp = with(density) { safeDrawing.getTop(density).toDp().value }
        val bottomInsetDp = with(density) { safeDrawing.getBottom(density).toDp().value }
        val usableWidth = (maxWidth.value - leftInsetDp - rightInsetDp).coerceAtLeast(0f)
        val usableHeight = (maxHeight.value - topInsetDp - bottomInsetDp).coerceAtLeast(0f)
        val window = when (separatingHinge?.orientation) {
            HingeOrientation.Vertical -> {
                val left = (with(density) { separatingHinge.leftPx.toDp().value } - leftInsetDp).coerceAtLeast(0f)
                val right = (
                    maxWidth.value - with(density) { separatingHinge.rightPx.toDp().value } - rightInsetDp
                ).coerceAtLeast(0f)
                WindowCharacteristics(
                    usableWidthDp = max(left, right).roundToInt(),
                    usableHeightDp = usableHeight.roundToInt(),
                    hasSeparatingVerticalHinge = true,
                    verticalPaneWidthsDp = listOf(left.roundToInt(), right.roundToInt()),
                )
            }
            HingeOrientation.Horizontal -> {
                val top = (with(density) { separatingHinge.topPx.toDp().value } - topInsetDp).coerceAtLeast(0f)
                val bottom = (
                    maxHeight.value - with(density) { separatingHinge.bottomPx.toDp().value } - bottomInsetDp
                ).coerceAtLeast(0f)
                WindowCharacteristics(
                    usableWidthDp = usableWidth.roundToInt(),
                    usableHeightDp = max(top, bottom).roundToInt(),
                    hasSeparatingHorizontalHinge = true,
                    horizontalPaneHeightsDp = listOf(top.roundToInt(), bottom.roundToInt()),
                )
            }
            null -> WindowCharacteristics(usableWidth.roundToInt(), usableHeight.roundToInt())
        }
        Box(Modifier.fillMaxSize()) {
        when (separatingHinge?.orientation) {
            HingeOrientation.Vertical -> {
                val (leftWidthDp, rightWidthDp) = window.verticalPaneWidthsDp
                val leftWidth = leftWidthDp.dp
                val rightWidth = rightWidthDp.dp
                val hingeWidth = with(density) { (separatingHinge.rightPx - separatingHinge.leftPx).toDp() }
                val panes = VerticalHingePanes(leftWidth, hingeWidth, rightWidth)
                val supportsTwoPane = AdaptiveNavigationPolicy.layout(window).supportsListDetail
                if (supportsTwoPane) {
                    AdaptiveShell(
                        boundaries, navController, window, restoredDestination, homeUiState,
                        verticalHingePanes = panes,
                        librarySelection = librarySelection,
                        onLibrarySelectionChanged = onLibrarySelectionChanged,
                        libraryGridState = libraryGridState,
                        searchListState = searchListState,
                        libraryCollection = libraryCollection,
                        onLibraryCollectionChanged = { libraryCollection = it },
                        adaptiveContentState = adaptiveContentState,
                    )
                } else {
                    val useLeft = leftWidth >= rightWidth
                    val selectedWidth = if (useLeft) leftWidth else rightWidth
                    Box(
                        modifier = Modifier.width(selectedWidth).fillMaxHeight()
                            .align(if (useLeft) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight),
                    ) {
                        AdaptiveShell(
                            boundaries,
                            navController,
                            WindowCharacteristics(selectedWidth.value.roundToInt(), window.usableHeightDp),
                            restoredDestination,
                            homeUiState,
                            librarySelection = librarySelection,
                            onLibrarySelectionChanged = onLibrarySelectionChanged,
                            libraryGridState = libraryGridState,
                            searchListState = searchListState,
                            libraryCollection = libraryCollection,
                            onLibraryCollectionChanged = { libraryCollection = it },
                            adaptiveContentState = adaptiveContentState,
                        )
                    }
                }
            }

            HingeOrientation.Horizontal -> {
                val (topHeightDp, bottomHeightDp) = window.horizontalPaneHeightsDp
                val topHeight = topHeightDp.dp
                val bottomHeight = bottomHeightDp.dp
                val useTop = topHeight >= bottomHeight
                val selectedHeight = if (useTop) topHeight else bottomHeight
                Box(
                    modifier = Modifier.fillMaxWidth().height(selectedHeight)
                        .align(if (useTop) Alignment.TopCenter else Alignment.BottomCenter),
                ) {
                    AdaptiveShell(
                        boundaries,
                        navController,
                        WindowCharacteristics(window.usableWidthDp, selectedHeight.value.roundToInt()),
                        restoredDestination,
                        homeUiState,
                        librarySelection = librarySelection,
                        onLibrarySelectionChanged = onLibrarySelectionChanged,
                        libraryGridState = libraryGridState,
                        searchListState = searchListState,
                        libraryCollection = libraryCollection,
                        onLibraryCollectionChanged = { libraryCollection = it },
                        adaptiveContentState = adaptiveContentState,
                    )
                }
            }

            null -> AdaptiveShell(
                boundaries, navController, window, restoredDestination, homeUiState,
                librarySelection = librarySelection,
                onLibrarySelectionChanged = onLibrarySelectionChanged,
                libraryGridState = libraryGridState,
                searchListState = searchListState,
                libraryCollection = libraryCollection,
                onLibraryCollectionChanged = { libraryCollection = it },
                adaptiveContentState = adaptiveContentState,
            )
        }
        }
        val playbackWindowLayout = AdaptiveNavigationPolicy.playbackWindowLayout(window)
        val playbackSystemBars = if (
            playbackWindowLayout.systemBars == PlaybackSystemBars.Immersive &&
            (playbackState is PlaybackState.Negotiating || playbackState is PlaybackState.Active)
        ) PlaybackSystemBars.Immersive else PlaybackSystemBars.Visible
        LaunchedEffect(playbackSystemBars) {
            onPlaybackSystemBarsChanged(playbackSystemBars)
        }
        if (playbackState is PlaybackState.Active) Media3VideoSurface(Modifier.fillMaxSize())
        PlaybackHost(
            boundaries.playback,
            Modifier.fillMaxSize(),
            onTogglePlaybackOrientation,
            onTogglePlaybackFullscreen,
            onPlaybackEnded,
            windowLayout = playbackWindowLayout,
            keepControlsVisible = keepPlaybackControlsVisible,
            danmaku = boundaries.danmaku,
            subtitleProvider = boundaries.subtitleProvider,
        )
        }
}

@Composable
private fun AdaptiveShell(
    boundaries: AppBoundaries,
    navController: NavHostController,
    window: WindowCharacteristics,
    restoredDestination: String,
    homeUiState: HomeUiState,
    verticalHingePanes: VerticalHingePanes? = null,
    librarySelection: MediaIdentity?,
    onLibrarySelectionChanged: (MediaIdentity?) -> Unit,
    libraryGridState: LazyGridState,
    searchListState: androidx.compose.foundation.lazy.LazyListState,
    libraryCollection: LibraryCollection,
    onLibraryCollectionChanged: (LibraryCollection) -> Unit,
    adaptiveContentState: SaveableStateHolder,
) {
    Box(Modifier.fillMaxSize()) {
        val layout = AdaptiveNavigationPolicy.layout(window)
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val reducedMotion = LocalReducedMotion.current
        val scope = rememberCoroutineScope()
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
                composable(TopLevelDestination.Libraries.route) {
                    LibrariesScreen(
                        gateway = boundaries.gateway,
                        windowClass = when (layout.contentWidthClass) {
                            ContentWidthClass.Compact -> LibraryWindowClass.Compact
                            ContentWidthClass.Medium -> LibraryWindowClass.Medium
                            ContentWidthClass.Expanded -> LibraryWindowClass.Expanded
                        },
                        isHeightConstrained = layout.isHeightConstrained,
                        supportsListDetail = layout.supportsListDetail,
                        playback = boundaries.playback,
                        hingePanes = verticalHingePanes?.let {
                            HingeListDetailPanes(it.leftWidth, it.hingeWidth, it.rightWidth)
                        },
                        initialSelection = librarySelection,
                        onSelectionChanged = onLibrarySelectionChanged,
                        detailsAuthenticationReturnDestination = TopLevelDestination.Libraries.route,
                        gridState = libraryGridState,
                        initialCollection = libraryCollection,
                        selectedCollection = libraryCollection,
                        onCollectionChanged = onLibraryCollectionChanged,
                        onOpenDetails = { identity ->
                            navController.navigate(identity.movieDetailsRoute())
                        },
                    )
                }
                composable(MovieDetailsRoute) { entry ->
                    val serverId = entry.arguments?.getString("serverId")
                    val itemId = entry.arguments?.getString("itemId")
                    if (serverId != null && itemId != null) {
                        MovieDetailsScreen(
                            gateway = boundaries.gateway,
                            identity = dev.chaichai.mobile.core.contracts.MediaIdentity(serverId, itemId),
                            playback = boundaries.playback,
                            windowClass = when (layout.contentWidthClass) {
                                ContentWidthClass.Compact -> LibraryWindowClass.Compact
                                ContentWidthClass.Medium -> LibraryWindowClass.Medium
                                ContentWidthClass.Expanded -> LibraryWindowClass.Expanded
                            },
                            isHeightConstrained = layout.isHeightConstrained,
                            authenticationReturnDestination = MediaIdentity(serverId, itemId).movieDetailsRoute(),
                        )
                    }
                }
                composable(TopLevelDestination.Search.route) {
                    SearchScreen(
                        gateway = boundaries.gateway,
                        supportsListDetail = layout.supportsListDetail,
                        onOpenDetails = { navController.navigate(it.detailsRoute()) },
                        // Aggregated Search (#29): a selected result may carry a different
                        // server's provenance than the currently active one. Activate that
                        // result's scope first so the shared gateway's details load (and any
                        // credential it uses) resolve against the CORRECT server — never a
                        // collision between identical item ids on different servers.
                        onResultSelected = { result -> boundaries.serverDirectory?.activateScope(result.scope) },
                        detailContent = { result, detailModifier ->
                            SearchResultDetails(
                                boundaries,
                                result,
                                layout.isHeightConstrained,
                                detailModifier,
                            )
                        },
                        hingePanes = verticalHingePanes?.let {
                            SearchHingePanes(it.leftWidth, it.hingeWidth, it.rightWidth)
                        },
                        listState = searchListState,
                    )
                }
                composable(SeriesDetailsRoute) { entry ->
                    val serverId = entry.arguments?.getString("serverId")
                    val itemId = entry.arguments?.getString("itemId")
                    if (serverId != null && itemId != null) {
                        SeriesDetailsScreen(
                            boundaries.gateway,
                            MediaIdentity(serverId, itemId),
                            boundaries.playback,
                            layout.isHeightConstrained,
                            authenticationReturnDestination = MediaIdentity(serverId, itemId).seriesDetailsRoute(),
                        )
                    }
                }
                composable(SeasonDetailsRoute) { entry ->
                    val serverId = entry.arguments?.getString("serverId")
                    val seriesId = entry.arguments?.getString("seriesId")
                    val seasonId = entry.arguments?.getString("seasonId")
                    if (serverId != null && seriesId != null && seasonId != null) {
                        SeriesDetailsScreen(
                            boundaries.gateway,
                            MediaIdentity(serverId, seriesId),
                            boundaries.playback,
                            layout.isHeightConstrained,
                            authenticationReturnDestination = "series/${Uri.encode(serverId)}/${Uri.encode(seriesId)}/season/${Uri.encode(seasonId)}",
                            initialSeasonIdentity = MediaIdentity(serverId, seasonId),
                        )
                    }
                }
                composable(EpisodeDetailsRoute) { entry ->
                    val serverId = entry.arguments?.getString("serverId")
                    val itemId = entry.arguments?.getString("itemId")
                    if (serverId != null && itemId != null) {
                        EpisodeDetailsScreen(
                            boundaries.gateway,
                            MediaIdentity(serverId, itemId),
                            boundaries.playback,
                            authenticationReturnDestination = MediaIdentity(serverId, itemId).episodeDetailsRoute(),
                        )
                    }
                }
                composable(TopLevelDestination.Settings.route) {
                    SettingsScreen(
                        account = boundaries.account,
                        serverDirectory = boundaries.serverDirectory,
                        serverProxy = boundaries.serverProxy,
                        danmakuEndpoints = boundaries.danmakuEndpoints,
                        subtitleProviders = boundaries.subtitleProviders,
                        playbackDiagnostics = boundaries.playbackDiagnostics,
                    )
                }
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
                    val navigation: @Composable (Modifier) -> Unit = { navigationModifier ->
                        NavigationBar(navigationModifier.testTag("bottom-navigation")) {
                            TopLevelDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentDestination.isSelected(destination),
                                    onClick = { navigate(destination) },
                                    icon = { DestinationIcon(destination) },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                    verticalHingePanes?.let { panes ->
                        Box(Modifier.fillMaxWidth()) {
                            val useLeft = panes.leftWidth >= panes.rightWidth
                            navigation(
                                Modifier.width(if (useLeft) panes.leftWidth else panes.rightWidth)
                                    .align(if (useLeft) Alignment.CenterStart else Alignment.CenterEnd),
                            )
                        }
                    } ?: navigation(Modifier.fillMaxWidth())
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    val hinge = verticalHingePanes
                    val showAcrossPanes = currentDestination.isSelected(TopLevelDestination.Libraries) ||
                        currentDestination.isSelected(TopLevelDestination.Search)
                    if (hinge != null && !showAcrossPanes) {
                        val useLeft = hinge.leftWidth >= hinge.rightWidth
                        content(
                            Modifier.width(if (useLeft) hinge.leftWidth else hinge.rightWidth)
                                .fillMaxHeight()
                                .align(if (useLeft) Alignment.CenterStart else Alignment.CenterEnd),
                        )
                    } else {
                        content(Modifier.fillMaxSize())
                    }
                }
            }
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

@Composable
private fun SearchResultDetails(
    boundaries: AppBoundaries,
    result: SearchResult,
    isHeightConstrained: Boolean,
    modifier: Modifier,
) {
    val returnDestination = TopLevelDestination.Search.route
    when (result.mediaType) {
        SearchMediaType.Movie -> MovieDetailsScreen(
            boundaries.gateway,
            result.identity,
            boundaries.playback,
            LibraryWindowClass.Medium,
            isHeightConstrained,
            modifier,
            returnDestination,
        )
        SearchMediaType.Series -> SeriesDetailsScreen(
            boundaries.gateway,
            result.identity,
            boundaries.playback,
            isHeightConstrained,
            modifier,
            returnDestination,
        )
        SearchMediaType.Season -> SeriesDetailsScreen(
            boundaries.gateway,
            result.seriesIdentity ?: result.identity,
            boundaries.playback,
            isHeightConstrained,
            modifier,
            returnDestination,
            result.identity,
        )
        SearchMediaType.Episode -> EpisodeDetailsScreen(
            boundaries.gateway,
            result.identity,
            boundaries.playback,
            modifier,
            returnDestination,
        )
    }
}

private fun MediaIdentity.movieDetailsRoute(): String =
    "movies/${Uri.encode(serverId)}/${Uri.encode(itemId)}"

private fun MediaIdentity.seriesDetailsRoute(): String =
    "series/${Uri.encode(serverId)}/${Uri.encode(itemId)}"

private fun MediaIdentity.episodeDetailsRoute(): String =
    "episodes/${Uri.encode(serverId)}/${Uri.encode(itemId)}"

private fun SearchResult.detailsRoute(): String = when (mediaType) {
    SearchMediaType.Movie -> identity.movieDetailsRoute()
    SearchMediaType.Series -> identity.seriesDetailsRoute()
    SearchMediaType.Season -> {
        val series = seriesIdentity ?: identity
        "series/${Uri.encode(series.serverId)}/${Uri.encode(series.itemId)}/season/${Uri.encode(identity.itemId)}"
    }
    SearchMediaType.Episode -> identity.episodeDetailsRoute()
}

private fun isRestorableDestination(route: String): Boolean =
    TopLevelDestination.entries.any { it.route == route } ||
        route.matches(Regex("movies/[^/]+/[^/]+")) ||
        route.matches(Regex("series/[^/]+/[^/]+")) ||
        route.matches(Regex("series/[^/]+/[^/]+/season/[^/]+")) ||
        route.matches(Regex("episodes/[^/]+/[^/]+"))

private fun NavDestination?.isSelected(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.route == destination.route } == true

@Composable
private fun DestinationIcon(destination: TopLevelDestination) {
    Icon(destination.icon, contentDescription = null)
}
