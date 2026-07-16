package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.ExternalSubtitleActivation
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.TrackDelivery
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackPreferences
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackProgressSync
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection
import dev.chaichai.mobile.core.contracts.SubtitleAppearance
import dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.PlaybackFailure
import dev.chaichai.mobile.platform.server.PlaybackGateway
import dev.chaichai.mobile.platform.server.PlaybackNegotiationResult
import dev.chaichai.mobile.platform.server.PlaybackReport
import dev.chaichai.mobile.platform.server.PlaybackReportKind
import dev.chaichai.mobile.platform.server.PlaybackStart
import dev.chaichai.mobile.platform.server.ScopedPlaybackRequest
import dev.chaichai.mobile.platform.server.ProgressAwarePlaybackGateway
import dev.chaichai.mobile.platform.server.ProgressSyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

interface PlaybackEngine {
    val snapshot: PlaybackEngineSnapshot
    val positionTicks: Long
        get() = snapshot.positionTicks
    val isPaused: Boolean
        get() = snapshot.isPaused
    val events: Flow<PlaybackEngineEvent> get() = emptyFlow()
    /** Whether this engine can change playback speed without a restart. Gates the speed control in the UI. */
    val speedControlSupported: Boolean get() = false
    /** Whether this engine can apply a subtitle timing offset without a restart. Gates the subtitle-delay control. */
    val subtitleDelaySupported: Boolean get() = false
    /** Whether this engine can apply subtitle appearance (size/position/color/edge/opacity) live. Gates the appearance controls. */
    val subtitleAppearanceSupported: Boolean get() = false
    suspend fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long, startPaused: Boolean = false)
    suspend fun acknowledgePlayingReported()
    suspend fun playPause()
    suspend fun seekTo(positionTicks: Long)
    suspend fun setSpeed(speed: Float) = Unit
    suspend fun setSubtitleDelayMs(delayMs: Long) = Unit
    /** Apply subtitle appearance to the currently rendered captions without restarting playback. */
    suspend fun setSubtitleAppearance(appearance: SubtitleAppearance) = Unit
    /**
     * Side-load a downloaded external subtitle ([localRef]) and make it the current subtitle track,
     * preserving the current position and paused/playing state (no renegotiation, no restart from 0).
     * Default no-op so JVM fakes stay simple; the Media3 engine re-prepares in place at the current
     * position. May throw if the subtitle is incompatible, so the caller can contain the failure.
     */
    suspend fun applyExternalSubtitle(localRef: String, mimeType: String, language: String?) = Unit
    suspend fun stop()
}
data class PlaybackEngineSnapshot(val positionTicks: Long, val isPaused: Boolean)
sealed interface PlaybackEngineEvent {
    data object Ready : PlaybackEngineEvent
    data object Completed : PlaybackEngineEvent
    data object FatalError : PlaybackEngineEvent
    data class Progress(
        val event: dev.chaichai.mobile.platform.server.PlaybackProgressEvent,
        val positionTicks: Long,
        val isPaused: Boolean,
    ) : PlaybackEngineEvent
    data class Stopped(val positionTicks: Long, val isPaused: Boolean) : PlaybackEngineEvent
}

