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
    suspend fun refreshMovies(query: LibraryQuery = LibraryQuery()) = Unit
    suspend fun loadNextMoviePage() = Unit
    suspend fun retryMoviePage() = loadNextMoviePage()
    suspend fun loadMovieDetails(
        identity: MediaIdentity,
        authenticationReturnDestination: String? = null,
    ): MovieDetailsState =
        MovieDetailsState.Failure("Movie details couldn't be loaded.")
    val seriesLibrary: StateFlow<SeriesLibraryState>
        get() = EmptySeriesLibrary.flow
    suspend fun refreshSeries(query: LibraryQuery = LibraryQuery()) = Unit
    suspend fun loadNextSeriesPage() = Unit
    suspend fun retrySeriesPage() = loadNextSeriesPage()
    suspend fun loadSeriesDetails(
        identity: MediaIdentity,
        authenticationReturnDestination: String? = null,
    ): SeriesDetailsState = SeriesDetailsState.Failure("Series details couldn't be loaded.")
    suspend fun loadSeasonEpisodes(
        seriesIdentity: MediaIdentity,
        seasonIdentity: MediaIdentity,
        authenticationReturnDestination: String? = null,
    ): SeasonEpisodesState = SeasonEpisodesState.Failure("Episodes couldn't be loaded.")
    suspend fun loadEpisodeDetails(
        identity: MediaIdentity,
        authenticationReturnDestination: String? = null,
    ): EpisodeDetailsState = EpisodeDetailsState.Failure("Episode details couldn't be loaded.")
    val searchState: StateFlow<SearchState>
        get() = EmptySearch.flow
    suspend fun search(query: String) = Unit
}
enum class GatewayConnectionState { Disconnected, Connected }
enum class GatewayAuthenticationStatus { Valid, Expired, Unavailable }
interface PlaybackCoordinator {
    val isPlaying: StateFlow<Boolean>
    val state: StateFlow<PlaybackState>
    fun submit(request: MediaPlaybackRequest)
    fun toggleControls()
    fun playPause()
    fun seekBy(deltaTicks: Long)
    fun seekTo(positionTicks: Long)
    fun selectTrack(selection: PlaybackTrackSelection) = Unit
    fun setPlaybackSpeed(speed: Float) = Unit
    fun setSubtitleDelay(deltaMillis: Long) = Unit
    fun retry()
    fun retryProgressSync() = Unit
    fun exit()
}

/**
 * Server-user scoped playback speed and media-scoped subtitle delay preferences.
 * Speed persists per [HomeScope] (applies across media for that user/server); subtitle delay
 * persists per [MediaIdentity] only (never globally). Default no-op bodies keep existing
 * constructions (fakes, tests) compiling without a real persistence implementation.
 */
