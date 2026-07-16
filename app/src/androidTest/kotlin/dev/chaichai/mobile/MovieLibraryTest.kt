package dev.chaichai.mobile

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import dev.chaichai.mobile.core.contracts.LibraryQuery
import dev.chaichai.mobile.core.contracts.MovieLibraryState
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.LibrarySortField
import dev.chaichai.mobile.core.contracts.MovieTrackAvailability
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.SortDirection
import dev.chaichai.mobile.core.contracts.ServerSetupBoundary
import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.libraries.LibrariesScreen
import dev.chaichai.mobile.feature.libraries.LibraryWindowClass
import dev.chaichai.mobile.feature.libraries.MovieDetailsScreen
import dev.chaichai.mobile.feature.libraries.MovieLibrarySelectionSaver
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
            MediaPlaybackRequest.Resume(MediaIdentity("server", "arrival"), 1_200_000_000, HomeScope("server", "user"), "Arrival"),
            playback.submitted,
        )
        composeRule.onNodeWithText("Play from beginning").performClick()
        assertEquals(MediaPlaybackRequest.PlayFromBeginning(MediaIdentity("server", "arrival"), HomeScope("server", "user"), "Arrival"), playback.submitted)
    }

    @Test
    fun failed_next_page_keeps_posters_and_retries_inline() {
        val gateway = FakeMovieGateway(ready().copy(pageFailureMessage = "Couldn't load more movies."))
        showLibrary(gateway)

        composeRule.onNodeWithText("Arrival").assertIsDisplayed()
        // The page-failure message and its Retry button are a footer item at the bottom of
        // the LazyVerticalGrid; scroll them into view so assertion/click don't race the
        // grid composing that trailing item on slow emulators.
        composeRule.onNodeWithTag("movie-grid").performScrollToNode(hasText("Couldn't load more movies."))
        composeRule.onNodeWithText("Couldn't load more movies.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry page").performClick()

        composeRule.onNodeWithTag("movie-grid").performScrollToNode(hasText("Recovered"))
        composeRule.onNodeWithText("Recovered").assertIsDisplayed()
        composeRule.onNodeWithText("Arrival").assertIsDisplayed()
    }

    @Test
    fun library_selection_survives_saved_state_and_expanded_list_detail_transition() {
        val restoration = StateRestorationTester(composeRule)
        val gateway = FakeMovieGateway(ready())
        restoration.setContent {
            var selection by androidx.compose.runtime.saveable.rememberSaveable(
                stateSaver = MovieLibrarySelectionSaver,
            ) { androidx.compose.runtime.mutableStateOf<MediaIdentity?>(null) }
            themed {
                LibrariesScreen(
                    gateway, LibraryWindowClass.Expanded, false, true, FakePlayback(), {},
                    initialSelection = selection,
                    onSelectionChanged = { selection = it },
                )
            }
        }
        composeRule.onNodeWithText("Arrival").performClick()
        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Arrival").assertCountEquals(2)
    }

    @Test
    @RequiresLargeTestWindow
    fun hinge_separated_panes_keep_collection_and_restored_details_clear_of_the_hinge() {
        val restoration = StateRestorationTester(composeRule)
        val gateway = FakeMovieGateway(ready())
        restoration.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                themed {
                    MobileApp(
                        appBoundaries(gateway, FakePlayback()),
                        SeparatingHinge(400, 0, 420, 700, HingeOrientation.Vertical),
                        Modifier.requiredSize(840.dp, 700.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithTag("movie-grid").assertWidthIsEqualTo(400.dp)
        composeRule.onNodeWithText("Arrival").performClick()
        composeRule.onNodeWithTag("movie-details-scroll")
            .assertLeftPositionInRootIsEqualTo(420.dp)
            .assertWidthIsEqualTo(420.dp)

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onAllNodesWithText("Arrival").assertCountEquals(2)
        composeRule.onNodeWithTag("movie-details-scroll").assertLeftPositionInRootIsEqualTo(420.dp)
    }

    @Test
    @RequiresLargeTestWindow
    fun live_hinge_transition_keeps_the_selected_movie_journey() {
        val gateway = FakeMovieGateway(ready())
        val hinge = androidx.compose.runtime.mutableStateOf<SeparatingHinge?>(null)
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                themed {
                    MobileApp(
                        appBoundaries(gateway, FakePlayback()),
                        hinge.value,
                        Modifier.requiredSize(840.dp, 700.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Arrival").performClick()
        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()

        composeRule.runOnIdle {
            hinge.value = SeparatingHinge(400, 0, 420, 700, HingeOrientation.Vertical)
        }

        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()
        composeRule.onNodeWithTag("movie-details-scroll").assertLeftPositionInRootIsEqualTo(420.dp)
    }

    @Test
    fun live_hinge_transition_keeps_the_library_scroll_journey() {
        val movies = (0 until 100).map {
            MoviePoster(MediaIdentity("server", "movie-$it"), "Movie $it")
        }
        val gateway = FakeMovieGateway(ready().copy(items = movies, totalCount = movies.size))
        val hinge = androidx.compose.runtime.mutableStateOf<SeparatingHinge?>(null)
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                themed {
                    MobileApp(
                        appBoundaries(gateway, FakePlayback()),
                        hinge.value,
                        Modifier.requiredSize(840.dp, 700.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithTag("movie-grid").performScrollToIndex(80)

        composeRule.runOnIdle {
            hinge.value = SeparatingHinge(400, 0, 420, 700, HingeOrientation.Vertical)
        }

        composeRule.onNodeWithText("Movie 0").assertDoesNotExist()
    }

    @Test
    fun expanded_list_detail_selection_preserves_the_collection_scroll_position() {
        val movies = (0 until 100).map {
            MoviePoster(MediaIdentity("server", "movie-$it"), "Movie $it", 2000 + it)
        }
        val gateway = FakeMovieGateway(
            MovieLibraryState.Ready(
                HomeScope("server", "user"), movies, movies.size,
                LibraryQuery(LibrarySortField.Name, SortDirection.Ascending),
            ),
            details().copy(identity = movies[80].identity, title = movies[80].title),
        )
        composeRule.setContent {
            themed {
                LibrariesScreen(
                    gateway, LibraryWindowClass.Expanded, false, true, FakePlayback(), {},
                    modifier = Modifier.requiredSize(840.dp, 700.dp),
                )
            }
        }
        composeRule.onNodeWithTag("movie-grid").performScrollToIndex(80)
        composeRule.onNodeWithText("Movie 80").performClick()

        composeRule.onNodeWithText("Movie 80").assertIsDisplayed()
        composeRule.onNodeWithText("Movie 0").assertDoesNotExist()
    }

    @Test
    fun hinge_panes_below_either_minimum_keep_the_single_safe_pane_layout() {
        val gateway = FakeMovieGateway(ready())
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                themed {
                    MobileApp(
                        appBoundaries(gateway, FakePlayback()),
                        SeparatingHinge(350, 0, 370, 700, HingeOrientation.Vertical),
                        Modifier.requiredSize(729.dp, 700.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()

        composeRule.onNodeWithTag("movie-grid").assertWidthIsEqualTo(359.dp)
        composeRule.onNodeWithText("Arrival").performClick()
        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()
    }

    @Test
    fun compact_selection_emits_exactly_one_navigation_request() {
        val gateway = FakeMovieGateway(ready())
        var navigations = 0
        composeRule.setContent {
            themed {
                LibrariesScreen(
                    gateway, LibraryWindowClass.Compact, false, false, FakePlayback(),
                    { navigations += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Arrival").performClick()

        composeRule.runOnIdle { assertEquals(1, navigations) }
    }

    @Test
    fun reauthentication_return_destination_restores_the_server_scoped_movie_details() {
        val gateway = FakeMovieGateway(ready())
        composeRule.setContent {
            themed {
                MobileApp(
                    appBoundaries(gateway, FakePlayback()).copy(
                        serverSetup = RestoredServerSetup("movies/server/arrival"),
                    ),
                    separatingHinge = null,
                )
            }
        }

        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()
    }

    @Test
    @RequiresLargeTestWindow
    fun inline_selection_survives_the_real_signed_out_and_reauthenticated_ui_transition() {
        val gateway = FakeMovieGateway(ready())
        val setup = RestoredServerSetup()
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                themed {
                    MobileApp(
                        appBoundaries(gateway, FakePlayback()).copy(serverSetup = setup),
                        separatingHinge = null,
                        modifier = Modifier.requiredSize(1000.dp, 700.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Arrival").performClick()
        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()

        composeRule.runOnIdle {
            gateway.movieLibrary.value = MovieLibraryState.Loading
            setup.signOut()
        }
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        composeRule.runOnIdle {
            gateway.movieLibrary.value = ready()
            setup.restore("libraries")
        }

        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()
    }

    @Test
    fun same_server_different_user_reauthentication_clears_the_prior_private_selection() {
        val gateway = FakeMovieGateway(ready())
        val setup = RestoredServerSetup()
        composeRule.setContent {
            themed {
                MobileApp(
                    appBoundaries(gateway, FakePlayback()).copy(serverSetup = setup),
                    separatingHinge = null,
                )
            }
        }
        composeRule.onNodeWithText("Libraries").performClick()
        composeRule.onNodeWithText("Arrival").performClick()
        composeRule.onNodeWithText("Language changes everything.").assertIsDisplayed()

        composeRule.runOnIdle {
            gateway.movieLibrary.value = MovieLibraryState.Loading
            setup.signOut()
        }
        composeRule.runOnIdle {
            gateway.movieLibrary.value = ready().copy(scope = HomeScope("server", "other-user"))
            setup.restore("libraries")
        }

        composeRule.onNodeWithText("Libraries").assertIsDisplayed()
        composeRule.onNodeWithText("Language changes everything.").assertDoesNotExist()
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
                LibraryQuery(LibrarySortField.ReleaseDate, SortDirection.Descending, "Drama"),
                (gateway.movieLibrary.value as MovieLibraryState.Ready).query,
            )
        }
    }

    @Test
    fun library_empty_filtered_empty_and_initial_failure_have_distinct_recovery_copy() {
        val emptyQuery = LibraryQuery(LibrarySortField.DateAdded, SortDirection.Descending)
        val gateway = FakeMovieGateway(MovieLibraryState.EmptyLibrary(HomeScope("server", "user"), emptyQuery))
        showLibrary(gateway)
        composeRule.onNodeWithText("No movies in this library").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(emptyQuery, gateway.lastRefreshQuery) }

        composeRule.runOnIdle {
            gateway.movieLibrary.value = MovieLibraryState.EmptyFiltered(
                HomeScope("server", "user"), LibraryQuery(genre = "Drama"), listOf("Drama"),
            )
        }
        composeRule.onNodeWithText("No matching movies").assertIsDisplayed()

        composeRule.runOnIdle {
            gateway.movieLibrary.value = MovieLibraryState.Failure("Server offline", query = LibraryQuery(genre = "Drama"))
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

    @Test
    fun near_tail_pagination_replays_when_cached_refresh_finishes_without_count_changes() {
        val movies = (0 until 80).map { MoviePoster(MediaIdentity("server", "movie-$it"), "Movie $it") }
        val gateway = FakeMovieGateway(
            ready().copy(items = movies, totalCount = 100, isRefreshing = true),
        )
        showLibrary(gateway)
        composeRule.onNodeWithTag("movie-grid").performScrollToIndex(79)
        composeRule.runOnIdle { assertEquals(0, gateway.nextPageRequests) }

        composeRule.runOnIdle {
            val state = gateway.movieLibrary.value as MovieLibraryState.Ready
            gateway.movieLibrary.value = state.copy(isRefreshing = false)
        }

        composeRule.runOnIdle { assertEquals(1, gateway.nextPageRequests) }
    }

    private fun showApp(gateway: EmbyGateway, playback: PlaybackCoordinator) {
        composeRule.setContent {
            themed {
                MobileApp(
                    appBoundaries(gateway, playback),
                    separatingHinge = null,
                )
            }
        }
    }

    private fun appBoundaries(gateway: EmbyGateway, playback: PlaybackCoordinator) = AppBoundaries(
        gateway,
        playback,
        AppClock { Instant.EPOCH },
        object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
    )

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
        var lastRefreshQuery: LibraryQuery? = null
        var nextPageRequests = 0
        override suspend fun refreshMovies(query: LibraryQuery) {
            lastRefreshQuery = query
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
        override suspend fun loadNextMoviePage() {
            nextPageRequests += 1
        }
        override suspend fun loadMovieDetails(identity: MediaIdentity, authenticationReturnDestination: String?) =
            MovieDetailsState.Ready(movieDetails.copy(identity = identity))
    }

    private class FakePlayback : NoOpPlaybackCoordinator() {
        override val isPlaying = MutableStateFlow(false)
        var submitted: MediaPlaybackRequest? = null
        override fun submit(request: MediaPlaybackRequest) { submitted = request }
    }

    private class RestoredServerSetup(returnDestination: String? = null) : ServerSetupBoundary {
        override val state = MutableStateFlow<ServerSetupState>(
            ServerSetupState.Authenticated("Cinema", "Ada", returnDestination),
        )
        override fun submitAddress(address: String) = Unit
        override fun acceptCleartextRisk() = Unit
        override fun acceptCertificateBypass() = Unit
        override fun confirmServer() = Unit
        override fun authenticate(username: String, password: String) = Unit
        override fun retry() = Unit
        override fun authenticationExpired(requestedDestination: String?) = Unit
        fun signOut() {
            state.value = ServerSetupState.SignIn("https://media.example", "Cinema", "Ada")
        }
        fun restore(returnDestination: String?) {
            state.value = ServerSetupState.Authenticated("Cinema", "Ada", returnDestination)
        }
    }

    private companion object {
        fun ready() = MovieLibraryState.Ready(
            HomeScope("server", "user"),
            listOf(MoviePoster(MediaIdentity("server", "arrival"), "Arrival", 2016)),
            2,
            LibraryQuery(LibrarySortField.Name, SortDirection.Ascending),
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
            scope = HomeScope("server", "user"),
        )
    }
}
