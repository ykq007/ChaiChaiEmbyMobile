package dev.chaichai.mobile

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
import dev.chaichai.mobile.core.contracts.SearchMediaType
import dev.chaichai.mobile.core.contracts.SearchResult
import dev.chaichai.mobile.core.contracts.SearchResultGroup
import dev.chaichai.mobile.core.contracts.SearchState
import dev.chaichai.mobile.core.contracts.SeasonEpisodesState
import dev.chaichai.mobile.core.contracts.SeasonSummary
import dev.chaichai.mobile.core.contracts.SeriesDetails
import dev.chaichai.mobile.core.contracts.SeriesDetailsState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AggregatedSearchTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun search_waits_for_two_trimmed_characters_and_debounces_superseded_input() {
        val gateway = FakeSearchGateway()
        showApp(gateway)
        composeRule.onNodeWithText("Search").performClick()
        val field = composeRule.onNodeWithTag("search-field")
        field.performTextInput(" a ")
        composeRule.runOnIdle { assertEquals(emptyList<String>(), gateway.queries) }

        field.performTextClearance()
        composeRule.mainClock.autoAdvance = false
        field.performTextInput(" ar ")
        composeRule.mainClock.advanceTimeBy(299)
        composeRule.runOnIdle { assertEquals(emptyList<String>(), gateway.queries) }
        composeRule.onNodeWithText("Searching for “ar”").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(1)
        composeRule.runOnIdle { assertEquals(listOf("ar"), gateway.queries) }
    }

    @Test
    fun every_group_is_accessible_and_movie_selection_uses_the_established_detail_route() {
        val gateway = FakeSearchGateway()
        showApp(gateway)
        composeRule.onNodeWithText("Search").performClick()
        enterQuery("ar")

        listOf("Movies", "Series", "Seasons", "Episodes").forEach {
            scrolledResult(it).assertIsDisplayed()
        }
        listOf(
            "Movies" to 0f,
            "Arrival" to 1f,
            "Series" to 1_000f,
            "The Expanse" to 1_001f,
            "Seasons" to 2_000f,
            "Season 1" to 2_001f,
            "Episodes" to 3_000f,
            "Dulcinea" to 3_001f,
        ).forEach { (text, index) ->
            scrolledResult(text)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, index))
        }
        scrolledResult("Arrival")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f))
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText("Language changes everything.").performScrollTo().assertExists()
    }

    @Test
    fun series_result_uses_the_established_series_detail_route() {
        val gateway = FakeSearchGateway()
        showApp(gateway)
        composeRule.onNodeWithText("Search").performClick()
        enterQuery("ar")
        scrolledResult("The Expanse").performClick()
        composeRule.onNodeWithTag("series-details").assertIsDisplayed()
    }

    @Test
    fun season_result_uses_the_established_hierarchy_details() {
        val gateway = FakeSearchGateway()
        showApp(gateway)
        composeRule.onNodeWithText("Search").performClick()
        enterQuery("ar")
        scrolledResult("Season 1").performClick()
        composeRule.onNodeWithText("S1 E1  Dulcinea").assertIsDisplayed()
    }

    @Test
    fun episode_result_uses_the_established_episode_detail_route() {
        val gateway = FakeSearchGateway()
        showApp(gateway)
        composeRule.onNodeWithText("Search").performClick()
        enterQuery("ar")
        scrolledResult("Dulcinea")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 3001f))
            .performClick()
        composeRule.onNodeWithTag("episode-details").assertIsDisplayed()
    }

    @Test
    @RequiresLargeTestWindow
    fun qualifying_hinge_panes_keep_results_and_details_clear_of_the_hinge() {
        val gateway = FakeSearchGateway()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ChaiChaiTheme(reducedMotion = false) {
                    MobileApp(
                        boundaries(gateway),
                        SeparatingHinge(400, 0, 420, 700, HingeOrientation.Vertical),
                        Modifier.requiredSize(840.dp, 700.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Search").performClick()
        enterQuery("ar")
        composeRule.onNodeWithTag("search-collection").assertWidthIsEqualTo(400.dp)
        composeRule.onNodeWithText("Arrival").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("movie-details-scroll").assertLeftPositionInRootIsEqualTo(420.dp)
    }

    @Test
    fun result_scroll_survives_process_state_restoration() {
        val movies = (0 until 100).map {
            SearchResult(scope, MediaIdentity("server", "movie-$it"), SearchMediaType.Movie, "Movie $it")
        }
        val gateway = FakeSearchGateway { query ->
            SearchState.Results(scope, query, listOf(SearchResultGroup(SearchMediaType.Movie, movies)))
        }
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            ChaiChaiTheme(reducedMotion = false) { MobileApp(boundaries(gateway), null) }
        }
        composeRule.onNodeWithText("Search").performClick()
        enterQuery("movie")
        composeRule.onNodeWithTag("search-results").performScrollToIndex(80)

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Movie 0").assertDoesNotExist()
    }

    @Test
    fun expanded_query_selection_and_detail_context_survive_state_restoration_and_large_text() {
        val restoration = StateRestorationTester(composeRule)
        val gateway = FakeSearchGateway()
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChaiChaiTheme(reducedMotion = false) {
                    MobileApp(boundaries(gateway), null, Modifier.requiredSize(1000.dp, 700.dp))
                }
            }
        }
        composeRule.onNodeWithText("Search").performClick()
        enterQuery("ar")
        composeRule.onNodeWithTag("search-results").performScrollToIndex(1)
        composeRule.onNodeWithText("Arrival").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Language changes everything.").performScrollTo().assertExists()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("search-field").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("ar")),
        )
        composeRule.onNodeWithText("Language changes everything.").performScrollTo().assertExists()
        composeRule.onNodeWithTag("search-results").assertExists()
    }

    @Test
    fun initial_no_results_and_recoverable_failure_are_distinct() {
        val gateway = FakeSearchGateway { query -> SearchState.Empty(scope, query) }
        showApp(gateway)
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithText("Find something to watch").assertIsDisplayed()
        enterQuery("none")
        composeRule.onNodeWithText("No results for “none”").assertIsDisplayed()

        composeRule.runOnIdle {
            gateway.searchState.value = SearchState.Failure("none", "Search couldn't be completed.", scope)
        }
        composeRule.onNodeWithText("Search unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Retry search").assertHasClickAction()
    }

    private fun showApp(gateway: FakeSearchGateway) {
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = false) { MobileApp(boundaries(gateway), null) }
        }
    }

    // Result rows live in the "search-results" LazyColumn, which only composes on-screen
    // items. A deep target (e.g. the last "Dulcinea" episode) may not exist in the semantics
    // tree yet, so `onNodeWithText(...).performScrollTo()` intermittently can't find it on the
    // slow large-window emulator. Scroll the list itself, which composes-and-scrolls to it.
    private fun scrolledResult(text: String): SemanticsNodeInteraction {
        composeRule.onNodeWithTag("search-results").performScrollToNode(hasText(text))
        return composeRule.onNodeWithText(text)
    }

    private fun enterQuery(query: String) {
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("search-field").performTextInput(query)
        composeRule.mainClock.advanceTimeBy(301)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search-field").performImeAction()
        // Results/empty content replaces the transient "Searching for …" placeholder only
        // once the gateway state's query catches up to the field. On slow emulators (the
        // large-window CI lane) that recomposition lags a frame, so callers that immediately
        // scroll to or assert on result rows would race it. Wait for the placeholder to clear.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Searching for", substring = true)
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun boundaries(gateway: EmbyGateway) = AppBoundaries(
        gateway,
        object : NoOpPlaybackCoordinator() {
            override val isPlaying = MutableStateFlow(false)
            override fun submit(request: MediaPlaybackRequest) = Unit
        },
        AppClock { Instant.EPOCH },
        object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
    )

    private class FakeSearchGateway(
        private val result: (String) -> SearchState = { query -> SearchState.Results(scope, query, groups()) },
    ) : EmbyGateway {
        override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
        override val searchState = MutableStateFlow<SearchState>(SearchState.Initial)
        val queries = mutableListOf<String>()

        override suspend fun search(query: String) {
            queries += query
            searchState.value = result(query)
        }

        override suspend fun loadMovieDetails(identity: MediaIdentity, authenticationReturnDestination: String?) =
            dev.chaichai.mobile.core.contracts.MovieDetailsState.Ready(
                dev.chaichai.mobile.core.contracts.MovieDetails(
                    identity,
                    "Arrival",
                    2016,
                    overview = "Language changes everything.",
                ),
            )

        override suspend fun loadSeriesDetails(identity: MediaIdentity, authenticationReturnDestination: String?) =
            SeriesDetailsState.Ready(
                SeriesDetails(
                    identity,
                    "The Expanse",
                    seasons = listOf(SeasonSummary(MediaIdentity(identity.serverId, "season"), "Season 1", 1)),
                ),
            )

        override suspend fun loadSeasonEpisodes(
            seriesIdentity: MediaIdentity,
            seasonIdentity: MediaIdentity,
            authenticationReturnDestination: String?,
        ) = SeasonEpisodesState.Ready(
            seriesIdentity,
            SeasonSummary(seasonIdentity, "Season 1", 1),
            listOf(EpisodeSummary(MediaIdentity(seriesIdentity.serverId, "episode"), "Dulcinea", "The Expanse", 1, 1)),
        )

        override suspend fun loadEpisodeDetails(identity: MediaIdentity, authenticationReturnDestination: String?) =
            EpisodeDetailsState.Ready(
                EpisodeDetails(
                    EpisodeSummary(identity, "Dulcinea", "The Expanse", 1, 1),
                    scope = scope,
                ),
            )
    }

    private companion object {
        val scope = HomeScope("server", "user")

        fun groups(): List<SearchResultGroup> {
            val series = MediaIdentity("server", "series")
            val season = MediaIdentity("server", "season")
            return listOf(
                SearchResultGroup(SearchMediaType.Movie, listOf(SearchResult(scope, MediaIdentity("server", "movie"), SearchMediaType.Movie, "Arrival", 2016))),
                SearchResultGroup(SearchMediaType.Series, listOf(SearchResult(scope, series, SearchMediaType.Series, "The Expanse", 2015))),
                SearchResultGroup(SearchMediaType.Season, listOf(SearchResult(scope, season, SearchMediaType.Season, "Season 1", seriesName = "The Expanse", seasonNumber = 1, seriesIdentity = series))),
                SearchResultGroup(SearchMediaType.Episode, listOf(SearchResult(scope, MediaIdentity("server", "episode"), SearchMediaType.Episode, "Dulcinea", seriesName = "The Expanse", seasonNumber = 1, episodeNumber = 1, seriesIdentity = series, seasonIdentity = season))),
            )
        }
    }
}
