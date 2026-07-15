package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection
import dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.PlaybackFailure
import dev.chaichai.mobile.platform.server.PlaybackGateway
import dev.chaichai.mobile.platform.server.PlaybackNegotiationResult
import dev.chaichai.mobile.platform.server.PlaybackReport
import dev.chaichai.mobile.platform.server.PlaybackReportKind
import dev.chaichai.mobile.platform.server.PlaybackStart
import dev.chaichai.mobile.platform.server.ScopedPlaybackRequest
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
    suspend fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long, startPaused: Boolean = false)
    suspend fun acknowledgePlayingReported()
    suspend fun playPause()
    suspend fun seekTo(positionTicks: Long)
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
                trackSelection = selection,
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

    override fun retry() {
        lastRequest?.let(::submit)
    }

    override fun onAppBackgrounded() {
        val plan = activePlan ?: return
        scope.launch {
            if (activePlan == plan) gateway.report(report(plan, PlaybackReportKind.Progress))
        }
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
                controlsVisible = true,
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
        mutableState.value = PlaybackState.Active(
            identity = dev.chaichai.mobile.core.contracts.MediaIdentity(plan.request.serverId, plan.request.itemId),
            title = title,
            positionTicks = snapshot.positionTicks,
            runtimeTicks = plan.runtimeTicks,
            isPaused = snapshot.isPaused,
            controlsVisible = controlsVisible,
            audioTracks = plan.audioTracks,
            subtitleTracks = plan.subtitleTracks,
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