class PlaybackCoordinatorImpl(
    private val scope: CoroutineScope,
    private val gateway: PlaybackGateway,
    private val engine: PlaybackEngine,
    private val capabilities: PlaybackCapabilities,
    private val scheduleTimelineUpdates: Boolean = true,
    private val preferences: PlaybackPreferences = object : PlaybackPreferences {},
) : PlaybackCoordinator, AutoCloseable {
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = mutableState
    private val mutableIsPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = mutableIsPlaying
    private var activePlan: AuthoritativePlaybackPlan? = null
    private var lastRequest: MediaPlaybackRequest? = null
    private var timelineJob: Job? = null
    private var negotiationJob: Job? = null
    private var exiting = false
    private var terminalAfterStop: PlaybackState? = null
    private var pendingRequest: MediaPlaybackRequest? = null
    private var trackChangeJob: Job? = null
    private var pendingTrackTransition: PendingTrackTransition? = null
    private var progressStatusJob: Job? = null
    private var currentSpeed: Float = 1.0f
    private var currentSubtitleDelayMillis: Long = 0L
    private var currentSubtitleAppearance: SubtitleAppearance = SubtitleAppearance.Default
    private var activeExternalSubtitle: PlaybackTrack? = null

    private val engineEventJob = scope.launch {
            engine.events.collect { event ->
                when (event) {
                    PlaybackEngineEvent.Ready -> confirmTrackTransition()
                    PlaybackEngineEvent.Completed -> exit()
                    PlaybackEngineEvent.FatalError -> recoverTrackTransitionOrFail()
                    is PlaybackEngineEvent.Progress -> reportServiceProgress(event)
                    is PlaybackEngineEvent.Stopped -> finishServiceStop(event)
                }
            }
    }

    override fun close() {
        engineEventJob.cancel()
        progressStatusJob?.cancel()
        timelineJob?.cancel()
        negotiationJob?.cancel()
        trackChangeJob?.cancel()
        pendingTrackTransition = null
    }

    override fun submit(request: MediaPlaybackRequest) {
        trackChangeJob?.cancel()
        pendingTrackTransition = null
        if (activePlan != null) {
            pendingRequest = request
            if (!exiting) {
                exiting = true
                terminalAfterStop = PlaybackState.Idle
                timelineJob?.cancel()
                scope.launch { engine.stop() }
            }
            return
        }
        lastRequest = request
        exiting = false
        activeExternalSubtitle = null
        timelineJob?.cancel()
        negotiationJob?.cancel()
        if (request.scope.userId.isBlank() || request.scope.serverId != request.identity.serverId || request.identity.itemId.isBlank()) {
            mutableState.value = PlaybackState.Failed(PlaybackFailureKind.SourceUnavailable)
            return
        }
        val title = when (request) {
            is MediaPlaybackRequest.Resume -> request.title
            is MediaPlaybackRequest.PlayFromBeginning -> request.title
        }
        mutableState.value = PlaybackState.Negotiating(title)
        negotiationJob = scope.launch {
            val start = when (request) {
                is MediaPlaybackRequest.Resume -> PlaybackStart.Resume(request.positionTicks)
                is MediaPlaybackRequest.PlayFromBeginning -> PlaybackStart.Beginning
            }
            val scoped = ScopedPlaybackRequest(
                request.scope,
                request.identity,
                start,
            )
            when (val result = gateway.negotiate(scoped, capabilities)) {
                is PlaybackNegotiationResult.Failed -> {
                    mutableIsPlaying.value = false
                    mutableState.value = PlaybackState.Failed(result.reason.toContractFailure())
                }
                is PlaybackNegotiationResult.Ready -> {
                    activePlan = result.plan
                    observeProgressStatus(result.plan.request.scope)
                    val startTicks = (start as? PlaybackStart.Resume)?.positionTicks ?: 0L
                    try {
                        engine.prepare(result.plan, startTicks, startPaused = false)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        activePlan = null
                        mutableIsPlaying.value = false
                        mutableState.value = PlaybackState.Failed(PlaybackFailureKind.SourceUnavailable)
                        return@launch
                    }
                    loadAndApplyPreferences(result.plan)
                    gateway.report(report(result.plan, PlaybackReportKind.Playing))
                    engine.acknowledgePlayingReported()
                    mutableIsPlaying.value = true
                    publishActive(title, controlsVisible = true)
                    if (scheduleTimelineUpdates) startTimelineUpdates()
                }
            }
        }
    }

    override fun toggleControls() {
        val current = mutableState.value as? PlaybackState.Active ?: return
        mutableState.value = current.copy(controlsVisible = !current.controlsVisible)
    }

    override fun playPause() {
        val current = mutableState.value as? PlaybackState.Active ?: return
        val plan = activePlan ?: return
        scope.launch {
            engine.playPause()
            mutableIsPlaying.value = !engine.isPaused
            publishActive(current.title, controlsVisible = true)
        }
    }

    override fun seekBy(deltaTicks: Long) {
        seekTo((engine.positionTicks + deltaTicks).coerceIn(0, activePlan?.runtimeTicks ?: Long.MAX_VALUE))
    }

    override fun seekTo(positionTicks: Long) {
        val current = mutableState.value as? PlaybackState.Active ?: return
        val plan = activePlan ?: return
        scope.launch {
            engine.seekTo(positionTicks.coerceIn(0, plan.runtimeTicks))
            publishActive(current.title, controlsVisible = true)
        }
    }

    override fun selectTrack(selection: PlaybackTrackSelection) {
        val current = mutableState.value as? PlaybackState.Active ?: return
        val previousPlan = activePlan ?: return
        if (current.isChangingTrack) return
        // A selection whose subtitle is the reserved external index means "keep the active provider
        // subtitle" (the user changed audio, or re-picked the external row): renegotiate server subs
        // off but leave the side-loaded external subtitle in place. Any other subtitle selection (a
        // real server stream, or Off) deactivates the provider subtitle so the server choice wins.
        val keepExternal = selection.subtitleStreamIndex == ExternalSubtitleActivation.SubtitleStreamIndex
        val effectiveSelection = if (keepExternal) selection.copy(subtitleStreamIndex = null) else selection
        if (!keepExternal) activeExternalSubtitle = null
        val positionTicks = engine.positionTicks.coerceIn(0, previousPlan.runtimeTicks)
        val wasPaused = engine.isPaused
        mutableState.value = current.copy(
            controlsVisible = true,
            isChangingTrack = true,
            trackChangeError = null,
        )
        trackChangeJob = scope.launch {
            gateway.report(
                PlaybackReport(previousPlan, PlaybackReportKind.Progress, positionTicks, wasPaused),
            )
            val request = previousPlan.request.copy(
                start = PlaybackStart.Resume(positionTicks),
                trackSelection = effectiveSelection,
                sessionReference = previousPlan.sessionReference,
            )
            when (val result = gateway.negotiate(request, capabilities)) {
                is PlaybackNegotiationResult.Failed -> publishTrackRollback(current, previousPlan)
                is PlaybackNegotiationResult.Ready -> {
                    val transition = PendingTrackTransition(
                        current, previousPlan, result.plan, positionTicks, wasPaused, restoring = false,
                    )
                    pendingTrackTransition = transition
                    activePlan = result.plan
                    try {
                        engine.prepare(result.plan, positionTicks, wasPaused)
                        reapplyPreferences()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        beginTrackRestore(transition)
                        return@launch
                    }
                }
            }
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val current = mutableState.value as? PlaybackState.Active ?: return
        val plan = activePlan ?: return
        if (!engine.speedControlSupported) return
        currentSpeed = speed
        preferences.setSpeed(plan.request.scope, speed)
        scope.launch {
            engine.setSpeed(speed)
            publishActive(current.title, controlsVisible = true)
        }
    }

    override fun setSubtitleDelay(deltaMillis: Long) {
        val current = mutableState.value as? PlaybackState.Active ?: return
        val plan = activePlan ?: return
        if (!engine.subtitleDelaySupported) return
        val identity = MediaIdentity(plan.request.serverId, plan.request.itemId)
        val updated = currentSubtitleDelayMillis + deltaMillis
        currentSubtitleDelayMillis = updated
        preferences.setSubtitleDelay(identity, updated)
        scope.launch {
            engine.setSubtitleDelayMs(updated)
            publishActive(current.title, controlsVisible = true)
        }
    }

    override fun setSubtitleAppearance(appearance: SubtitleAppearance) {
        val current = mutableState.value as? PlaybackState.Active ?: return
        val plan = activePlan ?: return
        if (!engine.subtitleAppearanceSupported) return
        currentSubtitleAppearance = appearance
        preferences.setSubtitleAppearance(plan.request.scope, appearance)
        scope.launch {
            engine.setSubtitleAppearance(appearance)
            publishActive(current.title, controlsVisible = true)
        }
    }

    /**
     * Activate a provider-downloaded External subtitle as the current subtitle track. Crucially this
     * NEVER renegotiates with the server (no gateway.negotiate) and reads the LIVE engine position and
     * paused state, so playback keeps its exact position and playing/paused state — the subtitle is
     * side-loaded in place. If the engine rejects it (incompatible), the previous external subtitle is
     * restored so the prior track stays current and playback is undisturbed.
     */
    override fun addExternalSubtitle(activation: ExternalSubtitleActivation) {
        val current = mutableState.value as? PlaybackState.Active ?: return
        if (activePlan == null) return
        val previousExternal = activeExternalSubtitle
        val track = activation.track.copy(
            index = ExternalSubtitleActivation.SubtitleStreamIndex,
            type = dev.chaichai.mobile.core.contracts.PlaybackTrackType.Subtitle,
            delivery = TrackDelivery.External,
            isCurrent = true,
        )
        activeExternalSubtitle = track
        publishActive(current.title, controlsVisible = true)
        scope.launch {
            try {
                engine.applyExternalSubtitle(activation.localRef, activation.mimeType, activation.track.language)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Incompatible/unreadable: roll back to whatever subtitle was current before.
                activeExternalSubtitle = previousExternal
                (mutableState.value as? PlaybackState.Active)?.let { publishActive(it.title, it.controlsVisible) }
                return@launch
            }
        }
    }

    private suspend fun loadAndApplyPreferences(plan: AuthoritativePlaybackPlan) {
        val identity = MediaIdentity(plan.request.serverId, plan.request.itemId)
        currentSpeed = preferences.speedFor(plan.request.scope)
        currentSubtitleDelayMillis = preferences.subtitleDelayFor(identity)
        currentSubtitleAppearance = preferences.subtitleAppearanceFor(plan.request.scope)
        if (engine.speedControlSupported) engine.setSpeed(currentSpeed)
        if (engine.subtitleDelaySupported) engine.setSubtitleDelayMs(currentSubtitleDelayMillis)
        if (engine.subtitleAppearanceSupported) engine.setSubtitleAppearance(currentSubtitleAppearance)
    }

    private suspend fun reapplyPreferences() {
        if (engine.speedControlSupported) engine.setSpeed(currentSpeed)
        if (engine.subtitleDelaySupported) engine.setSubtitleDelayMs(currentSubtitleDelayMillis)
        if (engine.subtitleAppearanceSupported) engine.setSubtitleAppearance(currentSubtitleAppearance)
    }

    override fun retry() {
        lastRequest?.let(::submit)
    }

    override fun retryProgressSync() {
        activePlan?.request?.scope?.let { (gateway as? ProgressAwarePlaybackGateway)?.retryProgress(it) }
    }

    override fun exit() {
        negotiationJob?.cancel()
        trackChangeJob?.cancel()
        pendingTrackTransition = null
        val plan = activePlan
        if (plan == null) {
            val identity = lastRequest?.identity ?: return
            mutableIsPlaying.value = false
            mutableState.value = PlaybackState.Exited(identity)
            return
        }
        if (exiting) return
        exiting = true
        timelineJob?.cancel()
        terminalAfterStop = PlaybackState.Exited(
            dev.chaichai.mobile.core.contracts.MediaIdentity(plan.request.serverId, plan.request.itemId),
        )
        scope.launch {
            gateway.report(report(plan, PlaybackReportKind.Progress))
            engine.stop()
        }
    }

    private fun reportServiceProgress(event: PlaybackEngineEvent.Progress) {
        val plan = activePlan ?: return
        val current = mutableState.value as? PlaybackState.Active
        if (current != null && !current.isChangingTrack) {
            mutableIsPlaying.value = !event.isPaused
            mutableState.value = current.copy(
                positionTicks = event.positionTicks.coerceIn(0, plan.runtimeTicks),
                isPaused = event.isPaused,
                controlsVisible = if (
                    event.event == dev.chaichai.mobile.platform.server.PlaybackProgressEvent.TimeUpdate
                ) current.controlsVisible else true,
            )
        }
        scope.launch {
            if (activePlan == plan) gateway.report(
                PlaybackReport(
                    plan, PlaybackReportKind.Progress, event.positionTicks, event.isPaused, event = event.event,
                ),
            )
        }
    }

    private fun startTimelineUpdates() {
        val plan = activePlan ?: return
        timelineJob?.cancel()
        timelineJob = scope.launch {
            while (activePlan == plan) {
                delay(TIMELINE_INTERVAL_MILLIS)
                val active = mutableState.value as? PlaybackState.Active ?: continue
                if (activePlan == plan) publishActive(active.title, active.controlsVisible)
            }
        }
    }

    private fun report(
        plan: AuthoritativePlaybackPlan,
        kind: PlaybackReportKind,
        event: dev.chaichai.mobile.platform.server.PlaybackProgressEvent? = null,
    ): PlaybackReport {
        val snapshot = engine.snapshot
        return PlaybackReport(plan, kind, snapshot.positionTicks, snapshot.isPaused, event = event)
    }

    private fun failActivePlayback() {
        val plan = activePlan ?: return
        if (exiting) return
        exiting = true
        timelineJob?.cancel()
        terminalAfterStop = PlaybackState.Failed(PlaybackFailureKind.SourceUnavailable)
        scope.launch {
            engine.stop()
        }
    }

    private fun recoverTrackTransitionOrFail() {
        val transition = pendingTrackTransition
        if (transition == null) {
            failActivePlayback()
            return
        }
        if (transition.restoring) {
            pendingTrackTransition = null
            failActivePlayback()
            return
        }
        trackChangeJob = scope.launch { beginTrackRestore(transition) }
    }

    private suspend fun beginTrackRestore(transition: PendingTrackTransition) {
        if (pendingTrackTransition != transition) return
        val restoring = transition.copy(restoring = true)
        pendingTrackTransition = restoring
        activePlan = restoring.previousPlan
        try {
            engine.prepare(restoring.previousPlan, restoring.positionTicks, restoring.wasPaused)
            reapplyPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pendingTrackTransition = null
            failActivePlayback()
        }
    }

    private fun confirmTrackTransition() {
        val transition = pendingTrackTransition ?: return
        pendingTrackTransition = null
        trackChangeJob = scope.launch {
            if (transition.restoring) {
                activePlan = transition.previousPlan
                gateway.report(report(transition.previousPlan, PlaybackReportKind.Playing))
                engine.acknowledgePlayingReported()
                mutableIsPlaying.value = !engine.isPaused
                publishTrackRollback(transition.activeState, transition.previousPlan)
                if (scheduleTimelineUpdates) startTimelineUpdates()
            } else {
                activePlan = transition.replacementPlan
                gateway.report(report(transition.replacementPlan, PlaybackReportKind.Playing))
                engine.acknowledgePlayingReported()
                mutableIsPlaying.value = !engine.isPaused
                publishActive(transition.activeState.title, controlsVisible = true)
                if (scheduleTimelineUpdates) startTimelineUpdates()
            }
        }
    }

    private fun finishServiceStop(event: PlaybackEngineEvent.Stopped) {
        val plan = activePlan ?: return
        val terminal = terminalAfterStop ?: PlaybackState.Failed(PlaybackFailureKind.SourceUnavailable)
        timelineJob?.cancel()
        scope.launch {
            gateway.report(
                PlaybackReport(plan, PlaybackReportKind.Stopped, event.positionTicks, event.isPaused),
            )
            progressStatusJob?.cancel()
            activePlan = null
            mutableIsPlaying.value = false
            mutableState.value = terminal
            terminalAfterStop = null
            exiting = false
            pendingRequest?.let { replacement ->
                pendingRequest = null
                submit(replacement)
            }
        }
    }

    private fun publishActive(title: String, controlsVisible: Boolean) {
        val plan = activePlan ?: return
        val snapshot = engine.snapshot
        val external = activeExternalSubtitle
        val subtitleTracks = if (external != null) {
            // The activated provider subtitle becomes the single current subtitle; server streams
            // stay available but no longer current.
            plan.subtitleTracks.map { it.copy(isCurrent = false) } + external
        } else {
            plan.subtitleTracks
        }
        mutableState.value = PlaybackState.Active(
            identity = dev.chaichai.mobile.core.contracts.MediaIdentity(plan.request.serverId, plan.request.itemId),
            title = title,
            positionTicks = snapshot.positionTicks,
            runtimeTicks = plan.runtimeTicks,
            isPaused = snapshot.isPaused,
            controlsVisible = controlsVisible,
            audioTracks = plan.audioTracks,
            subtitleTracks = subtitleTracks,
            progressSync = (gateway as? ProgressAwarePlaybackGateway)?.progressStatus(plan.request.scope)?.value?.toContract()
                ?: PlaybackProgressSync.Synced,
            playbackSpeed = currentSpeed,
            subtitleDelayMillis = currentSubtitleDelayMillis,
            speedControlSupported = engine.speedControlSupported,
            subtitleDelaySupported = engine.subtitleDelaySupported,
            subtitleAppearance = currentSubtitleAppearance,
            subtitleAppearanceSupported = engine.subtitleAppearanceSupported,
            scope = plan.request.scope,
        )
    }

    private fun publishTrackRollback(current: PlaybackState.Active, plan: AuthoritativePlaybackPlan) {
        if (activePlan != plan) return
        mutableState.value = current.copy(
            positionTicks = engine.positionTicks,
            isPaused = engine.isPaused,
            controlsVisible = true,
            isChangingTrack = false,
            trackChangeError = "That track couldn't be applied. The previous track is still playing.",
        )
    }

    private fun PlaybackFailure.toContractFailure() = PlaybackFailureKind.valueOf(name)

    private fun ProgressSyncStatus.toContract(): PlaybackProgressSync = when (this) {
        ProgressSyncStatus.Synced -> PlaybackProgressSync.Synced
        ProgressSyncStatus.Pending -> PlaybackProgressSync.Pending
        is ProgressSyncStatus.Failed -> PlaybackProgressSync.Failed(message)
    }

    private fun observeProgressStatus(accountScope: dev.chaichai.mobile.core.contracts.HomeScope) {
        progressStatusJob?.cancel()
        val progressGateway = gateway as? ProgressAwarePlaybackGateway ?: return
        progressStatusJob = scope.launch {
            progressGateway.progressStatus(accountScope).collect { status ->
                val plan = activePlan ?: return@collect
                if (plan.request.scope != accountScope) return@collect
                val active = mutableState.value as? PlaybackState.Active ?: return@collect
                mutableState.value = active.copy(progressSync = status.toContract())
            }
        }
    }

    private companion object {
        const val TIMELINE_INTERVAL_MILLIS = 1_000L
    }

    private data class PendingTrackTransition(
        val activeState: PlaybackState.Active,
        val previousPlan: AuthoritativePlaybackPlan,
        val replacementPlan: AuthoritativePlaybackPlan,
        val positionTicks: Long,
        val wasPaused: Boolean,
        val restoring: Boolean,
    )
}
