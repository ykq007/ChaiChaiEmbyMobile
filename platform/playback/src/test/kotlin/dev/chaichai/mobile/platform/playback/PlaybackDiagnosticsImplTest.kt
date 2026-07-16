package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackPreferences
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.core.contracts.SubtitleAppearance
import dev.chaichai.mobile.core.contracts.VideoScaleMode
import dev.chaichai.mobile.platform.server.DirectPlayCapability
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.TranscodeCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the [PlaybackDiagnosticsImpl] redaction contract (Playback Polish, #35 AC3/AC4): even when the
 * observed [PlaybackState] carries a planted secret-shaped token, a full media URL, subtitle cue text
 * and a library title, NONE of that content is reachable from [dev.chaichai.mobile.core.contracts.DiagnosticsReport.text] —
 * because the assembling implementation never reads those fields off [PlaybackState.Active] at all, it
 * only reads [PlaybackCapabilities], the engine's scale-mode capability, and the last failure KIND. This
 * test doubles as the privacy gate's source-side evidence for playback diagnostics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackDiagnosticsImplTest {
    private val plantedToken = "sk_live_SECRETTOKEN123"
    private val plantedUrl = "https://emby.example/videos/123/stream.mp4?ApiKey=$plantedToken"
    private val plantedSubtitleText = "Never gonna give you up, never gonna let you down"
    private val plantedLibraryTitle = "My Very Private Home Movie"

    private fun capabilities() = PlaybackCapabilities(
        maxStreamingBitrate = 18_000_000,
        maxAudioChannels = 6,
        directPlayProfiles = listOf(DirectPlayCapability("mp4", "h264", "aac")),
        transcodeProfiles = listOf(TranscodeCapability("hls", "h264", "aac")),
    )

    private fun activeStateWithPlantedSecrets(): PlaybackState.Active = PlaybackState.Active(
        identity = MediaIdentity("server", plantedToken),
        title = "$plantedLibraryTitle — $plantedUrl",
        positionTicks = 0,
        runtimeTicks = 100,
        isPaused = false,
        subtitleTracks = listOf(
            PlaybackTrack(index = 0, type = PlaybackTrackType.Subtitle, title = plantedSubtitleText),
        ),
        scope = HomeScope("server", "user"),
    )

    private class FakeEngine(
        override val videoScaleModeSupported: Boolean = true,
        override val supportedScaleModes: List<VideoScaleMode> = listOf(VideoScaleMode.Fit, VideoScaleMode.Zoom),
    ) : PlaybackEngine {
        override val snapshot = PlaybackEngineSnapshot(0L, true)
        override suspend fun prepare(plan: dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan, startPositionTicks: Long, startPaused: Boolean) = Unit
        override suspend fun acknowledgePlayingReported() = Unit
        override suspend fun playPause() = Unit
        override suspend fun seekTo(positionTicks: Long) = Unit
        override suspend fun stop() = Unit
    }

    private class FakePreferences : PlaybackPreferences {
        var stored = false
        override fun diagnosticsEnabled(): Boolean = stored
        override fun setDiagnosticsEnabled(enabled: Boolean) { stored = enabled }
    }

    /**
     * [PlaybackDiagnosticsImpl]'s internal state-observing job is meant to live for the app's process
     * lifetime in production (see `ProductionBoundaries`, which never cancels it). Each test hands it a
     * child [CoroutineScope] sharing this [TestScope]'s dispatcher/scheduler (so `advanceUntilIdle` still
     * drives the collector) but with its OWN [Job] that [block] cancels before returning — `runTest`
     * requires every job be complete or cancelled by the time the test body finishes, and a live
     * `StateFlow.collect` job never completes on its own.
     */
    private fun runDiagnosticsTest(block: suspend TestScope.(scope: CoroutineScope) -> Unit) = runTest {
        val child = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            block(child)
        } finally {
            child.cancel()
        }
    }

    @Test
    fun `diagnostics are off by default`() = runDiagnosticsTest { scope ->
        val diagnostics = PlaybackDiagnosticsImpl(
            scope, MutableStateFlow(PlaybackState.Idle), capabilities(), FakeEngine(), FakePreferences(),
        )
        assertFalse(diagnostics.enabled.value)
    }

    @Test
    fun `enabling diagnostics persists the opt-in`() = runDiagnosticsTest { scope ->
        val preferences = FakePreferences()
        val diagnostics = PlaybackDiagnosticsImpl(
            scope, MutableStateFlow(PlaybackState.Idle), capabilities(), FakeEngine(), preferences,
        )

        diagnostics.setEnabled(true)

        assertTrue(diagnostics.enabled.value)
        assertTrue(preferences.stored)
    }

    @Test
    fun `snapshot never leaks a token, a full media URL, subtitle text, or a library title`() = runDiagnosticsTest { scope ->
        val state = MutableStateFlow<PlaybackState>(activeStateWithPlantedSecrets())
        val diagnostics = PlaybackDiagnosticsImpl(scope, state, capabilities(), FakeEngine(), FakePreferences())
        advanceUntilIdle()

        val report = diagnostics.snapshot()

        assertFalse(report.text.contains(plantedToken))
        assertFalse(report.text.contains(plantedUrl))
        assertFalse(report.text.contains(plantedSubtitleText))
        assertFalse(report.text.contains(plantedLibraryTitle))
        assertFalse(report.text.contains("ApiKey"))
        assertFalse(report.text.contains("http"))
    }

    @Test
    fun `snapshot includes allowed capability and failure-kind context`() = runDiagnosticsTest { scope ->
        val state = MutableStateFlow<PlaybackState>(activeStateWithPlantedSecrets())
        val diagnostics = PlaybackDiagnosticsImpl(scope, state, capabilities(), FakeEngine(), FakePreferences())
        advanceUntilIdle()

        state.value = PlaybackState.Failed(PlaybackFailureKind.AuthorizationExpired)
        advanceUntilIdle()

        val report = diagnostics.snapshot()

        assertTrue(report.text.contains("18000000"))
        assertTrue(report.text.contains("AuthorizationExpired"))
        assertTrue(report.text.contains("Fit"))
        assertTrue(report.text.contains("Zoom"))
        // Still no leakage even after observing a Failed state and capability details.
        assertFalse(report.text.contains(plantedToken))
        assertFalse(report.text.contains(plantedLibraryTitle))
    }

    @Test
    fun `last failure kind remains remembered after leaving the failed state`() = runDiagnosticsTest { scope ->
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        val diagnostics = PlaybackDiagnosticsImpl(scope, state, capabilities(), FakeEngine(), FakePreferences())
        advanceUntilIdle()
        assertTrue(diagnostics.snapshot().text.contains("Last playback failure: none"))

        state.value = PlaybackState.Failed(PlaybackFailureKind.Network)
        advanceUntilIdle()
        state.value = PlaybackState.Idle
        advanceUntilIdle()

        assertEquals(true, diagnostics.snapshot().text.contains("Network"))
    }
}
