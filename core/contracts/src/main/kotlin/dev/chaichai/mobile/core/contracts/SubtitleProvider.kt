package dev.chaichai.mobile.core.contracts

import kotlinx.coroutines.flow.StateFlow

/**
 * Subtitle Expansion (issue #32). Finding, selecting and activating subtitles from user-configured
 * online providers during playback. This file is the entire NARROW boundary the feature UI talks to;
 * the real provider I/O lives in `:platform:subtitles` (which reuses `:platform:proxy` for routing and
 * Keystore-protected credentials and, like `:platform:danmaku`, never depends on `:platform:server`,
 * so provider traffic can never inherit a Server Address's Certificate Bypass).
 *
 * Out of scope for this milestone (see CONTEXT.md Subtitle Expansion): font-file upload and
 * cross-device settings synchronization.
 */

/**
 * Per-provider routing choice, mirroring [DanmakuEndpointRouting]: a provider connects directly unless
 * the user selects an explicit [Proxy] override reusing the exact #30 [ServerProxyConfig]. Carries NO
 * secret — proxy credentials live only in the Keystore-protected vault, keyed by provider id.
 */
sealed interface SubtitleProviderRouting {
    data object Direct : SubtitleProviderRouting
    data class Proxy(val config: ServerProxyConfig) : SubtitleProviderRouting
}

/**
 * A configured subtitle provider as the settings UI sees it. Non-secret: [hasCredentials] records only
 * whether provider account credentials exist in the Keystore vault, never the username/password.
 * [routing]'s optional proxy config likewise records only whether proxy credentials exist. [id] is
 * stable across renames so credentials and routing stay bound to the right provider.
 *
 * There are NO embedded provider defaults: a fresh install has an empty provider list, so nothing
 * contacts any provider until the user adds one.
 */
data class SubtitleProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val hasCredentials: Boolean = false,
    val routing: SubtitleProviderRouting = SubtitleProviderRouting.Direct,
)

/**
 * One subtitle candidate returned by a provider search, carrying its PROVENANCE ([providerId]/
 * [providerName]) alongside enough metadata to choose confidently: [language] (+ optional
 * human-readable [languageLabel]), the [releaseName]/[matchInfo] describing which release it matches,
 * the subtitle [format], an opaque [downloadHint] the provider client uses to fetch it, and an
 * optional [matchScore] and [hearingImpaired] flag.
 */
data class SubtitleCandidate(
    val id: String,
    val providerId: String,
    val providerName: String,
    val language: String,
    val languageLabel: String? = null,
    val releaseName: String? = null,
    val matchInfo: String? = null,
    val format: String = "srt",
    val downloadHint: String = "",
    val matchScore: Double? = null,
    val hearingImpaired: Boolean = false,
)

/** How one provider fared during a fanned-out search, so a partial failure stays visible (AC5). */
enum class SubtitleProviderOutcome { Ok, Empty, Failed, TimedOut, AuthFailed }

/** One provider's contribution to a search: which provider, its name, and how it fared. */
data class SubtitleProviderStatus(
    val providerId: String,
    val providerName: String,
    val outcome: SubtitleProviderOutcome,
)

/** Extra hints for a provider search, derived from the active media (title/language/episode/runtime). */
data class SubtitleSearchHints(
    val title: String = "",
    val language: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeTicks: Long? = null,
)

/**
 * The provider search/selection state the in-player panel renders. [Results] carries the candidates,
 * the per-provider [providerStatuses] (partial results stay visible), an [activatingCandidateId] for
 * the download+activate spinner, and a contained [activationError]: when a download fails or the
 * subtitle is incompatible the prior subtitle stays active and this message is shown (AC4).
 */
sealed interface SubtitleSearchState {
    data object Idle : SubtitleSearchState
    data class Searching(val identity: MediaIdentity) : SubtitleSearchState
    data class Results(
        val identity: MediaIdentity,
        val candidates: List<SubtitleCandidate>,
        val providerStatuses: List<SubtitleProviderStatus> = emptyList(),
        val activatingCandidateId: String? = null,
        val activatedCandidateId: String? = null,
        val activationError: String? = null,
    ) : SubtitleSearchState
    data class Failure(val message: String, val identity: MediaIdentity? = null) : SubtitleSearchState
}

/**
 * Narrow controller the in-player UI drives to find and activate provider subtitles. All provider and
 * download failures are contained here and mapped into [SubtitleSearchState]; nothing thrown from a
 * provider ever reaches playback. Selecting a candidate downloads it and activates it as the current
 * External subtitle track WITHOUT restarting playback or losing position/paused state — see
 * [PlaybackCoordinator.addExternalSubtitle]. Attached to [AppBoundaries] as nullable so its absence is
 * a no-op for existing wiring.
 */
interface SubtitleProviderController {
    val searchState: StateFlow<SubtitleSearchState>

    /** Search every enabled provider for [identity] using [hints]. Fans out with per-provider status. */
    fun searchForCurrentMedia(identity: MediaIdentity, scope: HomeScope, hints: SubtitleSearchHints = SubtitleSearchHints())

    /** Download [candidateId] and activate it as the current External subtitle without a restart. */
    fun selectCandidate(candidateId: String)

    /** Cancel any in-flight search/download so superseded results can never land. */
    fun cancelSearch()
}

/**
 * Narrow boundary for configuring subtitle providers and their PROTECTED credentials + optional
 * per-provider Proxy Routing. Android-free; the settings feature talks only to this. Provider account
 * credentials only ever travel *in* via [updateCredentials]/[updateRouting] and are stored in the
 * Keystore-protected vault behind this boundary — never surfaced back or echoed into diagnostics.
 */
interface SubtitleProviderBoundary {
    fun providers(): List<SubtitleProviderConfig>
    fun addProvider(name: String, baseUrl: String): String
    fun renameProvider(id: String, name: String)
    fun updateBaseUrl(id: String, baseUrl: String)
    fun setEnabled(id: String, enabled: Boolean)
    fun removeProvider(id: String)

    /**
     * Store or clear [id]'s provider account credentials. A non-null [credentials] (re)stores the
     * secret in the vault; a null [credentials] clears it.
     */
    fun updateCredentials(id: String, credentials: ProxyCredentials?)

    /** Set [id]'s proxy routing; proxy [credentials] handling mirrors [DanmakuEndpointBoundary.updateRouting]. */
    fun updateRouting(id: String, routing: SubtitleProviderRouting, credentials: ProxyCredentials? = null)

    /** Run the distinguished connection test for [id]'s configured route. */
    suspend fun testProvider(id: String): ProxyTestResult
}

/**
 * A downloaded provider subtitle handed to [PlaybackCoordinator.addExternalSubtitle] for no-restart
 * activation. [track] is the External [PlaybackTrack] that becomes current; [localRef] is a local
 * file/URI reference to the downloaded subtitle bytes; [mimeType] identifies the subtitle format for
 * the player.
 */
data class ExternalSubtitleActivation(
    val track: PlaybackTrack,
    val localRef: String,
    val mimeType: String,
) {
    companion object {
        /**
         * The reserved synthetic [PlaybackTrack.index] used for an activated provider subtitle. It sits
         * far above any real server stream index so it never collides, and lets the coordinator tell a
         * "keep the external subtitle" selection apart from a real server-track selection.
         */
        const val SubtitleStreamIndex: Int = 1_000_000
    }
}
