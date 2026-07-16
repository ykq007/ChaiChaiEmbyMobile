package dev.chaichai.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chaichai.mobile.core.contracts.DanmakuController
import dev.chaichai.mobile.core.contracts.DanmakuMatchOptions
import dev.chaichai.mobile.core.contracts.DanmakuMediaKey
import dev.chaichai.mobile.core.contracts.DanmakuOverlaySnapshot
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuTuning
import dev.chaichai.mobile.core.contracts.DanmakuVisibleComment
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MarkerKind
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.core.contracts.SkipTarget
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.playback.PlaybackHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * App-level acceptance for Skip trustworthy intro/outro markers (issue #34): the Skip control only
 * appears while a marker is currently offered, activating it seeks and keeps playback/tracks intact,
 * it stays clear of the primary transport row, and Danmaku stays synced to the new position — all
 * against an INDEPENDENTLY AUTHORED fake coordinator that mimics the real seek-preserves-everything
 * contract (no platform code).
 */
class PlaybackSkipMarkersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun skip_intro_appears_seeks_and_disappears_once_past_the_marker() {
        val playback = FakePlayback(
            withIntro(positionTicks = 10_000_000),
        )
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback) }
        }

        compose.onNodeWithTag("skip-intro").assertIsDisplayed()
        compose.onNodeWithContentDescription("Skip intro").performClick()

        assertEquals(1, playback.skipCalls.size)
        assertEquals(900_000_000L, (playback.state.value as PlaybackState.Active).positionTicks)
        compose.onNodeWithTag("skip-intro").assertDoesNotExist()
        // Playback continues uninterrupted and pause/tracks are untouched.
        compose.onNodeWithContentDescription("Pause").assertExists()
        assertTrue((playback.state.value as PlaybackState.Active).audioTracks.single().isCurrent)
    }

    @Test
    fun no_skip_target_hides_the_control() {
        val playback = FakePlayback(
            PlaybackState.Active(
                MediaIdentity("server", "movie"), "Arrival", 5_000_000_000, 7_200_000_000, false,
            ),
        )
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback) }
        }

        compose.onNodeWithTag("skip-intro").assertDoesNotExist()
        compose.onNodeWithTag("skip-outro").assertDoesNotExist()
    }

    @Test
    fun skip_control_never_overlaps_the_primary_transport_row() {
        val playback = FakePlayback(withIntro(positionTicks = 10_000_000))
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback) }
        }

        val skipBounds = compose.onNodeWithTag("skip-intro", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val transportBounds = compose.onNodeWithTag("playback-transport", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "expected skip control $skipBounds to stay clear of transport $transportBounds",
            skipBounds.bottom <= transportBounds.top || skipBounds.top >= transportBounds.bottom,
        )
    }

    @Test
    fun activating_skip_updates_the_position_danmaku_sees() {
        val playback = FakePlayback(withIntro(positionTicks = 10_000_000))
        val danmaku = RecordingDanmaku()
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback, danmaku = danmaku) }
        }

        compose.onNodeWithContentDescription("Skip intro").performClick()
        // The danmaku bridge forwards the new position from a LaunchedEffect keyed on
        // positionTicks; give that recomposition+effect a chance to run before reading it.
        compose.waitForIdle()

        assertEquals(900_000_000L, danmaku.onPlaybackCalls.last().first)
    }

    private fun withIntro(positionTicks: Long) = PlaybackState.Active(
        identity = MediaIdentity("server", "movie"),
        title = "Arrival",
        positionTicks = positionTicks,
        runtimeTicks = 7_200_000_000,
        isPaused = false,
        scope = HomeScope("server", "user"),
        audioTracks = listOf(PlaybackTrack(1, PlaybackTrackType.Audio, "eng", isCurrent = true)),
        skipTargets = listOf(SkipTarget(MarkerKind.Intro, "Skip intro", 900_000_000)),
    )

    private class FakePlayback(initial: PlaybackState.Active) : NoOpPlaybackCoordinator() {
        val mutableState = MutableStateFlow<PlaybackState>(initial)
        override val state: StateFlow<PlaybackState> = mutableState
        override val isPlaying = MutableStateFlow(!initial.isPaused)
        val skipCalls = mutableListOf<SkipTarget>()

        override fun skip(target: SkipTarget) {
            val current = mutableState.value as? PlaybackState.Active ?: return
            if (target !in current.skipTargets) return
            skipCalls += target
            // Mirrors PlaybackCoordinatorImpl.skip: reuse the seek path, keep everything else,
            // recompute skipTargets against the new position (past the intro window -> empty).
            mutableState.value = current.copy(
                positionTicks = target.seekToTicks,
                skipTargets = emptyList(),
            )
        }
    }

    private class RecordingDanmaku : DanmakuController {
        private val mutable = MutableStateFlow<DanmakuState>(DanmakuState.Disabled)
        override val state: StateFlow<DanmakuState> = mutable
        override val matchOptions: StateFlow<DanmakuMatchOptions> = MutableStateFlow(DanmakuMatchOptions())
        override val tuning: StateFlow<DanmakuTuning> = MutableStateFlow(DanmakuTuning.Neutral)
        val onPlaybackCalls = mutableListOf<Triple<Long, Boolean, Float>>()

        override fun setEnabled(enabled: Boolean) = Unit
        override fun attach(
            identity: MediaIdentity,
            scope: HomeScope,
            title: String,
            runtimeTicks: Long,
            mediaKey: DanmakuMediaKey?,
        ) {
            mutable.value = DanmakuState.Active(
                endpointName = "Community",
                matchedTitle = title,
                totalComments = 0,
                overlay = DanmakuOverlaySnapshot(
                    positionTicks = 0,
                    isPaused = false,
                    speed = 1.0f,
                    laneCount = 1,
                    visible = emptyList<DanmakuVisibleComment>(),
                ),
                tuning = DanmakuTuning.Neutral,
            )
        }
        override fun onPlayback(positionTicks: Long, isPaused: Boolean, speed: Float) {
            onPlaybackCalls += Triple(positionTicks, isPaused, speed)
        }
        override fun detach() = Unit
        override fun searchMatches(query: String) = Unit
        override fun selectMatch(candidateId: String) = Unit
        override fun clearMatch() = Unit
        override fun updateTuning(tuning: DanmakuTuning) = Unit
    }
}
