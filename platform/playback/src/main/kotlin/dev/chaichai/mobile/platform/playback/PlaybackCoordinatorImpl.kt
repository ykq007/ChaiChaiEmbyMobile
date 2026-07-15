package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackState
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

interface PlaybackEngine {
    val positionTicks: Long
    val isPaused: Boolean
    val events: Flow<PlaybackEngineEvent> get() = emptyFlow()
    suspend fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long)
    suspend fun playPause()
    suspend fun seekTo(positionTicks: Long)
    suspend fun stop()
}
sealed interface PlaybackEngineEvent {
    data object Completed : PlaybackEngineEvent
    data object FatalError : PlaybackEngineEvent
}

class PlaybackCoordinatorImpl(
    private val scope: CoroutineScope,
    private val gateway: PlaybackGateway,
    private val engine: PlaybackEngine,
    private val capabilities: PlaybackCapabilities,
    private val schedulePeriodicReports: Boolean = true,
    private val scheduleTimelineUpdates: Boolean = schedulePeriodicReports,
) : PlaybackCoordinator, AutoCloseable {
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = mutableState
    private val mutableIsPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = mutableIsPlaying
    private var activePlan: AuthoritativePlaybackPlan? = null
    private var lastRequest: MediaPlaybackRequest? = null
    private var periodicJob: Job? = null
    private var timelineJob: Job? = null
    private var negotiationJob: Job? = null
    private var exiting = false

    private val engineEventJob = scope.launch {
            engine.events.collect { event ->
                when (event) {
                    PlaybackEngineEvent.Completed -> exit()
                    PlaybackEngineEvent.FatalError -> failActivePlayback()
                }
            }
    }

    override fun close() {
        engineEventJob.cancel()
        periodicJob?.cancel()
        timelineJob?.cancel()
        negotiationJob?.cancel()
    }

    override fun submit(request: MediaPlaybackRequest) {
        lastRequest = request
        exiting = false
        periodicJob?.cancel()
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
                    activePlan?.let { previous -> stopPlan(previous) }
                    activePlan = result.plan
                    val startTicks = (start as? PlaybackStart.Resume)?.positionTicks ?: 0L
                    engine.prepare(result.plan, startTicks)
                    mutableIsPlaying.value = true
                    publishActive(title, controlsVisible = true)
                    gateway.report(report(result.plan, PlaybackReportKind.Playing))
                    if (schedulePeriodicReports) startPeriodicReports()
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
            gateway.report(report(plan, PlaybackReportKind.Progress, if (engine.isPaused) {
                dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Pause
            } else dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Unpause))
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
            gateway.report(report(plan, PlaybackReportKind.Progress, dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Seek))
        }
    }

    override fun retry() {
        lastRequest?.let(::submit)
    }

    override fun exit() {
        negotiationJob?.cancel()
        val plan = activePlan
        if (plan == null) {
            val identity = lastRequest?.identity ?: return
            mutableIsPlaying.value = false
            mutableState.value = PlaybackState.Exited(identity)
            return
        }
        if (exiting) return
        exiting = true
        periodicJob?.cancel()
        timelineJob?.cancel()
        scope.launch {
            gateway.report(report(plan, PlaybackReportKind.Progress))
            stopPlan(plan)
            engine.stop()
            activePlan = null
            mutableIsPlaying.value = false
            mutableState.value = PlaybackState.Exited(
                dev.chaichai.mobile.core.contracts.MediaIdentity(plan.request.serverId, plan.request.itemId),
            )
        }
    }

    private fun startPeriodicReports() {
        val plan = activePlan ?: return
        periodicJob = scope.launch {
            while (activePlan == plan) {
                delay(PROGRESS_INTERVAL_MILLIS)
                if (activePlan == plan) gateway.report(
                    report(plan, PlaybackReportKind.Progress, dev.chaichai.mobile.platform.server.PlaybackProgressEvent.TimeUpdate),
                )
            }
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

    private suspend fun stopPlan(plan: AuthoritativePlaybackPlan) {
        gateway.report(report(plan, PlaybackReportKind.Stopped))
    }

    private fun report(
        plan: AuthoritativePlaybackPlan,
        kind: PlaybackReportKind,
        event: dev.chaichai.mobile.platform.server.PlaybackProgressEvent? = null,
    ) = PlaybackReport(
        plan, kind, engine.positionTicks, engine.isPaused, event = event,
    )

    private fun failActivePlayback() {
        val plan = activePlan ?: return
        if (exiting) return
        exiting = true
        periodicJob?.cancel()
        timelineJob?.cancel()
        scope.launch {
            stopPlan(plan)
            engine.stop()
            activePlan = null
            mutableIsPlaying.value = false
            mutableState.value = PlaybackState.Failed(PlaybackFailureKind.SourceUnavailable)
            exiting = false
        }
    }

    private fun publishActive(title: String, controlsVisible: Boolean) {
        val plan = activePlan ?: return
        mutableState.value = PlaybackState.Active(
            identity = dev.chaichai.mobile.core.contracts.MediaIdentity(plan.request.serverId, plan.request.itemId),
            title = title,
            positionTicks = engine.positionTicks,
            runtimeTicks = plan.runtimeTicks,
            isPaused = engine.isPaused,
            controlsVisible = controlsVisible,
        )
    }

    private fun PlaybackFailure.toContractFailure() = PlaybackFailureKind.valueOf(name)

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 10_000L
        const val TIMELINE_INTERVAL_MILLIS = 1_000L
    }
}
