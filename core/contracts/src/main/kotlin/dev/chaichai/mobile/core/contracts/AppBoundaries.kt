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
}
enum class GatewayConnectionState { Disconnected, Connected }
enum class GatewayAuthenticationStatus { Valid, Expired, Unavailable }
interface PlaybackCoordinator { val isPlaying: StateFlow<Boolean> }
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
        get() = playbackPositionTicks >= MeaningfulResumeTicks &&
            (runtimeTicks == null || runtimeTicks - playbackPositionTicks >= MeaningfulRemainingTicks)

    companion object {
        const val TicksPerSecond = 10_000_000L
        private const val MeaningfulResumeTicks = 10 * TicksPerSecond
        private const val MeaningfulRemainingTicks = 60 * TicksPerSecond
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

data class AppBoundaries(
    val gateway: EmbyGateway,
    val playback: PlaybackCoordinator,
    val clock: AppClock,
    val connectivity: ConnectivityMonitor,
    val serverSetup: ServerSetupBoundary? = null,
    val homeMediaActions: HomeMediaActionBoundary = HomeMediaActionBoundary {},
)
