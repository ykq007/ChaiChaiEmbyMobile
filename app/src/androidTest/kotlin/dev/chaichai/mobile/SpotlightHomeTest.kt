package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.HomeSection
import dev.chaichai.mobile.core.contracts.HomeSectionContent
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.HomeMediaAction
import dev.chaichai.mobile.core.contracts.HomeMediaActionBoundary
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.platform.server.createRoomHomeCache
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class SpotlightHomeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun successful_home_prioritizes_actionable_resume_spotlight_and_omits_empty_shelves() {
        val resume = media("movie", "Arrival", playbackPositionTicks = 600_000_000, runtimeTicks = 7_000_000_000)
        val sections = mapOf(
            HomeSection.ContinueWatching to HomeSectionContent(listOf(resume)),
            HomeSection.NextUp to HomeSectionContent(listOf(media("episode", "The Next Chapter", "Episode"))),
            HomeSection.LatestMovies to HomeSectionContent(emptyList()),
            HomeSection.LatestEpisodes to HomeSectionContent(listOf(media("latest", "New Episode", "Episode"))),
            HomeSection.AccessibleLibraries to HomeSectionContent(listOf(media("library", "Movies", "CollectionFolder"))),
        )
        show(ready(sections))

        composeRule.onAllNodesWithText("Arrival")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Resume 1:00").assertIsDisplayed()
        composeRule.onNodeWithText("Latest Movies").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Home discovery content", substring = true)).assertIsDisplayed()
    }

    @Test
    fun partial_failure_keeps_successful_shelves_and_exposes_section_retry() {
        show(
            ready(
                mapOf(
                    HomeSection.ContinueWatching to HomeSectionContent(listOf(media("movie", "Saved Movie"))),
                    HomeSection.NextUp to HomeSectionContent(emptyList(), "Couldn't load Next Up."),
                ),
            ),
        )
        composeRule.onNodeWithText("Saved Movie").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't load Next Up.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry Next Up").assertIsDisplayed()
    }

    @Test
    fun total_failure_and_empty_home_are_distinct_recoverable_states() {
        val state = MutableStateFlow<HomeFeedState>(HomeFeedState.Failure("Home couldn't be loaded."))
        show(state)
        composeRule.onNodeWithText("Home couldn't be loaded.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.runOnIdle { state.value = HomeFeedState.Empty }
        composeRule.onNodeWithText("Your Home is empty").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").assertIsDisplayed()
    }

    @Test
    fun constrained_height_collapses_spotlight_but_keeps_discovery_shelves() {
        val item = media("movie", "Compact Arrival", playbackPositionTicks = 600_000_000)
        show(
            ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(item)))),
            Modifier.size(360.dp, 400.dp),
        )
        composeRule.onNodeWithText("Spotlight").assertDoesNotExist()
        composeRule.onNodeWithText("Continue Watching").assertIsDisplayed()
    }

    @Test
    fun room_cache_remains_visible_while_refreshing_and_after_a_section_failure() {
        runBlocking {
            val old = media("movie", "Cached Arrival")
        val sections = mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(old)))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("server_scoped_home_cache.db")
        createRoomHomeCache(context).saveFeed(HomeScope("server", "user"), sections)
        val recreatedCache = createRoomHomeCache(context)
        val refreshMayFail = CompletableDeferred<Unit>()
        val state = MutableStateFlow<HomeFeedState>(HomeFeedState.Loading)
        val gateway = object : EmbyGateway {
            override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
            override val homeFeed = state
            override suspend fun refreshHome() {
                val cached = checkNotNull(recreatedCache.loadFeed(HomeScope("server", "user")))
                state.value = ready(cached, isRefreshing = true)
                refreshMayFail.await()
                state.value = ready(
                    cached.mapValues { (_, content) ->
                        content.copy(failureMessage = "Couldn't refresh Continue Watching.", isStale = true)
                    },
                )
            }
        }
        showGateway(gateway)
        composeRule.onNodeWithText("Cached Arrival").assertIsDisplayed()
        composeRule.onNodeWithText("Refreshing").assertIsDisplayed()
        composeRule.runOnIdle {
            refreshMayFail.complete(Unit)
            state.value = ready(
                sections.mapValues { (_, content) ->
                    content.copy(failureMessage = "Couldn't refresh Continue Watching.", isStale = true)
                },
            )
        }
        composeRule.onNodeWithText("Cached Arrival").assertIsDisplayed()
        composeRule.onNodeWithText("Showing saved content").assertIsDisplayed()
            composeRule.onNodeWithText("Retry Continue Watching").assertIsDisplayed()
        }
    }

    @Test
    fun large_text_keeps_primary_actions_reachable_and_talkback_semantics_named() {
        val item = media(
            "movie", "Accessible Arrival", playbackPositionTicks = 600_000_000, runtimeTicks = 7_000_000_000,
        )
        show(
            ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(item)))),
            Modifier.size(400.dp, 700.dp),
            fontScale = 2f,
        )
        composeRule.onNode(hasText("Home") and isHeading()).assertIsDisplayed()
        composeRule.onNode(hasText("Resume 1:00") and hasClickAction()).assertIsDisplayed()
        composeRule.onNode(hasText("Refresh") and hasClickAction()).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Home discovery content", substring = true)).assertIsDisplayed()
    }

    @Test
    fun shelf_scroll_state_survives_saved_state_process_recreation() {
        val media = (0..10).map { media("movie-$it", "Movie $it") }
        val state = ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(media)))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { appContent(MutableStateFlow(state), Modifier, 1f) }
        composeRule.onNodeWithTag("shelf-ContinueWatching").performScrollToIndex(8)
        composeRule.onNodeWithText("Movie 8").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Movie 8").assertIsDisplayed()
    }

    @Test
    fun spotlight_and_shelf_actions_submit_stable_server_scoped_identity() {
        val actions = mutableListOf<HomeMediaAction>()
        val resume = media(
            "movie", "Action Arrival", playbackPositionTicks = 600_000_000, runtimeTicks = 7_000_000_000,
        )
        show(
            ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(resume)))),
            actionBoundary = HomeMediaActionBoundary(actions::add),
        )
        composeRule.onNodeWithText("Resume 1:00").performClick()
        assertEquals(
            HomeMediaAction.Resume(MediaIdentity("server", "movie"), 600_000_000),
            actions.single(),
        )
        composeRule.onNodeWithText("Opening media").assertIsDisplayed()
    }

    @Test
    fun shelf_action_opens_stable_server_scoped_media_destination() {
        val actions = mutableListOf<HomeMediaAction>()
        show(
            ready(mapOf(HomeSection.LatestMovies to HomeSectionContent(listOf(media("movie", "Action Arrival"))))),
            actionBoundary = HomeMediaActionBoundary(actions::add),
        )
        composeRule.onNodeWithText("Action Arrival").performClick()
        assertEquals(HomeMediaAction.OpenDetails(MediaIdentity("server", "movie")), actions.single())
        composeRule.onNodeWithText("Opening media").assertIsDisplayed()
    }

    @Test
    fun talkback_traversal_orders_home_before_shelves() {
        show(
            ready(
                mapOf(
                    HomeSection.ContinueWatching to HomeSectionContent(
                        listOf(media("movie", "Arrival", playbackPositionTicks = 600_000_000)),
                        failureMessage = "Couldn't refresh Continue Watching.",
                        isStale = true,
                    ),
                ),
            ),
        )
        composeRule.onNode(
            hasText("Home") and SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 0f),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasText("Refresh") and hasClickAction() and
                SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, .5f),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasText("Resume 1:00") and hasClickAction() and
                SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasText("Continue Watching") and SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 2f),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasContentDescription("Arrival, Movie") and hasClickAction() and
                SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 3f),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasText("Showing saved content") and
                SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 2.5f),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasText("Retry Continue Watching") and hasClickAction() and
                SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 9f),
        ).assertIsDisplayed()
    }

    @Test
    fun compact_window_uses_compact_home_density() {
        show(ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(media("movie", "Arrival", playbackPositionTicks = 600_000_000))))), Modifier.size(400.dp, 700.dp), densityValue = 1f)
        composeRule.onNode(hasContentDescription("Home discovery content, compact layout")).assertIsDisplayed()
        composeRule.onNodeWithTag("home-spotlight").assertHeightIsEqualTo(240.dp)
        composeRule.onNodeWithTag("media-server:movie").assertWidthIsEqualTo(252.dp)
        composeRule.onNodeWithTag("bottom-navigation").assertIsDisplayed()
    }

    @Test
    fun compact_landscape_window_uses_compact_home_density() {
        show(ready(mapOf(HomeSection.LatestMovies to HomeSectionContent(listOf(media("movie", "Arrival"))))), Modifier.size(580.dp, 350.dp), densityValue = 1f)
        composeRule.onNode(hasContentDescription("Home discovery content, compact layout")).assertIsDisplayed()
    }

    @Test
    fun medium_split_window_uses_medium_home_density() {
        show(ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(media("movie", "Arrival", playbackPositionTicks = 600_000_000))))), Modifier.size(700.dp, 700.dp), densityValue = 1f)
        composeRule.onNode(hasContentDescription("Home discovery content, medium layout")).assertIsDisplayed()
        composeRule.onNodeWithTag("home-spotlight").assertHeightIsEqualTo(280.dp)
        composeRule.onNodeWithTag("media-server:movie").assertWidthIsEqualTo(280.dp)
        composeRule.onNodeWithTag("navigation-rail").assertIsDisplayed()
    }

    @Test
    fun expanded_window_uses_expanded_home_density() {
        show(ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(media("movie", "Arrival", playbackPositionTicks = 600_000_000))))), Modifier.size(900.dp, 900.dp), densityValue = 1f)
        composeRule.onNode(hasContentDescription("Home discovery content, expanded layout")).assertIsDisplayed()
        composeRule.onNodeWithTag("home-spotlight").assertHeightIsEqualTo(320.dp)
        composeRule.onNodeWithTag("media-server:movie").assertWidthIsEqualTo(320.dp)
        composeRule.onNodeWithTag("navigation-rail").assertIsDisplayed()
    }

    @Test
    fun expanded_portrait_window_uses_expanded_home_density() {
        show(ready(mapOf(HomeSection.LatestMovies to HomeSectionContent(listOf(media("movie", "Arrival"))))), Modifier.size(900.dp, 1200.dp), densityValue = 1f)
        composeRule.onNode(hasContentDescription("Home discovery content, expanded layout")).assertIsDisplayed()
    }

    @Test
    fun expanded_landscape_window_uses_expanded_home_density() {
        show(ready(mapOf(HomeSection.LatestMovies to HomeSectionContent(listOf(media("movie", "Arrival"))))), Modifier.size(1000.dp, 700.dp), densityValue = 1f)
        composeRule.onNode(hasContentDescription("Home discovery content, expanded layout")).assertIsDisplayed()
    }

    @Test
    fun separating_fold_selects_an_unobstructed_compact_pane() {
        show(
            ready(mapOf(HomeSection.LatestMovies to HomeSectionContent(listOf(media("movie", "Arrival"))))),
            Modifier.size(900.dp, 900.dp),
            densityValue = 1f,
            hinge = SeparatingHinge(400, 0, 420, 900, HingeOrientation.Vertical),
        )
        composeRule.onNode(hasContentDescription("Home discovery content, compact layout"))
            .assertIsDisplayed().assertWidthIsEqualTo(480.dp)
    }

    @Test
    fun shelf_and_destination_state_survive_resize_rotation_background_and_fold_transitions() {
        val size = mutableStateOf(DpSize(400.dp, 700.dp))
        val hinge = mutableStateOf<SeparatingHinge?>(null)
        val lifecycleOwner = MutableLifecycleOwner()
        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        val media = (0..10).map { media("movie-$it", "Transition Movie $it") }
        val state = MutableStateFlow<HomeFeedState>(
            ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(media))),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                appContent(
                    state,
                    Modifier.size(size.value.width, size.value.height),
                    1f,
                    densityValue = 1f,
                    hinge = hinge.value,
                )
            }
        }
        composeRule.onNodeWithTag("shelf-ContinueWatching").performScrollToIndex(8)
        composeRule.onNodeWithText("Transition Movie 8").assertIsDisplayed()

        composeRule.runOnIdle { size.value = DpSize(700.dp, 400.dp) }
        composeRule.onNodeWithText("Transition Movie 8").assertIsDisplayed()
        composeRule.runOnIdle {
            lifecycleOwner.moveTo(Lifecycle.State.CREATED)
        }
        composeRule.runOnIdle {
            lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
            size.value = DpSize(900.dp, 900.dp)
            hinge.value = SeparatingHinge(400, 0, 420, 900, HingeOrientation.Vertical)
        }
        composeRule.onNodeWithText("Transition Movie 8").assertIsDisplayed()
        composeRule.runOnIdle { hinge.value = null }
        composeRule.onNodeWithText("Transition Movie 8").performClick()
        composeRule.onNodeWithText("Opening media").assertIsDisplayed()

        composeRule.runOnIdle {
            size.value = DpSize(400.dp, 700.dp)
            lifecycleOwner.moveTo(Lifecycle.State.CREATED)
        }
        composeRule.runOnIdle {
            lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        }
        composeRule.onNodeWithText("Opening media").assertIsDisplayed()
    }

    private fun show(
        state: HomeFeedState,
        modifier: Modifier = Modifier,
        fontScale: Float = 1f,
        densityValue: Float? = null,
        hinge: SeparatingHinge? = null,
        actionBoundary: HomeMediaActionBoundary = HomeMediaActionBoundary {},
    ) = show(MutableStateFlow(state), modifier, fontScale, densityValue, hinge, actionBoundary)

    private fun show(
        state: MutableStateFlow<HomeFeedState>,
        modifier: Modifier = Modifier,
        fontScale: Float = 1f,
        densityValue: Float? = null,
        hinge: SeparatingHinge? = null,
        actionBoundary: HomeMediaActionBoundary = HomeMediaActionBoundary {},
    ) {
        composeRule.setContent { appContent(state, modifier, fontScale, densityValue, hinge, actionBoundary) }
    }

    private fun showGateway(gateway: EmbyGateway) {
        composeRule.setContent {
            appContent(MutableStateFlow(HomeFeedState.Loading), Modifier, 1f, gateway = gateway)
        }
    }

    @Composable
    private fun appContent(
        state: MutableStateFlow<HomeFeedState>,
        modifier: Modifier,
        fontScale: Float,
        densityValue: Float? = null,
        hinge: SeparatingHinge? = null,
        actionBoundary: HomeMediaActionBoundary = HomeMediaActionBoundary {},
        gateway: EmbyGateway? = null,
    ) {
        val activeGateway = gateway ?: object : EmbyGateway {
            override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
            override val homeFeed = state
        }
        val boundaries = AppBoundaries(
            activeGateway,
            object : PlaybackCoordinator { override val isPlaying = MutableStateFlow(false) },
            AppClock { Instant.parse("2026-01-01T00:00:00Z") },
            object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
            homeMediaActions = actionBoundary,
        )
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(densityValue ?: density.density, fontScale)) {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries, hinge, modifier) }
        }
    }

    private fun ready(
        sections: Map<HomeSection, HomeSectionContent>,
        isRefreshing: Boolean = false,
    ) = HomeFeedState.Ready(HomeScope("server", "user"), sections, isRefreshing)

    private fun media(
        itemId: String,
        title: String,
        type: String = "Movie",
        playbackPositionTicks: Long = 0,
        runtimeTicks: Long? = null,
    ) = HomeMediaItem(
        MediaIdentity("server", itemId), title, type,
        playbackPositionTicks = playbackPositionTicks,
        runtimeTicks = runtimeTicks,
    )

    private class MutableLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
        fun moveTo(state: Lifecycle.State) { registry.currentState = state }
    }
}
