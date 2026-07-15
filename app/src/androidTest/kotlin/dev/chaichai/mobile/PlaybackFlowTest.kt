package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.playback.PlaybackHost
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun familiar_cinema_controls_seek_exit_and_toggle_adaptive_affordances() {
        val playback = FakePlayback()
        var orientationToggles = 0
        var fullscreenToggles = 0
        compose.setContent {
            ChaiChaiTheme(reducedMotion = false) {
                PlaybackHost(playback, onToggleOrientation = { orientationToggles++ }, onToggleFullscreen = { fullscreenToggles++ })
            }
        }

        compose.onNodeWithText("Arrival").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rewind 10 seconds").performClick()
        assertEquals(-100_000_000, playback.seekDelta)
        compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
        assertEquals(200_000_000, playback.seekDelta)
        compose.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, playback.playPauseCount)
        compose.onNodeWithContentDescription("Change orientation").performClick()
        compose.onNodeWithContentDescription("Fullscreen").performClick()
        assertEquals(1, orientationToggles)
        assertEquals(1, fullscreenToggles)
        compose.onNodeWithContentDescription("Back to details").performClick()
        assertEquals(1, playback.exitCount)
    }

    @Test
    fun failures_expose_distinct_retry_or_back_actions() {
        val playback = FakePlayback()
        playback.mutableState.value = PlaybackState.Failed(PlaybackFailureKind.Network)
        compose.setContent { ChaiChaiTheme(reducedMotion = false) { PlaybackHost(playback) } }
        compose.onNodeWithText("Network unavailable").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, playback.retryCount)

        compose.runOnIdle { playback.mutableState.value = PlaybackState.Failed(PlaybackFailureKind.UnsupportedMedia) }
        compose.onNodeWithText("Unsupported media").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, playback.exitCount)
    }

    private class FakePlayback : PlaybackCoordinator {
        val mutableState = MutableStateFlow<PlaybackState>(
            PlaybackState.Active(MediaIdentity("server", "movie"), "Arrival", 600_000_000, 7_200_000_000, false),
        )
        override val state = mutableState
        override val isPlaying = MutableStateFlow(true)
        var seekDelta = 0L
        var playPauseCount = 0
        var exitCount = 0
        var retryCount = 0
        override fun submit(request: MediaPlaybackRequest) = Unit
        override fun seekBy(deltaTicks: Long) { seekDelta += deltaTicks }
        override fun playPause() { playPauseCount++ }
        override fun exit() { exitCount++ }
        override fun retry() { retryCount++ }
    }
}
