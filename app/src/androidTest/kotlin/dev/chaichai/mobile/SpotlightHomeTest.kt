package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.HomeSection
import dev.chaichai.mobile.core.contracts.HomeSectionContent
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class SpotlightHomeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun successful_home_prioritizes_actionable_resume_spotlight_and_omits_empty_shelves() {
        val resume = HomeMediaItem(
            "server", "movie", "Arrival", "Movie", playbackPositionTicks = 600_000_000, runtimeTicks = 7_000_000_000,
        )
        val sections = mapOf(
            HomeSection.ContinueWatching to HomeSectionContent(listOf(resume)),
            HomeSection.NextUp to HomeSectionContent(listOf(HomeMediaItem("server", "episode", "The Next Chapter", "Episode"))),
            HomeSection.LatestMovies to HomeSectionContent(emptyList()),
            HomeSection.LatestEpisodes to HomeSectionContent(listOf(HomeMediaItem("server", "latest", "New Episode", "Episode"))),
            HomeSection.AccessibleLibraries to HomeSectionContent(listOf(HomeMediaItem("server", "library", "Movies", "CollectionFolder"))),
        )
        show(HomeFeedState.Ready(sections))

        composeRule.onAllNodesWithText("Arrival")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Resume 1:00").assertIsDisplayed()
        composeRule.onNodeWithText("Latest Movies").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Home discovery content")).assertIsDisplayed()
    }

    @Test
    fun partial_failure_keeps_successful_shelves_and_exposes_section_retry() {
        show(
            HomeFeedState.Ready(
                mapOf(
                    HomeSection.ContinueWatching to HomeSectionContent(listOf(HomeMediaItem("server", "movie", "Saved Movie", "Movie"))),
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
        composeRule.runOnIdle { state.value = HomeFeedState.Empty() }
        composeRule.onNodeWithText("Your Home is empty").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").assertIsDisplayed()
    }

    @Test
    fun constrained_height_collapses_spotlight_but_keeps_discovery_shelves() {
        val item = HomeMediaItem("server", "movie", "Compact Arrival", "Movie", playbackPositionTicks = 600_000_000)
        show(
            HomeFeedState.Ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(item)))),
            Modifier.size(360.dp, 400.dp),
        )
        composeRule.onNodeWithText("Spotlight").assertDoesNotExist()
        composeRule.onNodeWithText("Continue Watching").assertIsDisplayed()
    }

    @Test
    fun stale_cache_remains_visible_while_refreshing_and_after_a_section_failure() {
        val old = HomeMediaItem("server", "movie", "Cached Arrival", "Movie")
        val state = MutableStateFlow<HomeFeedState>(
            HomeFeedState.Ready(
                mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(old))),
                isRefreshing = true,
            ),
        )
        show(state)
        composeRule.onNodeWithText("Cached Arrival").assertIsDisplayed()
        composeRule.onNodeWithText("Refreshing").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = HomeFeedState.Ready(
                mapOf(
                    HomeSection.ContinueWatching to HomeSectionContent(
                        listOf(old), "Couldn't refresh Continue Watching.", isStale = true,
                    ),
                ),
            )
        }
        composeRule.onNodeWithText("Cached Arrival").assertIsDisplayed()
        composeRule.onNodeWithText("Showing saved content").assertIsDisplayed()
        composeRule.onNodeWithText("Retry Continue Watching").assertIsDisplayed()
    }

    @Test
    fun large_text_keeps_primary_actions_reachable_and_talkback_semantics_named() {
        val item = HomeMediaItem(
            "server", "movie", "Accessible Arrival", "Movie", playbackPositionTicks = 600_000_000,
            runtimeTicks = 7_000_000_000,
        )
        show(
            HomeFeedState.Ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(item)))),
            Modifier.size(400.dp, 700.dp),
            fontScale = 2f,
        )
        composeRule.onNode(hasText("Home") and isHeading()).assertIsDisplayed()
        composeRule.onNode(hasText("Resume 1:00") and hasClickAction()).assertIsDisplayed()
        composeRule.onNode(hasText("Refresh") and hasClickAction()).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Home discovery content")).assertIsDisplayed()
    }

    @Test
    fun shelf_scroll_state_survives_saved_state_process_recreation() {
        val media = (0..10).map { HomeMediaItem("server", "movie-$it", "Movie $it", "Movie") }
        val state = HomeFeedState.Ready(mapOf(HomeSection.ContinueWatching to HomeSectionContent(media)))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { appContent(MutableStateFlow(state), Modifier, 1f) }
        composeRule.onNodeWithTag("shelf-ContinueWatching").performScrollToIndex(8)
        composeRule.onNodeWithText("Movie 8").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Movie 8").assertIsDisplayed()
    }

    private fun show(state: HomeFeedState, modifier: Modifier = Modifier, fontScale: Float = 1f) =
        show(MutableStateFlow(state), modifier, fontScale)

    private fun show(state: MutableStateFlow<HomeFeedState>, modifier: Modifier = Modifier, fontScale: Float = 1f) {
        composeRule.setContent { appContent(state, modifier, fontScale) }
    }

    @Composable
    private fun appContent(state: MutableStateFlow<HomeFeedState>, modifier: Modifier, fontScale: Float) {
        val gateway = object : EmbyGateway {
            override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
            override val homeFeed = state
        }
        val boundaries = AppBoundaries(
            gateway,
            object : PlaybackCoordinator { override val isPlaying = MutableStateFlow(false) },
            AppClock { Instant.parse("2026-01-01T00:00:00Z") },
            object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
        )
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries, null, modifier) }
        }
    }
}
