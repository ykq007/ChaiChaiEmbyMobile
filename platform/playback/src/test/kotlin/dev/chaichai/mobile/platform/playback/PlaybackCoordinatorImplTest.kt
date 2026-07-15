package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan
import dev.chaichai.mobile.platform.server.DirectPlayCapability
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.PlaybackFailure
import dev.chaichai.mobile.platform.server.PlaybackGateway
import dev.chaichai.mobile.platform.server.PlaybackMethod
import dev.chaichai.mobile.platform.server.PlaybackNegotiationResult
import dev.chaichai.mobile.platform.server.PlaybackReport
import dev.chaichai.mobile.platform.server.PlaybackReportKind
import dev.chaichai.mobile.platform.server.PlaybackSessionReference
import dev.chaichai.mobile.platform.server.ScopedPlaybackRequest
import dev.chaichai.mobile.platform.server.TranscodeCapability
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorImplTest {
    @Test
    fun `resume commits authoritative plan and seek exit reports one stopped event`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)

        coordinator.submit(MediaPlaybackRequest.Resume(MediaIdentity("server", "movie"), 900_000_000, HomeScope("server", "user"), "Arrival"))
        advanceUntilIdle()
        coordinator.seekBy(300_000_000)
        advanceUntilIdle()
        coordinator.exit()
        coordinator.exit()
        advanceUntilIdle()

        assertEquals(900_000_000, gateway.request!!.startPositionTicks)
        assertEquals(1_200_000_000, engine.positionTicks)
        assertEquals(
            listOf(PlaybackReportKind.Playing, PlaybackReportKind.Progress, PlaybackReportKind.Progress, PlaybackReportKind.Stopped),
            gateway.reports.map { it.kind },
        )
        assertEquals(1, gateway.reports.count { it.kind == PlaybackReportKind.Stopped })
        assertEquals(PlaybackState.Exited(MediaIdentity("server", "movie")), coordinator.state.value)
        coordinator.close()
    }

    @Test
    fun `pause and timeline seek preserve title and expose truthful player state`() = runTest {
        val coordinator = PlaybackCoordinatorImpl(this, FakeGateway(), FakeEngine(), capabilities(), false)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(MediaIdentity("server", "movie"), HomeScope("server", "user"), "Arrival"))
        advanceUntilIdle()

        coordinator.playPause()
        coordinator.seekTo(2_000_000_000)
        advanceUntilIdle()

        val state = coordinator.state.value as PlaybackState.Active
        assertEquals("Arrival", state.title)
        assertEquals(2_000_000_000, state.positionTicks)
        assertEquals(true, state.isPaused)
        assertFalse(coordinator.isPlaying.value)
        coordinator.close()
    }

    @Test
    fun `gateway failures retain distinct retry or back policy`() = runTest {
        PlaybackFailure.entries.forEach { failure ->
            val gateway = FakeGateway(PlaybackNegotiationResult.Failed(failure))
            val coordinator = PlaybackCoordinatorImpl(this, gateway, FakeEngine(), capabilities(), false)
            coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(MediaIdentity("server", "movie"), HomeScope("server", "user")))
            advanceUntilIdle()
            assertEquals(failure.name, (coordinator.state.value as PlaybackState.Failed).reason.name)
            assertEquals(
                failure == PlaybackFailure.Network || failure == PlaybackFailure.SourceUnavailable,
                (coordinator.state.value as PlaybackState.Failed).reason.canRetry,
            )
            coordinator.close()
        }
    }

    @Test
    fun `periodic progress and fatal media3 errors stop the active session exactly once`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), true)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(MediaIdentity("server", "movie"), HomeScope("server", "user")))
        runCurrent()

        engine.eventsFlow.emit(PlaybackEngineEvent.Progress(
            dev.chaichai.mobile.platform.server.PlaybackProgressEvent.TimeUpdate, engine.positionTicks, engine.isPaused,
        ))
        runCurrent()
        assertEquals(1, gateway.reports.count { it.kind == PlaybackReportKind.Progress })

        engine.eventsFlow.emit(PlaybackEngineEvent.FatalError)
        runCurrent()
        assertEquals(1, gateway.reports.count { it.kind == PlaybackReportKind.Stopped })
        assertEquals(PlaybackFailureKind.SourceUnavailable, (coordinator.state.value as PlaybackState.Failed).reason)
        coordinator.close()
    }

    @Test
    fun `media session pause and seek events produce event driven progress`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"),
        ))
        runCurrent()

        engine.eventsFlow.emit(PlaybackEngineEvent.Progress(
            dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Pause, 10, true,
        ))
        engine.eventsFlow.emit(PlaybackEngineEvent.Progress(
            dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Seek, 20, true,
        ))
        runCurrent()

        assertEquals(
            listOf(
                dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Pause,
                dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Seek,
            ),
            gateway.reports.filter { it.kind == PlaybackReportKind.Progress }.map { it.event },
        )
        assertEquals(listOf(10L, 20L), gateway.reports.filter { it.kind == PlaybackReportKind.Progress }.map { it.positionTicks })
        assertTrue(gateway.reports.filter { it.kind == PlaybackReportKind.Progress }.all { it.isPaused })
        coordinator.close()
    }

    @Test
    fun `audio focus or noisy output interruption updates the active session without renegotiation`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"), "Arrival",
        ))
        runCurrent()

        engine.positionTicks = 450_000_000
        engine.isPaused = true
        engine.eventsFlow.emit(
            PlaybackEngineEvent.Progress(
                dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Pause,
                450_000_000,
                true,
            ),
        )
        runCurrent()

        val active = coordinator.state.value as PlaybackState.Active
        assertEquals(MediaIdentity("server", "movie"), active.identity)
        assertEquals(450_000_000, active.positionTicks)
        assertTrue(active.isPaused)
        assertFalse(coordinator.isPlaying.value)
        assertEquals(1, gateway.requests.size)
        coordinator.close()
    }

    @Test
    fun `service owned background event reports its snapshot and leaves playback active`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"), "Arrival",
        ))
        runCurrent()
        engine.positionTicks = 700_000_000

        engine.eventsFlow.emit(
            PlaybackEngineEvent.Progress(
                dev.chaichai.mobile.platform.server.PlaybackProgressEvent.TimeUpdate,
                700_000_000,
                false,
            ),
        )
        runCurrent()

        val background = gateway.reports.last()
        assertEquals(PlaybackReportKind.Progress, background.kind)
        assertEquals(700_000_000, background.positionTicks)
        assertTrue(coordinator.state.value is PlaybackState.Active)
        assertEquals(0, engine.stopCount)
        coordinator.close()
    }

    @Test
    fun `playing is reported before service control events are enabled`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        gateway.onReport = { report ->
            if (report.kind == PlaybackReportKind.Playing) assertFalse(engine.acknowledgedPlaying)
        }
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)

        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"),
        ))
        runCurrent()

        assertEquals(PlaybackReportKind.Playing, gateway.reports.first().kind)
        assertTrue(engine.acknowledgedPlaying)
        coordinator.close()
    }

    @Test
    fun `replacement stops and reports the old session before playing the new one`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "old"), HomeScope("server", "user"), "Old",
        ))
        runCurrent()

        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "new"), HomeScope("server", "user"), "New",
        ))
        runCurrent()

        assertEquals(
            listOf(PlaybackReportKind.Playing, PlaybackReportKind.Stopped, PlaybackReportKind.Playing),
            gateway.reports.map { it.kind },
        )
        assertEquals(listOf("old", "old", "new"), gateway.reports.map { it.plan.request.itemId })
        assertEquals("New", (coordinator.state.value as PlaybackState.Active).title)
        coordinator.close()
    }

    @Test
    fun `unsolicited service destruction reports stop and clears active playback`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"),
        ))
        runCurrent()

        engine.eventsFlow.emit(PlaybackEngineEvent.Stopped(333, true))
        runCurrent()

        assertEquals(333, gateway.reports.single { it.kind == PlaybackReportKind.Stopped }.positionTicks)
        assertEquals(PlaybackFailureKind.SourceUnavailable, (coordinator.state.value as PlaybackState.Failed).reason)
        assertFalse(coordinator.isPlaying.value)
        coordinator.close()
    }

    @Test
    fun `media3 preparation failure leaves negotiation in retryable source failure`() = runTest {
        val engine = FakeEngine().apply { prepareFailure = IllegalStateException("service unavailable") }
        val coordinator = PlaybackCoordinatorImpl(this, FakeGateway(), engine, capabilities(), false)

        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"),
        ))
        runCurrent()

        assertEquals(PlaybackFailureKind.SourceUnavailable, (coordinator.state.value as PlaybackState.Failed).reason)
        coordinator.close()
    }

    @Test
    fun `timeline republishes the service position while playback is active`() = runTest {
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, FakeGateway(), engine, capabilities(), true)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"), "Arrival",
        ))
        runCurrent()
        engine.positionTicks = 120_000_000

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(120_000_000, (coordinator.state.value as PlaybackState.Active).positionTicks)
        coordinator.close()
    }

    @Test
    fun `mismatched request scope is rejected before negotiation`() = runTest {
        val gateway = FakeGateway()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, FakeEngine(), capabilities(), false)

        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server-a", "movie"), HomeScope("server-b", "user"),
        ))

        assertEquals(PlaybackFailureKind.SourceUnavailable, (coordinator.state.value as PlaybackState.Failed).reason)
        assertEquals(null, gateway.request)
        coordinator.close()
    }

    @Test
    fun `successful audio change preserves media position pause state and negotiated session continuity`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), true)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"), "Arrival",
        ))
        runCurrent()
        engine.positionTicks = 1_200_000_000
        coordinator.playPause()
        runCurrent()
        engine.autoReady = false

        coordinator.selectTrack(PlaybackTrackSelection(audioStreamIndex = 2))
        runCurrent()

        val changing = coordinator.state.value as PlaybackState.Active
        assertTrue(changing.isChangingTrack)
        assertTrue(changing.audioTracks.single { it.index == 1 }.isCurrent)
        engine.eventsFlow.emit(PlaybackEngineEvent.Ready)
        runCurrent()

        val state = coordinator.state.value as PlaybackState.Active
        assertEquals(MediaIdentity("server", "movie"), state.identity)
        assertEquals(1_200_000_000, state.positionTicks)
        assertTrue(state.isPaused)
        assertTrue(state.audioTracks.single { it.index == 2 }.isCurrent)
        assertEquals(PlaybackSessionReference("source", "session"), gateway.requests.last().sessionReference)
        assertEquals(1_200_000_000, gateway.requests.last().startPositionTicks)
        assertEquals(listOf(false, true), engine.preparePauseStates)
        engine.positionTicks = 1_300_000_000
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1_300_000_000, (coordinator.state.value as PlaybackState.Active).positionTicks)
        coordinator.close()
    }

    @Test
    fun `delayed replacement error restores the prior plan after it becomes ready`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), true)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"), "Arrival",
        ))
        runCurrent()
        engine.positionTicks = 900_000_000
        coordinator.playPause()
        runCurrent()
        engine.autoReady = false

        coordinator.selectTrack(PlaybackTrackSelection(audioStreamIndex = 2))
        runCurrent()
        engine.eventsFlow.emit(PlaybackEngineEvent.FatalError)
        runCurrent()

        assertTrue((coordinator.state.value as PlaybackState.Active).isChangingTrack)
        assertEquals(listOf(false, true, true), engine.preparePauseStates)
        engine.eventsFlow.emit(PlaybackEngineEvent.Ready)
        runCurrent()

        val restored = coordinator.state.value as PlaybackState.Active
        assertFalse(restored.isChangingTrack)
        assertTrue(restored.audioTracks.single { it.index == 1 }.isCurrent)
        assertEquals(900_000_000, restored.positionTicks)
        assertTrue(restored.isPaused)
        assertTrue(restored.trackChangeError!!.contains("previous track is still playing"))
        assertEquals(
            listOf(
                PlaybackReportKind.Playing,
                PlaybackReportKind.Progress,
                PlaybackReportKind.Progress,
                PlaybackReportKind.Playing,
            ),
            gateway.reports.map { it.kind },
        )
        engine.positionTicks = 1_000_000_000
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1_000_000_000, (coordinator.state.value as PlaybackState.Active).positionTicks)
        coordinator.close()
    }

    @Test
    fun `failed subtitle change restores the prior working track and explains rollback`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(
            MediaIdentity("server", "movie"), HomeScope("server", "user"), "Arrival",
        ))
        runCurrent()
        engine.positionTicks = 800_000_000
        gateway.failTrackChanges = true

        coordinator.selectTrack(PlaybackTrackSelection(audioStreamIndex = null, subtitleStreamIndex = 4))
        runCurrent()

        val state = coordinator.state.value as PlaybackState.Active
        assertEquals(800_000_000, state.positionTicks)
        assertFalse(state.isPaused)
        assertFalse(state.isChangingTrack)
        assertTrue(state.trackChangeError!!.contains("previous track is still playing"))
        assertEquals(1, engine.preparePauseStates.size)
        assertEquals(listOf(PlaybackReportKind.Playing, PlaybackReportKind.Progress), gateway.reports.map { it.kind })
        coordinator.close()
    }

    private fun capabilities() = PlaybackCapabilities(
        18_000_000, 6, listOf(DirectPlayCapability("mp4", "h264", "aac")),
        listOf(TranscodeCapability("hls", "h264", "aac")),
    )

    private class FakeGateway(
        private val result: PlaybackNegotiationResult? = null,
    ) : PlaybackGateway {
        var request: ScopedPlaybackRequest? = null
        val requests = mutableListOf<ScopedPlaybackRequest>()
        val reports = mutableListOf<PlaybackReport>()
        var onReport: (PlaybackReport) -> Unit = {}
        var failTrackChanges = false
        override suspend fun negotiate(request: ScopedPlaybackRequest, capabilities: PlaybackCapabilities): PlaybackNegotiationResult {
            this.request = request
            requests += request
            if (failTrackChanges && request.trackSelection != null) {
                return PlaybackNegotiationResult.Failed(PlaybackFailure.SourceUnavailable)
            }
            val audioIndex = request.trackSelection?.audioStreamIndex ?: 1
            return result ?: PlaybackNegotiationResult.Ready(
                AuthoritativePlaybackPlan(
                    request, PlaybackSessionReference("source", "session"), PlaybackMethod.DirectPlay,
                    "https://example.test/video".toHttpUrl(), emptyMap(), 7_200_000_000,
                    audioIndex,
                    request.trackSelection?.subtitleStreamIndex,
                    audioTracks = listOf(1, 2).map { index ->
                        PlaybackTrack(index, PlaybackTrackType.Audio, isCurrent = index == audioIndex)
                    },
                ),
            )
        }
        override suspend fun report(event: PlaybackReport): Boolean = true.also {
            reports += event
            onReport(event)
        }
    }

    private class FakeEngine : PlaybackEngine {
        val eventsFlow = MutableSharedFlow<PlaybackEngineEvent>()
        override val events = eventsFlow
        override var positionTicks = 0L
        override var isPaused = false
        override val snapshot: PlaybackEngineSnapshot get() = PlaybackEngineSnapshot(positionTicks, isPaused)
        var prepareFailure: Exception? = null
        var autoReady = true
        var acknowledgedPlaying = false
        var stopCount = 0
        val preparePauseStates = mutableListOf<Boolean>()
        override suspend fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long, startPaused: Boolean) {
            prepareFailure?.let { throw it }
            positionTicks = startPositionTicks
            isPaused = startPaused
            preparePauseStates += startPaused
            if (autoReady) eventsFlow.emit(PlaybackEngineEvent.Ready)
        }
        override suspend fun acknowledgePlayingReported() { acknowledgedPlaying = true }
        override suspend fun playPause() {
            isPaused = !isPaused
            eventsFlow.emit(PlaybackEngineEvent.Progress(
                if (isPaused) dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Pause
                else dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Unpause,
                positionTicks,
                isPaused,
            ))
        }
        override suspend fun seekTo(positionTicks: Long) {
            this.positionTicks = positionTicks
            eventsFlow.emit(PlaybackEngineEvent.Progress(
                dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Seek, positionTicks, isPaused,
            ))
        }
        override suspend fun stop() {
            stopCount++
            eventsFlow.emit(PlaybackEngineEvent.Stopped(positionTicks, isPaused))
        }
    }
}

private val ScopedPlaybackRequest.startPositionTicks: Long
    get() = when (val value = start) {
        dev.chaichai.mobile.platform.server.PlaybackStart.Beginning -> 0
        is dev.chaichai.mobile.platform.server.PlaybackStart.Resume -> value.positionTicks
    }
