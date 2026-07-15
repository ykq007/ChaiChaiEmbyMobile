package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.core.contracts.TrackDelivery
import dev.chaichai.mobile.core.contracts.TrackQualifier
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.playback.PlaybackHost
import dev.chaichai.mobile.platform.adaptive.PlaybackSafePane
import dev.chaichai.mobile.platform.adaptive.PlaybackTracksLayout
import dev.chaichai.mobile.platform.adaptive.PlaybackTracksPresentation
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

    @Test
    fun playback_end_restores_activity_window_state() {
        val playback = FakePlayback()
        var restoreCount = 0
        compose.setContent {
            ChaiChaiTheme(reducedMotion = false) {
                PlaybackHost(playback, onPlaybackEnded = { restoreCount++ })
            }
        }

        compose.runOnIdle {
            playback.mutableState.value = PlaybackState.Exited(MediaIdentity("server", "movie"))
        }
        compose.waitForIdle()

        assertEquals(1, restoreCount)
    }

    @Test
    fun compact_tracks_sheet_labels_current_default_external_and_off_then_selects_safely() {
        val playback = FakePlayback().apply {
            mutableState.value = activeWithTracks()
        }
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) {
                PlaybackHost(playback)
            }
        }

        compose.onNodeWithContentDescription("Tracks").performClick()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithText("Audio").assertIsDisplayed()
        compose.onNodeWithText("English · AAC · Stereo · Default · Current").assertIsDisplayed()
        compose.onNodeWithText("Japanese · AAC · Commentary").assertIsDisplayed()
        compose.onNodeWithText("English · SRT · External · Hearing impaired · Current").assertIsDisplayed()
        compose.onNodeWithText("Off").performClick()

        assertEquals(PlaybackTrackSelection(audioStreamIndex = 1, subtitleStreamIndex = null), playback.selection)
    }

    @Test
    fun qualifying_expanded_playback_anchors_tracks_beside_visible_video() {
        val playback = FakePlayback().apply { mutableState.value = activeWithTracks() }
        compose.setContent {
            ChaiChaiTheme(reducedMotion = false) {
                PlaybackHost(
                    playback,
                    Modifier.size(1000.dp, 700.dp),
                    tracksLayout = PlaybackTracksLayout(
                        PlaybackTracksPresentation.AnchoredSide,
                        PlaybackSafePane.WholeWindow,
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Tracks").performClick()

        compose.onNodeWithTag("tracks-side-sheet", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("PGS · Burn-in required").assertExists()
        compose.onNodeWithText("French · ASS · Embedded").assertExists()
    }

    @Test
    fun separating_hinge_uses_inset_safe_modal_sheet_and_missing_streams_remain_understandable() {
        val playback = FakePlayback()
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) {
                PlaybackHost(
                    playback,
                    Modifier.size(1000.dp, 700.dp),
                    tracksLayout = PlaybackTracksLayout(
                        PlaybackTracksPresentation.ModalBottom,
                        PlaybackSafePane.End(500),
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Tracks").performClick()

        compose.onNodeWithTag("tracks-bottom-sheet", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("No audio tracks available").assertExists()
        compose.onNodeWithText("Off · Current").assertExists()
        compose.onNodeWithText("No subtitle streams available").assertExists()
    }

    @Test
    fun track_failure_explanation_stays_in_playback_and_keeps_tracks_available() {
        val playback = FakePlayback().apply {
            mutableState.value = activeWithTracks().copy(
                trackChangeError = "That track couldn't be applied. The previous track is still playing.",
            )
        }
        compose.setContent { ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback) } }

        compose.onNodeWithContentDescription("Tracks").performClick()

        compose.onNodeWithText("That track couldn't be applied. The previous track is still playing.").assertIsDisplayed()
        compose.onNodeWithTag("playback-screen", useUnmergedTree = true).assertExists()
    }

    private class FakePlayback : NoOpPlaybackCoordinator() {
        val mutableState = MutableStateFlow<PlaybackState>(
            PlaybackState.Active(MediaIdentity("server", "movie"), "Arrival", 600_000_000, 7_200_000_000, false),
        )
        override val state = mutableState
        override val isPlaying = MutableStateFlow(true)
        var seekDelta = 0L
        var playPauseCount = 0
        var exitCount = 0
        var retryCount = 0
        var selection: PlaybackTrackSelection? = null
        override fun submit(request: MediaPlaybackRequest) = Unit
        override fun seekBy(deltaTicks: Long) { seekDelta += deltaTicks }
        override fun playPause() { playPauseCount++ }
        override fun exit() { exitCount++ }
        override fun retry() { retryCount++ }
        override fun selectTrack(selection: PlaybackTrackSelection) { this.selection = selection }
    }

    private fun activeWithTracks() = PlaybackState.Active(
        MediaIdentity("server", "movie"), "Arrival", 600_000_000, 7_200_000_000, false,
        audioTracks = listOf(
            PlaybackTrack(1, PlaybackTrackType.Audio, "eng", "aac", "Stereo", isDefault = true, isCurrent = true),
            PlaybackTrack(2, PlaybackTrackType.Audio, "jpn", "aac", qualifiers = listOf(TrackQualifier.Commentary)),
        ),
        subtitleTracks = listOf(
            PlaybackTrack(
                4, PlaybackTrackType.Subtitle, "eng", "srt", delivery = TrackDelivery.External,
                isCurrent = true, qualifiers = listOf(TrackQualifier.HearingImpaired),
            ),
            PlaybackTrack(5, PlaybackTrackType.Subtitle, codec = "pgs", delivery = TrackDelivery.BurnIn),
            PlaybackTrack(6, PlaybackTrackType.Subtitle, "fra", "ass"),
        ),
    )
}
