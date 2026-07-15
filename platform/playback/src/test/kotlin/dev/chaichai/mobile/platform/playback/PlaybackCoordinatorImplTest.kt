package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan
import dev.chaichai.mobile.platform.server.DirectPlayCapability
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.PlaybackFailure
import dev.chaichai.mobile.platform.server.PlaybackGateway
import dev.chaichai.mobile.platform.server.PlaybackMethod
import dev.chaichai.mobile.platform.server.PlaybackNegotiationResult
import dev.chaichai.mobile.platform.server.PlaybackReport
import dev.chaichai.mobile.platform.server.PlaybackReportKind
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorImplTest {
    @Test
    fun `resume commits authoritative plan and seek exit reports one stopped event`() = runTest {
        val gateway = FakeGateway()
        val engine = FakeEngine()
        val coordinator = PlaybackCoordinatorImpl(this, gateway, engine, capabilities(), false)

        coordinator.submit(MediaPlaybackRequest.Resume(MediaIdentity("server", "movie"), 900_000_000, "user", "Arrival"))
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
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(MediaIdentity("server", "movie"), "user", "Arrival"))
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
            coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(MediaIdentity("server", "movie"), "user"))
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
        coordinator.submit(MediaPlaybackRequest.PlayFromBeginning(MediaIdentity("server", "movie"), "user"))
        runCurrent()

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, gateway.reports.count { it.kind == PlaybackReportKind.Progress })

        engine.eventsFlow.emit(PlaybackEngineEvent.FatalError)
        runCurrent()
        assertEquals(1, gateway.reports.count { it.kind == PlaybackReportKind.Stopped })
        assertEquals(PlaybackFailureKind.SourceUnavailable, (coordinator.state.value as PlaybackState.Failed).reason)
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
        val reports = mutableListOf<PlaybackReport>()
        override suspend fun negotiate(request: ScopedPlaybackRequest, capabilities: PlaybackCapabilities): PlaybackNegotiationResult {
            this.request = request
            return result ?: PlaybackNegotiationResult.Ready(
                AuthoritativePlaybackPlan(
                    request, "source", "session", PlaybackMethod.DirectPlay,
                    "https://example.test/video".toHttpUrl(), emptyMap(), 7_200_000_000, null, null,
                ),
            )
        }
        override suspend fun report(event: PlaybackReport): Boolean = true.also { reports += event }
    }

    private class FakeEngine : PlaybackEngine {
        val eventsFlow = MutableSharedFlow<PlaybackEngineEvent>()
        override val events = eventsFlow
        override var positionTicks = 0L
        override var isPaused = false
        override suspend fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long) { positionTicks = startPositionTicks }
        override fun playPause() { isPaused = !isPaused }
        override fun seekTo(positionTicks: Long) { this.positionTicks = positionTicks }
        override fun stop() = Unit
    }
}

private val ScopedPlaybackRequest.startPositionTicks: Long
    get() = when (val value = start) {
        dev.chaichai.mobile.platform.server.PlaybackStart.Beginning -> 0
        is dev.chaichai.mobile.platform.server.PlaybackStart.Resume -> value.positionTicks
    }
