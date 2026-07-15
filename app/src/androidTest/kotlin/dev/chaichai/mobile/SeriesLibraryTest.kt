package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.EpisodeDetails
import dev.chaichai.mobile.core.contracts.EpisodeDetailsState
import dev.chaichai.mobile.core.contracts.EpisodeSummary
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.SeasonEpisodesState
import dev.chaichai.mobile.core.contracts.SeasonSummary
import dev.chaichai.mobile.core.contracts.SeriesDetails
import dev.chaichai.mobile.core.contracts.SeriesDetailsState
import dev.chaichai.mobile.core.contracts.SeriesLibraryState
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.LibraryQuery
import dev.chaichai.mobile.SeparatingHinge
import dev.chaichai.mobile.HingeOrientation
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.libraries.LibrariesScreen
import dev.chaichai.mobile.feature.libraries.LibraryWindowClass
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class SeriesLibraryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun show_to_season_to_episode_preserves_context_and_requires_an_explicit_play_decision() {
        val gateway = FakeSeriesGateway()
        val playback = FakeSeriesPlayback()
        composeRule.setContent { ChaiChaiTheme(reducedMotion = false) { MobileApp(boundaries(gateway, playback), null) } }

        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithText("The Expanse").performClick()
        composeRule.onNodeWithText("Season 1").performClick()
        composeRule.onNodeWithText("S1 E1  Dulcinea").performClick()

        composeRule.onNodeWithText("A distress call changes everything.").assertIsDisplayed()
        composeRule.onNodeWithText("Resume from 2:00").performClick()
        assertEquals(
            MediaPlaybackRequest.Resume(MediaIdentity("server", "episode-1"), 1_200_000_000, "user", "Dulcinea"),
            playback.submitted,
        )
        composeRule.onNodeWithText("Play from beginning").performClick()
        assertEquals(MediaPlaybackRequest.PlayFromBeginning(MediaIdentity("server", "episode-1"), "user", "Dulcinea"), playback.submitted)
    }

    @Test
    fun selected_show_season_and_episode_survive_process_state_restoration() {
        val restoration = StateRestorationTester(composeRule)
        val gateway = FakeSeriesGateway()
        restoration.setContent {
            ChaiChaiTheme(reducedMotion = false) { MobileApp(boundaries(gateway, FakeSeriesPlayback()), null) }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithText("The Expanse").performClick()
        composeRule.onNodeWithText("Season 1").performClick()
        composeRule.onNodeWithText("S1 E1  Dulcinea").performClick()
        composeRule.onNodeWithText("A distress call changes everything.").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("A distress call changes everything.").assertIsDisplayed()
        composeRule.onNodeWithText("Back to episodes").assertIsDisplayed()
    }

    @Test
    fun series_filters_sort_and_scroll_survive_process_state_restoration() {
        val shows = (0 until 100).map { MoviePoster(MediaIdentity("server", "show-$it"), "Show $it") }
        val gateway = FakeSeriesGateway(FakeSeriesGateway.readyState().copy(items = shows, totalCount = shows.size))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ChaiChaiTheme(reducedMotion = false) {
                    MobileApp(boundaries(gateway, FakeSeriesPlayback()), null, Modifier.requiredSize(700.dp, 700.dp))
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithText("Release date").performClick()
        composeRule.onNodeWithText("Ascending").performClick()
        composeRule.onNodeWithText("Drama").performClick()
        composeRule.onNodeWithTag("series-grid").performScrollToIndex(80)

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Show 0").assertDoesNotExist()
        composeRule.onNodeWithTag("series-grid").performScrollToIndex(0)
        composeRule.onNodeWithText("Release date").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
        composeRule.onNodeWithText("Descending").assertIsDisplayed()
        composeRule.onNodeWithText("Drama").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun folding_preserves_the_selected_series_journey() {
        val hinge = androidx.compose.runtime.mutableStateOf<SeparatingHinge?>(null)
        val gateway = FakeSeriesGateway()
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ChaiChaiTheme(reducedMotion = false) {
                    MobileApp(boundaries(gateway, FakeSeriesPlayback()), hinge.value, Modifier.requiredSize(840.dp, 700.dp))
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithText("The Expanse").performClick()
        composeRule.onNodeWithText("Season 1").assertIsDisplayed()

        composeRule.runOnIdle { hinge.value = SeparatingHinge(400, 0, 420, 700, HingeOrientation.Vertical) }
        composeRule.waitUntilAtLeastOneExists(hasText("Season 1"))
        composeRule.onNodeWithText("Season 1").assertIsDisplayed()
    }

    @Test
    fun expanded_width_keeps_show_collection_and_episode_context_visible_together() {
        val gateway = FakeSeriesGateway()
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ChaiChaiTheme(reducedMotion = false) {
                    MobileApp(boundaries(gateway, FakeSeriesPlayback()), null, Modifier.requiredSize(1000.dp, 700.dp))
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithText("The Expanse").performClick()
        composeRule.onNodeWithText("Season 1").performClick()

        composeRule.onAllNodesWithText("The Expanse").assertCountEquals(2)
        composeRule.onNodeWithText("S1 E1  Dulcinea").assertIsDisplayed()
    }

    @Test
    fun paging_failure_missing_seasons_and_large_text_remain_recoverable_and_accessible() {
        val base = FakeSeriesGateway.readyState().copy(pageFailureMessage = "Couldn't load more shows.")
        val gateway = FakeSeriesGateway(
            base,
            SeriesDetails(MediaIdentity("server", "expanse"), "The Expanse"),
        )
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChaiChaiTheme(reducedMotion = false) {
                    MobileApp(boundaries(gateway, FakeSeriesPlayback()), null, Modifier.requiredSize(360.dp, 640.dp))
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithTag("series-grid").performScrollToNode(hasText("Couldn't load more shows."))
        composeRule.onNodeWithText("Couldn't load more shows.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry page").assertHasClickAction()
        composeRule.onNodeWithText("The Expanse").assertHasClickAction().performClick()

        composeRule.onNodeWithText("No seasons available").assertIsDisplayed()
        composeRule.onNodeWithText("This show does not have season information from the server.").assertIsDisplayed()
    }

    @Test
    fun an_empty_season_keeps_series_context_and_explains_the_empty_result() {
        val gateway = FakeSeriesGateway(emptyEpisodes = true)
        composeRule.setContent { ChaiChaiTheme(reducedMotion = false) { MobileApp(boundaries(gateway, FakeSeriesPlayback()), null) } }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithText("The Expanse").performClick()
        composeRule.onNodeWithText("Season 1").performClick()

        composeRule.onNodeWithText("No episodes in Season 1").assertIsDisplayed()
        composeRule.onAllNodesWithText("The Expanse").assertCountEquals(2)
    }

    @Test
    fun live_resize_returns_to_the_list_then_restores_the_selected_series_on_expansion() {
        val supportsListDetail = androidx.compose.runtime.mutableStateOf(true)
        val gateway = FakeSeriesGateway()
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = false) {
                LibrariesScreen(
                    gateway, if (supportsListDetail.value) LibraryWindowClass.Expanded else LibraryWindowClass.Compact,
                    false, supportsListDetail.value, FakeSeriesPlayback(), {}, Modifier.requiredSize(1000.dp, 700.dp),
                )
            }
        }
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithText("The Expanse").performClick()
        composeRule.onNodeWithText("Season 1").assertIsDisplayed()

        composeRule.runOnIdle { supportsListDetail.value = false }
        composeRule.onNodeWithText("Season 1").assertDoesNotExist()
        composeRule.onNodeWithTag("series-grid").assertExists()

        composeRule.runOnIdle { supportsListDetail.value = true }
        composeRule.onNodeWithText("Season 1").assertIsDisplayed()
    }

    @Test
    fun medium_width_and_talkback_traversal_orders_multiple_named_episode_targets() {
        val gateway = FakeSeriesGateway()
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ChaiChaiTheme(reducedMotion = false) {
                    MobileApp(boundaries(gateway, FakeSeriesPlayback()), null, Modifier.requiredSize(700.dp, 700.dp))
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Shows").performClick()
        composeRule.onNodeWithText("The Expanse").performClick()
        composeRule.onNodeWithText("Season 1").performClick()

        composeRule.onNode(
            hasText("S1 E1  Dulcinea") and
                SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f),
        ).assertHasClickAction().assertIsDisplayed()
        composeRule.onNode(
            hasText("S1 E2  The Big Empty") and
                SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 2f),
        ).assertHasClickAction().assertIsDisplayed()
    }

    private fun boundaries(gateway: EmbyGateway, playback: PlaybackCoordinator) = AppBoundaries(
        gateway, playback, AppClock { Instant.EPOCH }, object : ConnectivityMonitor {
            override val isOnline = MutableStateFlow(true)
        },
    )

    private class FakeSeriesPlayback : PlaybackCoordinator {
        override val isPlaying = MutableStateFlow(false)
        var submitted: MediaPlaybackRequest? = null
        override fun submit(request: MediaPlaybackRequest) { submitted = request }
    }

    private class FakeSeriesGateway(
        initialLibrary: SeriesLibraryState = readyState(),
        private val seriesDetails: SeriesDetails = defaultSeriesDetails(),
        private val emptyEpisodes: Boolean = false,
    ) : EmbyGateway {
        override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
        override val seriesLibrary = MutableStateFlow(initialLibrary)
        override suspend fun refreshSeries(query: LibraryQuery) {
            val current = seriesLibrary.value as? SeriesLibraryState.Ready ?: return
            seriesLibrary.value = current.copy(query = query)
        }
        override suspend fun loadSeriesDetails(identity: MediaIdentity, authenticationReturnDestination: String?) =
            SeriesDetailsState.Ready(
                seriesDetails.copy(identity = identity),
            )
        override suspend fun loadSeasonEpisodes(seriesIdentity: MediaIdentity, seasonIdentity: MediaIdentity, authenticationReturnDestination: String?) =
            if (emptyEpisodes) SeasonEpisodesState.Empty(seriesIdentity, SeasonSummary(seasonIdentity, "Season 1", 1)) else SeasonEpisodesState.Ready(
                seriesIdentity, SeasonSummary(seasonIdentity, "Season 1", 1),
                listOf(
                    EpisodeSummary(
                        MediaIdentity("server", "episode-1"), "Dulcinea", "The Expanse", 1, 1,
                        "A distress call changes everything.", 25_200_000_000, 1_200_000_000,
                    ),
                    EpisodeSummary(
                        MediaIdentity("server", "episode-2"), "The Big Empty", "The Expanse", 1, 2,
                        "The crew faces a difficult choice.", 25_200_000_000,
                    ),
                ),
            )
        override suspend fun loadEpisodeDetails(identity: MediaIdentity, authenticationReturnDestination: String?) =
            EpisodeDetailsState.Ready(
                EpisodeDetails(
                    EpisodeSummary(
                        identity, "Dulcinea", "The Expanse", 1, 1,
                        "A distress call changes everything.", 25_200_000_000, 1_200_000_000,
                    ),
                    scope = HomeScope("server", "user"),
                ),
            )

        companion object {
            fun readyState() = SeriesLibraryState.Ready(
                HomeScope("server", "user"),
                listOf(MoviePoster(MediaIdentity("server", "expanse"), "The Expanse", 2015)),
                1,
                dev.chaichai.mobile.core.contracts.LibraryQuery(),
                listOf("Drama"),
            )

            fun defaultSeriesDetails() = SeriesDetails(
                MediaIdentity("server", "expanse"), "The Expanse", 2015,
                "Humanity has spread across the solar system.",
                seasons = listOf(SeasonSummary(MediaIdentity("server", "season-1"), "Season 1", 1)),
            )
        }
    }
}
