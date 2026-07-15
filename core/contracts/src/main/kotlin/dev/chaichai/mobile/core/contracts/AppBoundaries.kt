package dev.chaichai.mobile.core.contracts

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

interface EmbyGateway {
    val connectionState: StateFlow<GatewayConnectionState>
    val homeFeed: StateFlow<HomeFeedState>
        get() = EmptyHomeFeed.flow
    suspend fun verifyAuthentication(requestedDestination: String? = null): GatewayAuthenticationStatus =
        GatewayAuthenticationStatus.Unavailable
    suspend fun refreshHome() = Unit
    suspend fun retryHomeSection(section: HomeSection) = refreshHome()
    suspend fun loadArtwork(artwork: ArtworkReference): ByteArray? = null
    val movieLibrary: StateFlow<MovieLibraryState>
        get() = EmptyMovieLibrary.flow
    suspend fun refreshMovies(query: MovieLibraryQuery = MovieLibraryQuery()) = Unit
    suspend fun loadNextMoviePage() = Unit
    suspend fun retryMoviePage() = loadNextMoviePage()
    suspend fun loadMovieDetails(
        identity: MediaIdentity,
        authenticationReturnDestination: String? = null,
    ): MovieDetailsState =
        MovieDetailsState.Failure("Movie details couldn't be loaded.")
}
enum class GatewayConnectionState { Disconnected, Connected }
enum class GatewayAuthenticationStatus { Valid, Expired, Unavailable }
interface PlaybackCoordinator {
    val isPlaying: StateFlow<Boolean>
    fun submit(request: MoviePlaybackRequest) = Unit
}
fun interface AppClock { fun now(): Instant }
interface ConnectivityMonitor { val isOnline: StateFlow<Boolean> }

enum class HomeSection(val title: String) {
    ContinueWatching("Continue Watching"),
    NextUp("Next Up"),
    LatestMovies("Latest Movies"),
    LatestEpisodes("Latest Episodes"),
    AccessibleLibraries("Libraries"),
}

data class HomeScope(val serverId: String, val userId: String)

data class MediaIdentity(val serverId: String, val itemId: String)

enum class MovieSortField(val label: String) {
    Name("Name"),
    DateAdded("Date added"),
    ReleaseDate("Release date"),
}

enum class SortDirection(val label: String) {
    Ascending("Ascending"),
    Descending("Descending"),
}

data class MovieLibraryQuery(
    val sortField: MovieSortField = MovieSortField.Name,
    val sortDirection: SortDirection = SortDirection.Ascending,
    val genre: String? = null,
)

data class MoviePoster(
    val identity: MediaIdentity,
    val title: String,
    val year: Int? = null,
    val artwork: ArtworkReference? = null,
)

sealed interface MovieLibraryState {
    data object Loading : MovieLibraryState
    data class Ready(
        val scope: HomeScope,
        val items: List<MoviePoster>,
        val totalCount: Int,
        val query: MovieLibraryQuery,
        val availableGenres: List<String> = emptyList(),
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val pageFailureMessage: String? = null,
        val refreshFailureMessage: String? = null,
    ) : MovieLibraryState
    data class EmptyLibrary(
        val scope: HomeScope,
        val query: MovieLibraryQuery = MovieLibraryQuery(),
        val availableGenres: List<String> = emptyList(),
    ) : MovieLibraryState
    data class EmptyFiltered(
        val scope: HomeScope,
        val query: MovieLibraryQuery,
        val availableGenres: List<String> = emptyList(),
    ) : MovieLibraryState
    data class Failure(
        val message: String,
        val scope: HomeScope? = null,
        val query: MovieLibraryQuery = MovieLibraryQuery(),
    ) : MovieLibraryState
}

data class MovieTrackAvailability(val audioTracks: Int = 0, val subtitleTracks: Int = 0)

data class MovieDetails(
    val identity: MediaIdentity,
    val title: String,
    val year: Int? = null,
    val runtimeTicks: Long? = null,
    val communityRating: Double? = null,
    val criticRating: Double? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    val playbackPositionTicks: Long = 0,
    val played: Boolean = false,
    val tracks: MovieTrackAvailability = MovieTrackAvailability(),
    val artwork: ArtworkReference? = null,
    val backdrop: ArtworkReference? = null,
) {
    val hasMeaningfulResume: Boolean
        get() = hasMeaningfulResume(playbackPositionTicks, runtimeTicks, played)
}

fun hasMeaningfulResume(positionTicks: Long, runtimeTicks: Long?, played: Boolean = false): Boolean =
    !played && positionTicks >= 10 * HomeMediaItem.TicksPerSecond &&
        (runtimeTicks == null || runtimeTicks - positionTicks >= 60 * HomeMediaItem.TicksPerSecond)

sealed interface MovieDetailsState {
    data class Ready(val details: MovieDetails) : MovieDetailsState
    data class Failure(val message: String) : MovieDetailsState
}

sealed interface MoviePlaybackRequest {
    val identity: MediaIdentity
    data class Resume(override val identity: MediaIdentity, val positionTicks: Long) : MoviePlaybackRequest
    data class PlayFromBeginning(override val identity: MediaIdentity) : MoviePlaybackRequest
}

enum class ArtworkKind(val routeName: String) { Primary("Primary"), Backdrop("Backdrop") }

data class ArtworkReference(
    val identity: MediaIdentity,
    val imageTag: String,
    val kind: ArtworkKind = ArtworkKind.Primary,
)

data class HomeMediaItem(
    val identity: MediaIdentity,
    val title: String,
    val mediaType: String,
    val subtitle: String? = null,
    val playbackPositionTicks: Long = 0,
    val runtimeTicks: Long? = null,
    val artwork: ArtworkReference? = null,
    val backdrop: ArtworkReference? = null,
) {
    val hasMeaningfulResume: Boolean
        get() = hasMeaningfulResume(playbackPositionTicks, runtimeTicks)

    companion object {
        const val TicksPerSecond = 10_000_000L
    }
}

data class HomeSectionContent(
    val items: List<HomeMediaItem>,
    val failureMessage: String? = null,
    val isStale: Boolean = false,
)

sealed interface HomeFeedState {
    data object Loading : HomeFeedState
    data class Ready(
        val scope: HomeScope,
        val sections: Map<HomeSection, HomeSectionContent>,
        val isRefreshing: Boolean = false,
    ) : HomeFeedState
    data class Empty(val scope: HomeScope) : HomeFeedState
    data class Failure(val message: String, val scope: HomeScope? = null) : HomeFeedState
}

sealed interface HomeMediaAction {
    val identity: MediaIdentity
    data class OpenDetails(override val identity: MediaIdentity) : HomeMediaAction
    data class Resume(override val identity: MediaIdentity, val positionTicks: Long) : HomeMediaAction
}

fun interface HomeMediaActionBoundary { fun submit(action: HomeMediaAction) }

private object EmptyHomeFeed {
    val flow: StateFlow<HomeFeedState> = kotlinx.coroutines.flow.MutableStateFlow(HomeFeedState.Loading)
}

private object EmptyMovieLibrary {
    val flow: StateFlow<MovieLibraryState> = kotlinx.coroutines.flow.MutableStateFlow(MovieLibraryState.Loading)
}

data class AppBoundaries(
    val gateway: EmbyGateway,
    val playback: PlaybackCoordinator,
    val clock: AppClock,
    val connectivity: ConnectivityMonitor,
    val serverSetup: ServerSetupBoundary? = null,
    val homeMediaActions: HomeMediaActionBoundary = HomeMediaActionBoundary {},
)