interface PlaybackPreferences {
    fun speedFor(scope: HomeScope): Float = 1.0f
    fun setSpeed(scope: HomeScope, speed: Float) = Unit
    fun subtitleDelayFor(identity: MediaIdentity): Long = 0L
    fun setSubtitleDelay(identity: MediaIdentity, delayMillis: Long) = Unit
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

enum class SearchMediaType(val title: String) {
    Movie("Movies"),
    Series("Series"),
    Season("Seasons"),
    Episode("Episodes"),
}

data class SearchResult(
    val scope: HomeScope,
    val identity: MediaIdentity,
    val mediaType: SearchMediaType,
    val title: String,
    val year: Int? = null,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val seriesIdentity: MediaIdentity? = null,
    val seasonIdentity: MediaIdentity? = null,
    val artwork: ArtworkReference? = null,
)

data class SearchResultGroup(
    val mediaType: SearchMediaType,
    val items: List<SearchResult>,
)

/** Per-server outcome of one fanned-out Aggregated Search query (issue #29). */
enum class ServerSearchOutcome { Ok, Empty, Failed, TimedOut }

/**
 * One configured server's contribution to an Aggregated Search query: which server, its
 * user-facing name, and how it fared. Carried on [SearchState.Results] only when more than one
 * server was queried, so single-server search keeps its pre-#29 shape untouched.
 */
data class ServerSearchStatus(
    val serverId: String,
    val displayName: String,
    val outcome: ServerSearchOutcome,
)

sealed interface SearchState {
    data object Initial : SearchState
    data class Searching(
        val query: String,
        val restoredGroups: List<SearchResultGroup> = emptyList(),
    ) : SearchState
    data class Results(
        val scope: HomeScope,
        val query: String,
        val groups: List<SearchResultGroup>,
        /**
         * Per-server search provenance for this query. Empty for single-server search (unchanged
         * rendering); populated with one entry per queried server when Aggregated Search (#29)
         * fanned out across more than one configured server, so a partial failure stays visible
         * alongside the results that did arrive.
         */
        val serverStatuses: List<ServerSearchStatus> = emptyList(),
    ) : SearchState
    data class Empty(val scope: HomeScope, val query: String) : SearchState
    data class Failure(
        val query: String,
        val message: String,
        val scope: HomeScope? = null,
        val restoredGroups: List<SearchResultGroup> = emptyList(),
    ) : SearchState
}

enum class LibrarySortField(val label: String) {
    Name("Name"),
    DateAdded("Date added"),
    ReleaseDate("Release date"),
}

enum class SortDirection(val label: String) {
    Ascending("Ascending"),
    Descending("Descending"),
}

data class LibraryQuery(
    val sortField: LibrarySortField = LibrarySortField.Name,
    val sortDirection: SortDirection = SortDirection.Ascending,
    val genre: String? = null,
)
typealias MovieLibraryQuery = LibraryQuery
typealias MovieSortField = LibrarySortField

data class MediaPoster(
    val identity: MediaIdentity,
    val title: String,
    val year: Int? = null,
    val artwork: ArtworkReference? = null,
)
typealias MoviePoster = MediaPoster

sealed interface MovieLibraryState {
    data object Loading : MovieLibraryState
    data class Ready(
        val scope: HomeScope,
        val items: List<MoviePoster>,
        val totalCount: Int,
        val query: LibraryQuery,
        val availableGenres: List<String> = emptyList(),
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val pageFailureMessage: String? = null,
        val refreshFailureMessage: String? = null,
    ) : MovieLibraryState
    data class EmptyLibrary(
        val scope: HomeScope,
        val query: LibraryQuery = LibraryQuery(),
        val availableGenres: List<String> = emptyList(),
    ) : MovieLibraryState
    data class EmptyFiltered(
        val scope: HomeScope,
        val query: LibraryQuery,
        val availableGenres: List<String> = emptyList(),
    ) : MovieLibraryState
    data class Failure(
        val message: String,
        val scope: HomeScope? = null,
        val query: LibraryQuery = LibraryQuery(),
    ) : MovieLibraryState
}

data class MediaTrackAvailability(val audioTracks: Int = 0, val subtitleTracks: Int = 0)
typealias MovieTrackAvailability = MediaTrackAvailability

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
    val scope: HomeScope? = null,
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

typealias SeriesPoster = MediaPoster

sealed interface SeriesLibraryState {
    data object Loading : SeriesLibraryState
    data class Ready(
        val scope: HomeScope,
        val items: List<SeriesPoster>,
        val totalCount: Int,
        val query: LibraryQuery,
        val availableGenres: List<String> = emptyList(),
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val pageFailureMessage: String? = null,
        val refreshFailureMessage: String? = null,
    ) : SeriesLibraryState
    data class EmptyLibrary(val scope: HomeScope, val query: LibraryQuery = LibraryQuery(), val availableGenres: List<String> = emptyList()) : SeriesLibraryState
    data class EmptyFiltered(val scope: HomeScope, val query: LibraryQuery, val availableGenres: List<String> = emptyList()) : SeriesLibraryState
    data class Failure(val message: String, val scope: HomeScope? = null, val query: LibraryQuery = LibraryQuery()) : SeriesLibraryState
}

data class SeasonSummary(val identity: MediaIdentity, val name: String, val indexNumber: Int? = null)

data class SeriesDetails(
    val identity: MediaIdentity,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val artwork: ArtworkReference? = null,
    val backdrop: ArtworkReference? = null,
    val seasons: List<SeasonSummary> = emptyList(),
)

sealed interface SeriesDetailsState {
    data class Ready(val details: SeriesDetails) : SeriesDetailsState
    data class Failure(val message: String) : SeriesDetailsState
}

data class EpisodeSummary(
    val identity: MediaIdentity,
    val title: String,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val overview: String? = null,
    val runtimeTicks: Long? = null,
    val playbackPositionTicks: Long = 0,
    val played: Boolean = false,
    val artwork: ArtworkReference? = null,
) {
    val hasMeaningfulResume: Boolean
        get() = hasMeaningfulResume(playbackPositionTicks, runtimeTicks, played)
}

sealed interface SeasonEpisodesState {
    data class Ready(val seriesIdentity: MediaIdentity, val season: SeasonSummary, val episodes: List<EpisodeSummary>) : SeasonEpisodesState
    data class Empty(val seriesIdentity: MediaIdentity, val season: SeasonSummary) : SeasonEpisodesState
    data class Failure(val message: String) : SeasonEpisodesState
}

data class EpisodeDetails(
    val episode: EpisodeSummary,
    val communityRating: Double? = null,
    val criticRating: Double? = null,
    val genres: List<String> = emptyList(),
    val tracks: MediaTrackAvailability = MediaTrackAvailability(),
    val backdrop: ArtworkReference? = null,
    val scope: HomeScope,
)

sealed interface EpisodeDetailsState {
    data class Ready(val details: EpisodeDetails) : EpisodeDetailsState
    data class Failure(val message: String) : EpisodeDetailsState
}

sealed interface MediaPlaybackRequest {
    val identity: MediaIdentity
    val scope: HomeScope
    data class Resume(
        override val identity: MediaIdentity,
        val positionTicks: Long,
        override val scope: HomeScope,
        val title: String = "",
    ) : MediaPlaybackRequest
    data class PlayFromBeginning(
        override val identity: MediaIdentity,
        override val scope: HomeScope,
        val title: String = "",
    ) : MediaPlaybackRequest
}

enum class PlaybackFailureKind(val canRetry: Boolean) {
    UnsupportedMedia(false),
    TranscodingRefused(false),
    SourceUnavailable(true),
    AuthorizationExpired(false),
    Network(true),
}

enum class PlaybackTrackType { Audio, Subtitle }
enum class TrackDelivery { Embedded, External, BurnIn }
enum class TrackQualifier { HearingImpaired, Commentary, VisuallyImpaired }

data class PlaybackTrack(
    val index: Int,
    val type: PlaybackTrackType,
    val language: String? = null,
    val codec: String? = null,
    val title: String? = null,
    val delivery: TrackDelivery = TrackDelivery.Embedded,
    val isDefault: Boolean = false,
    val isCurrent: Boolean = false,
    val qualifiers: List<TrackQualifier> = emptyList(),
)

data class PlaybackTrackSelection(
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
) {
    companion object {
        fun subtitleOff(audioStreamIndex: Int?) = PlaybackTrackSelection(audioStreamIndex, null)
    }
}

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Negotiating(val title: String) : PlaybackState
    data class Active(
        val identity: MediaIdentity,
        val title: String,
        val positionTicks: Long,
        val runtimeTicks: Long,
        val isPaused: Boolean,
        val controlsVisible: Boolean = true,
        val audioTracks: List<PlaybackTrack> = emptyList(),
        val subtitleTracks: List<PlaybackTrack> = emptyList(),
        val isChangingTrack: Boolean = false,
        val trackChangeError: String? = null,
        val progressSync: PlaybackProgressSync = PlaybackProgressSync.Synced,
        val playbackSpeed: Float = 1.0f,
        val subtitleDelayMillis: Long = 0L,
        val speedControlSupported: Boolean = false,
        val subtitleDelaySupported: Boolean = false,
        val scope: HomeScope? = null,
        val seriesIdentity: MediaIdentity? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    ) : PlaybackState
    data class Failed(val reason: PlaybackFailureKind) : PlaybackState
    data class Exited(val identity: MediaIdentity) : PlaybackState
}

sealed interface PlaybackProgressSync {
    data object Synced : PlaybackProgressSync
    data object Pending : PlaybackProgressSync
    data class Failed(val message: String) : PlaybackProgressSync
}

typealias MoviePlaybackRequest = MediaPlaybackRequest

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

private object EmptySeriesLibrary {
    val flow: StateFlow<SeriesLibraryState> = kotlinx.coroutines.flow.MutableStateFlow(SeriesLibraryState.Loading)
}

private object EmptySearch {
    val flow: StateFlow<SearchState> = kotlinx.coroutines.flow.MutableStateFlow(SearchState.Initial)
}

data class AppBoundaries(
    val gateway: EmbyGateway,
    val playback: PlaybackCoordinator,
    val clock: AppClock,
    val connectivity: ConnectivityMonitor,
    val serverSetup: ServerSetupBoundary? = null,
    val homeMediaActions: HomeMediaActionBoundary = HomeMediaActionBoundary {},
    val account: AccountBoundary? = null,
    val danmaku: DanmakuController? = null,
    val serverDirectory: ServerDirectory? = null,
    val serverProxy: ServerProxyBoundary? = null,
    val danmakuEndpoints: DanmakuEndpointBoundary? = null,
)
