package dev.chaichai.mobile

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MovieDetails
import dev.chaichai.mobile.core.contracts.MovieDetailsState
import dev.chaichai.mobile.core.contracts.MovieLibraryQuery
import dev.chaichai.mobile.core.contracts.MovieLibraryState
import dev.chaichai.mobile.core.contracts.MoviePlaybackRequest
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.MovieSortField
import dev.chaichai.mobile.core.contracts.MovieTrackAvailability
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.SortDirection
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.libraries.LibrariesScreen
import dev.chaichai.mobile.feature.libraries.LibraryWindowClass
import dev.chaichai.mobile.feature.libraries.MovieDetailsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class MovieLibraryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun selecting_a_movie_opens_details_then_resume_or_play_is_an_explicit_decision() {
        val gateway = FakeMovieGateway(ready())
        val playback = FakePlayback()
        showApp(gateway, playback)

        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Arrival").performClick()

        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()
        composeRule.onNodeWithText("2 audio tracks").assertIsDisplayed()
        composeRule.onNodeWithText("1 subtitle track").assertIsDisplayed()
        composeRule.onNodeWithText("Resume from 2:00").performClick()
        assertEquals(
            MoviePlaybackRequest.Resume(MediaIdentity("server", "arrival"), 1_200_000_000),
            playback.submitted,
        )
        composeRule.onNodeWithText("Play from beginning").performClick()
        assertEquals(MoviePlaybackRequest.PlayFromBeginning(MediaIdentity("server", "arrival")), playback.submitted)
    }

    @Test
    fun failed_next_page_keeps_posters_and_retries_inline() {
        val gateway = FakeMovieGateway(ready().copy(pageFailureMessage = "Couldn't load more movies."))
        showLibrary(gateway)

        composeRule.onNodeWithText("Arrival").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't load more movies.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry page").performClick()

        composeRule.onNodeWithText("Recovered").assertIsDisplayed()
        composeRule.onNodeWithText("Arrival").assertIsDisplayed()
    }

    @Test
    fun library_selection_survives_saved_state_and_expanded_list_detail_transition() {
        val restoration = StateRestorationTester(composeRule)
        val gateway = FakeMovieGateway(ready())
        restoration.setContent {
            themed { LibrariesScreen(gateway, LibraryWindowClass.Expanded, false, true, FakePlayback(), {}) }
        }
        composeRule.onNodeWithText("Arrival").performClick()
        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Arrival").assertCountEquals(2)
    }

    @Test
    fun missing_metadata_reflows_at_large_text_in_a_constrained_window() {
        val gateway = FakeMovieGateway(
            ready(),
            details().copy(
                year = null,
                runtimeTicks = null,
                overview = null,
                genres = emptyList(),
                playbackPositionTicks = 0,
                tracks = MovieTrackAvailability(),
            ),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                themed {
                    MovieDetailsScreen(
                        gateway,
                        MediaIdentity("server", "arrival"),
                        FakePlayback(),
                        LibraryWindowClass.Compact,
                        true,
                        Modifier.requiredSize(360.dp, 440.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("movie-details-scroll").performScrollToNode(hasText("Play"))
        composeRule.onNodeWithText("Play").assertIsDisplayed()
    }

    @Test
    fun sort_direction_and_genre_controls_submit_the_selected_query() {
        val gateway = FakeMovieGateway(ready())
        showLibrary(gateway)

        composeRule.onNodeWithText("Release date").performClick()
        composeRule.onNodeWithTag("movie-sort-controls").performScrollToNode(hasText("Ascending"))
        composeRule.onNodeWithText("Ascending").performClick()
        composeRule.onNodeWithTag("movie-genre-controls").performScrollToNode(hasText("Drama"))
        composeRule.onNodeWithText("Drama").performClick()

        composeRule.runOnIdle {
            assertEquals(
                MovieLibraryQuery(MovieSortField.ReleaseDate, SortDirection.Descending, "Drama"),
                (gateway.movieLibrary.value as MovieLibraryState.Ready).query,
            )
        }
    }

    @Test
    fun library_empty_filtered_empty_and_initial_failure_have_distinct_recovery_copy() {
        val gateway = FakeMovieGateway(MovieLibraryState.EmptyLibrary(HomeScope("server", "user")))
        showLibrary(gateway)
        composeRule.onNodeWithText("No movies in this library").assertIsDisplayed()

        composeRule.runOnIdle {
            gateway.movieLibrary.value = MovieLibraryState.EmptyFiltered(
                HomeScope("server", "user"), MovieLibraryQuery(genre = "Drama"), listOf("Drama"),
            )
        }
        composeRule.onNodeWithText("No matching movies").assertIsDisplayed()

        composeRule.runOnIdle {
            gateway.movieLibrary.value = MovieLibraryState.Failure("Server offline", query = MovieLibraryQuery(genre = "Drama"))
        }
        composeRule.onNodeWithText("Movies unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Server offline").assertIsDisplayed()
    }

    @Test
    fun movie_grid_scroll_survives_saved_state_restoration() {
        val restoration = StateRestorationTester(composeRule)
        val movies = (0..70).map { MoviePoster(MediaIdentity("server", "movie-$it"), "Movie $it") }
        val gateway = FakeMovieGateway(ready().copy(items = movies, totalCount = movies.size))
        restoration.setContent {
            themed { LibrariesScreen(gateway, LibraryWindowClass.Compact, false, false, FakePlayback(), {}) }
        }
        composeRule.onNodeWithTag("movie-grid").performScrollToNode(hasText("Movie 60"))
        composeRule.onNodeWithText("Movie 60").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Movie 60").assertIsDisplayed()
    }

    private fun showApp(gateway: EmbyGateway, playback: PlaybackCoordinator) {
        composeRule.setContent {
            themed {
                MobileApp(
                    AppBoundaries(
                        gateway,
                        playback,
                        AppClock { Instant.EPOCH },
                        object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
                    ),
                    separatingHinge = null,
                )
            }
        }
    }

    private fun showLibrary(gateway: EmbyGateway) {
        composeRule.setContent {
            themed { LibrariesScreen(gateway, LibraryWindowClass.Compact, false, false, FakePlayback(), {}) }
        }
    }

    @Composable private fun themed(content: @Composable () -> Unit) = ChaiChaiTheme(reducedMotion = true, content = content)

    private class FakeMovieGateway(
        initial: MovieLibraryState,
        private val movieDetails: MovieDetails = details(),
    ) : EmbyGateway {
        override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
        override val movieLibrary = MutableStateFlow<MovieLibraryState>(initial)
        override suspend fun refreshMovies(query: MovieLibraryQuery) {
            val ready = movieLibrary.value as? MovieLibraryState.Ready ?: return
            movieLibrary.value = ready.copy(query = query)
        }
        override suspend fun retryMoviePage() {
            val ready = movieLibrary.value as MovieLibraryState.Ready
            movieLibrary.value = ready.copy(
                items = ready.items + MoviePoster(MediaIdentity("server", "recovered"), "Recovered"),
                totalCount = ready.items.size + 1,
                pageFailureMessage = null,
            )
        }
        override suspend fun loadMovieDetails(identity: MediaIdentity) = MovieDetailsState.Ready(movieDetails.copy(identity = identity))
    }

    private class FakePlayback : PlaybackCoordinator {
        override val isPlaying = MutableStateFlow(false)
        var submitted: MoviePlaybackRequest? = null
        override fun submit(request: MoviePlaybackRequest) { submitted = request }
    }

    private companion object {
        fun ready() = MovieLibraryState.Ready(
            HomeScope("server", "user"),
            listOf(MoviePoster(MediaIdentity("server", "arrival"), "Arrival", 2016)),
            2,
            MovieLibraryQuery(MovieSortField.Name, SortDirection.Ascending),
            listOf("Drama", "Science Fiction"),
        )
        fun details() = MovieDetails(
            identity = MediaIdentity("server", "arrival"),
            title = "Arrival",
            year = 2016,
            runtimeTicks = 69_600_000_000,
            genres = listOf("Drama", "Science Fiction"),
            overview = "Language changes everything.",
            playbackPositionTicks = 1_200_000_000,
            tracks = MovieTrackAvailability(2, 1),
        )
    }
}
