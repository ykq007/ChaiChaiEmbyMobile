package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuController
import dev.chaichai.mobile.core.contracts.DanmakuMatchOption
import dev.chaichai.mobile.core.contracts.DanmakuMatchOptions
import dev.chaichai.mobile.core.contracts.DanmakuMediaKey
import dev.chaichai.mobile.core.contracts.DanmakuOverlaySnapshot
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuTuning
import dev.chaichai.mobile.core.contracts.DanmakuUnavailableReason
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val MILLIS_TO_TICKS = 10_000L

/**
 * Owns danmaku configuration and the active comment track, and is the single place that contains
 * failure: EVERY endpoint call, match, decode and snapshot computation is wrapped so a thrown
 * exception becomes a [DanmakuState.Unavailable] status and never propagates into playback.
 *
 * Danmaku is disabled by default (see [DanmakuConfig]). Enabling it while media is attached kicks off
 * automatic matching across the user's independently named endpoints; the first endpoint that yields
 * a confident match wins and its time-indexed comment track is loaded. A previously remembered manual
 * match for the attached scope (see [DanmakuMediaKey]) is tried first and, on success, skips
 * automatic matching entirely.
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

    private val mutableMatchOptions = MutableStateFlow(DanmakuMatchOptions())
    override val matchOptions: StateFlow<DanmakuMatchOptions> = mutableMatchOptions

    private val mutableTuning = MutableStateFlow(DanmakuTuning.Neutral)
    override val tuning: StateFlow<DanmakuTuning> = mutableTuning

    private var attached: AttachedMedia? = null
    private var track: DanmakuCommentTrack? = null
    private var matchedEndpointName: String = ""
    private var matchedTitle: String = ""
    private var matchJob: Job? = null
    private var searchJob: Job? = null
    private var enabled: Boolean = configStore.load().enabled

    private var lastPositionTicks: Long = 0L
    private var lastPaused: Boolean = false
    private var lastSpeed: Float = 1f

    /** candidateId -> the endpoint/candidate pair it refers to, from the most recent search. */
    private var candidateIndex: Map<String, Pair<DanmakuEndpoint, DanmakuMatchCandidate>> = emptyMap()

    private data class AttachedMedia(
        val identity: MediaIdentity,
        val scope: HomeScope,
        val title: String,
        val runtimeTicks: Long,
        val key: DanmakuMediaKey,
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

    override fun attach(
        identity: MediaIdentity,
        scope: HomeScope,
        title: String,
        runtimeTicks: Long,
        mediaKey: DanmakuMediaKey?,
    ) {
        val key = mediaKey ?: DanmakuMediaKey.Movie(identity.serverId, identity.itemId)
        attached = AttachedMedia(identity, scope, title, runtimeTicks, key)
        track = null
        lastPositionTicks = 0L
        lastPaused = false
        lastSpeed = 1f
        mutableTuning.value = configStore.tuning(key)
        mutableMatchOptions.value = DanmakuMatchOptions()
        candidateIndex = emptyMap()
        if (enabled) startMatching()
    }

    override fun detach() {
        matchJob?.cancel()
        searchJob?.cancel()
        attached = null
        track = null
        candidateIndex = emptyMap()
        mutableMatchOptions.value = DanmakuMatchOptions()
        mutableState.value = if (enabled) DanmakuState.Matching(NO_IDENTITY) else DanmakuState.Disabled
    }

    /**
     * The danmaku endpoint set or an endpoint's routing changed. If danmaku is enabled we re-run
     * matching against the current endpoints so a route change takes effect immediately — this only
     * ever reloads danmaku (a contained, isolated operation) and NEVER touches media playback. When
     * disabled or nothing is attached it is a cheap no-op beyond refreshing the matching state.
     */
    fun onEndpointsChanged() {
        if (enabled) startMatching()
    }

    override fun onPlayback(positionTicks: Long, isPaused: Boolean, speed: Float) {
        if (!enabled) return
        lastPositionTicks = positionTicks
        lastPaused = isPaused
        lastSpeed = speed
        recomputeOverlay()
    }

    override fun searchMatches(query: String) {
        val config = configStore.load()
        if (config.endpoints.isEmpty()) {
            searchJob?.cancel()
            mutableMatchOptions.value = DanmakuMatchOptions(
                query = query,
                error = "Add a danmaku endpoint in settings to search.",
            )
            return
        }
        searchJob?.cancel()
        mutableMatchOptions.value = DanmakuMatchOptions(query = query, isSearching = true)
        searchJob = scope.launch {
            val matchQuery = DanmakuMatchQuery(title = query, runtimeTicks = attached?.runtimeTicks ?: 0L)
            val options = mutableListOf<DanmakuMatchOption>()
            val index = mutableMapOf<String, Pair<DanmakuEndpoint, DanmakuMatchCandidate>>()
            var anySucceeded = false
            var lastError: String? = null
            for (endpoint in config.endpoints) {
                val result = runCatching { endpointClient.match(endpoint, matchQuery) }
                result.onSuccess { candidates ->
                    anySucceeded = true
                    for (candidate in candidates) {
                        val id = candidateId(endpoint.name, candidate.mediaId)
                        index[id] = endpoint to candidate
                        options += DanmakuMatchOption(
                            candidateId = id,
                            title = candidate.title.ifBlank { query },
                            season = candidate.season,
                            episode = candidate.episode,
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    lastError = "Couldn't reach \"${endpoint.name}\"."
                }
            }
            candidateIndex = index
            mutableMatchOptions.value = DanmakuMatchOptions(
                query = query,
                isSearching = false,
                results = options,
                error = if (!anySucceeded && lastError != null) lastError else null,
            )
        }
    }

    override fun selectMatch(candidateId: String) {
        val (endpoint, candidate) = candidateIndex[candidateId] ?: return
        val media = attached ?: return
        matchJob?.cancel()
        mutableState.value = DanmakuState.Matching(media.identity)
        matchJob = scope.launch {
            val result = runCatching { endpointClient.fetchComments(endpoint, candidate.mediaId) }
            val comments = result.getOrElse { error ->
                if (error is CancellationException) throw error
                mutableState.value = DanmakuState.Unavailable(
                    DanmakuUnavailableReason.EndpointUnreachable,
                    "Couldn't reach danmaku endpoint \"${endpoint.name}\". Media is playing normally.",
                )
                return@launch
            }
            val built = runCatching { DanmakuCommentTrack(comments) }.getOrElse { error ->
                if (error is CancellationException) throw error
                mutableState.value = DanmakuState.Unavailable(
                    DanmakuUnavailableReason.DecodeFailed,
                    "Danmaku comments from \"${endpoint.name}\" couldn't be read.",
                )
                return@launch
            }
            track = built
            matchedEndpointName = endpoint.name
            matchedTitle = candidate.title.ifBlank { media.title }
            configStore.rememberMatch(
                media.key,
                DanmakuRememberedMatch(endpoint.name, candidate.mediaId, matchedTitle),
            )
            mutableState.value = DanmakuState.Active(
                endpointName = matchedEndpointName,
                matchedTitle = matchedTitle,
                totalComments = built.size,
                overlay = DanmakuOverlaySnapshot.Empty,
                tuning = mutableTuning.value,
            )
            recomputeOverlay()
        }
    }

    override fun clearMatch() {
        val media = attached ?: return
        configStore.clearMatch(media.key)
        if (enabled) startMatching()
    }

    override fun updateTuning(tuning: DanmakuTuning) {
        mutableTuning.value = tuning
        attached?.let { configStore.setTuning(it.key, tuning) }
        recomputeOverlay()
    }

    /** Recomputes the visible-comment snapshot from the last known playback params and tuning. */
    private fun recomputeOverlay() {
        val currentTrack = track ?: return
        val currentTuning = mutableTuning.value
        val offsetTicks = currentTuning.timingOffsetMillis * MILLIS_TO_TICKS
        val adjustedPosition = (lastPositionTicks + offsetTicks).coerceAtLeast(0L)
        val effectiveSpeed = (lastSpeed * currentTuning.speed).let { if (it <= 0f) 1f else it }
        val snapshot = try {
            currentTrack.snapshotAt(adjustedPosition, lastPaused, effectiveSpeed, currentTuning.screenFraction)
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
        val filtered = snapshot.copy(
            visible = snapshot.visible.filter { it.comment.position in currentTuning.allowedPositions },
        )
        val active = mutableState.value as? DanmakuState.Active ?: DanmakuState.Active(
            endpointName = matchedEndpointName,
            matchedTitle = matchedTitle,
            totalComments = currentTrack.size,
            overlay = DanmakuOverlaySnapshot.Empty,
            tuning = currentTuning,
        )
        mutableState.value = active.copy(overlay = filtered, tuning = currentTuning)
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
        val remembered = configStore.rememberedMatch(media.key)
        matchJob = scope.launch {
            if (remembered != null) {
                val rememberedEndpoint = config.endpoints.firstOrNull { it.name == remembered.endpointName }
                if (rememberedEndpoint != null && loadRememberedMatch(media, rememberedEndpoint, remembered)) {
                    return@launch
                }
                // Remembered endpoint missing or its fetch failed: fall through to automatic matching.
            }
            runAutomaticMatching(media, config.endpoints)
        }
    }

    /** Returns true if the remembered match loaded successfully and state/track were set. */
    private suspend fun loadRememberedMatch(
        media: AttachedMedia,
        endpoint: DanmakuEndpoint,
        remembered: DanmakuRememberedMatch,
    ): Boolean {
        val result = runCatching { endpointClient.fetchComments(endpoint, remembered.mediaId) }
        val comments = result.getOrElse { error ->
            if (error is CancellationException) throw error
            return false
        }
        val built = runCatching { DanmakuCommentTrack(comments) }.getOrNull() ?: return false
        track = built
        matchedEndpointName = endpoint.name
        matchedTitle = remembered.title.ifBlank { media.title }
        mutableState.value = DanmakuState.Active(
            endpointName = matchedEndpointName,
            matchedTitle = matchedTitle,
            totalComments = built.size,
            overlay = DanmakuOverlaySnapshot.Empty,
            tuning = mutableTuning.value,
        )
        recomputeOverlay()
        return true
    }

    private suspend fun runAutomaticMatching(media: AttachedMedia, endpoints: List<DanmakuEndpoint>) {
        val query = DanmakuMatchQuery(title = media.title, runtimeTicks = media.runtimeTicks)
        var lastFailure: DanmakuState.Unavailable? = null
        for (endpoint in endpoints) {
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
                tuning = mutableTuning.value,
            )
            recomputeOverlay()
            return
        }
        mutableState.value = lastFailure ?: DanmakuState.Unavailable(
            DanmakuUnavailableReason.NoMatch,
            "No danmaku match found for \"${media.title}\".",
        )
    }

    private fun candidateId(endpointName: String, mediaId: String): String = "$endpointName $mediaId"

    private companion object {
        val NO_IDENTITY = MediaIdentity("", "")
    }
}
