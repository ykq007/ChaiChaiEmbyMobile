package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuController
import dev.chaichai.mobile.core.contracts.DanmakuOverlaySnapshot
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuUnavailableReason
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns danmaku configuration and the active comment track, and is the single place that contains
 * failure: EVERY endpoint call, match, decode and snapshot computation is wrapped so a thrown
 * exception becomes a [DanmakuState.Unavailable] status and never propagates into playback.
 *
 * Danmaku is disabled by default (see [DanmakuConfig]). Enabling it while media is attached kicks off
 * automatic matching across the user's independently named endpoints; the first endpoint that yields
 * a confident match wins and its time-indexed comment track is loaded.
 */
class DanmakuControllerImpl(
    private val scope: CoroutineScope,
    private val endpointClient: DanmakuEndpointClient,
    private val configStore: DanmakuConfigStore,
    private val matcher: DanmakuMatcher = DanmakuMatcher(),
) : DanmakuController {

    private val mutableState = MutableStateFlow<DanmakuState>(
        if (configStore.load().enabled) DanmakuState.Matching(NO_IDENTITY) else DanmakuState.Disabled,
    )
    override val state: StateFlow<DanmakuState> = mutableState

    private var attached: AttachedMedia? = null
    private var track: DanmakuCommentTrack? = null
    private var matchedEndpointName: String = ""
    private var matchedTitle: String = ""
    private var matchJob: Job? = null
    private var enabled: Boolean = configStore.load().enabled

    private data class AttachedMedia(
        val identity: MediaIdentity,
        val scope: HomeScope,
        val title: String,
        val runtimeTicks: Long,
    )

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        configStore.setEnabled(enabled)
        if (!enabled) {
            matchJob?.cancel()
            track = null
            mutableState.value = DanmakuState.Disabled
            return
        }
        startMatching()
    }

    override fun attach(identity: MediaIdentity, scope: HomeScope, title: String, runtimeTicks: Long) {
        attached = AttachedMedia(identity, scope, title, runtimeTicks)
        track = null
        if (enabled) startMatching()
    }

    override fun detach() {
        matchJob?.cancel()
        attached = null
        track = null
        mutableState.value = if (enabled) DanmakuState.Matching(NO_IDENTITY) else DanmakuState.Disabled
    }

    override fun onPlayback(positionTicks: Long, isPaused: Boolean, speed: Float) {
        if (!enabled) return
        val currentTrack = track ?: return
        // Rendering-side computation is contained too: a render failure never interrupts media.
        val snapshot = try {
            currentTrack.snapshotAt(positionTicks, isPaused, speed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutableState.value = DanmakuState.Unavailable(
                DanmakuUnavailableReason.RenderFailed,
                "Danmaku rendering was paused after an error. Playback is unaffected.",
            )
            track = null
            return
        }
        val active = mutableState.value as? DanmakuState.Active ?: DanmakuState.Active(
            endpointName = matchedEndpointName,
            matchedTitle = matchedTitle,
            totalComments = currentTrack.size,
            overlay = DanmakuOverlaySnapshot.Empty,
        )
        mutableState.value = active.copy(overlay = snapshot)
    }

    private fun startMatching() {
        val media = attached
        if (media == null) {
            mutableState.value = DanmakuState.Matching(NO_IDENTITY)
            return
        }
        val config = configStore.load()
        if (config.endpoints.isEmpty()) {
            mutableState.value = DanmakuState.Unavailable(
                DanmakuUnavailableReason.NoEndpointConfigured,
                "Add a danmaku endpoint in settings to load comments.",
            )
            return
        }
        matchJob?.cancel()
        track = null
        mutableState.value = DanmakuState.Matching(media.identity)
        matchJob = scope.launch {
            val query = DanmakuMatchQuery(title = media.title, runtimeTicks = media.runtimeTicks)
            var lastFailure: DanmakuState.Unavailable? = null
            for (endpoint in config.endpoints) {
                val result = runCatching {
                    val candidates = endpointClient.match(endpoint, query)
                    val candidate = matcher.bestMatch(query, candidates) ?: return@runCatching null
                    val comments = endpointClient.fetchComments(endpoint, candidate.mediaId)
                    Triple(endpoint, candidate, comments)
                }
                val outcome = result.getOrElse { error ->
                    if (error is CancellationException) throw error
                    lastFailure = DanmakuState.Unavailable(
                        DanmakuUnavailableReason.EndpointUnreachable,
                        "Couldn't reach danmaku endpoint \"${endpoint.name}\". Media is playing normally.",
                    )
                    null
                }
                if (outcome == null) {
                    if (lastFailure == null) {
                        lastFailure = DanmakuState.Unavailable(
                            DanmakuUnavailableReason.NoMatch,
                            "No danmaku match found for \"${media.title}\".",
                        )
                    }
                    continue
                }
                val (matchedEndpoint, candidate, comments) = outcome
                val built = runCatching { DanmakuCommentTrack(comments) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    lastFailure = DanmakuState.Unavailable(
                        DanmakuUnavailableReason.DecodeFailed,
                        "Danmaku comments from \"${matchedEndpoint.name}\" couldn't be read.",
                    )
                    null
                } ?: continue
                track = built
                matchedEndpointName = matchedEndpoint.name
                matchedTitle = candidate.title.ifBlank { media.title }
                mutableState.value = DanmakuState.Active(
                    endpointName = matchedEndpoint.name,
                    matchedTitle = matchedTitle,
                    totalComments = built.size,
                    overlay = DanmakuOverlaySnapshot.Empty,
                )
                return@launch
            }
            mutableState.value = lastFailure ?: DanmakuState.Unavailable(
                DanmakuUnavailableReason.NoMatch,
                "No danmaku match found for \"${media.title}\".",
            )
        }
    }

    private companion object {
        val NO_IDENTITY = MediaIdentity("", "")
    }
}
