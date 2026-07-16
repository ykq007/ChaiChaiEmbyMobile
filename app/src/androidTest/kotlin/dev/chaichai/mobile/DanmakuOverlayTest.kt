package dev.chaichai.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chaichai.mobile.core.contracts.DanmakuComment
import dev.chaichai.mobile.core.contracts.DanmakuController
import dev.chaichai.mobile.core.contracts.DanmakuOverlaySnapshot
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuUnavailableReason
import dev.chaichai.mobile.core.contracts.DanmakuVisibleComment
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.playback.PlaybackHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * App-level acceptance for the danmaku overlay using an INDEPENDENTLY AUTHORED fake controller
 * (no platform code, no TV Client logic). Verifies that danmaku is contained: enabling shows the
 * overlay and status, and a failure surfaces as danmaku status while playback keeps running.
 */
class DanmakuOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun activePlayback() = object : NoOpPlaybackCoordinator() {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(
            PlaybackState.Active(
                identity = MediaIdentity("server", "movie"),
                title = "Arrival",
                positionTicks = 20_000_000,
                runtimeTicks = 7_200_000_000,
                isPaused = false,
                scope = HomeScope("server", "user"),
            ),
        )
    }

    @Test
    fun enabling_danmaku_shows_overlay_and_status_without_disrupting_playback() {
        val danmaku = FakeDanmaku()
        val playback = activePlayback()
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback, danmaku = danmaku) }
        }

        // Off by default: no overlay yet, but playback controls are present.
        compose.onNodeWithTag("danmaku-overlay").assertDoesNotExist()
        compose.onNodeWithText("Arrival").assertIsDisplayed()

        // Toggle danmaku on -> fake matches and renders.
        compose.onNodeWithContentDescription("Turn danmaku on").performClick()
        assertTrue(danmaku.enabledCalls.last())
        danmaku.emitActive()

        compose.onNodeWithTag("danmaku-overlay").assertExists()
        compose.onNodeWithTag("danmaku-status").assertExists()
        compose.onNodeWithText("こんにちは", substring = true).assertExists()
        // Playback stays fully usable.
        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun endpoint_failure_shows_contained_status_and_playback_continues() {
        val danmaku = FakeDanmaku()
        val playback = activePlayback()
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback, danmaku = danmaku) }
        }
        compose.onNodeWithContentDescription("Turn danmaku on").performClick()
        danmaku.emitUnavailable("Couldn't reach danmaku endpoint. Media is playing normally.")

        compose.onNodeWithTag("danmaku-status").assertExists()
        compose.onNodeWithText("Couldn't reach danmaku endpoint. Media is playing normally.").assertIsDisplayed()
        // Failure never becomes a playback failure: transport controls remain.
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Forward 30 seconds").assertExists()
    }

    private class FakeDanmaku : DanmakuController {
        private val mutable = MutableStateFlow<DanmakuState>(DanmakuState.Disabled)
        override val state: StateFlow<DanmakuState> = mutable
        val enabledCalls = mutableListOf<Boolean>()

        override fun setEnabled(enabled: Boolean) {
            enabledCalls.add(enabled)
            mutable.value = if (enabled) {
                DanmakuState.Matching(MediaIdentity("server", "movie"))
            } else {
                DanmakuState.Disabled
            }
        }

        override fun attach(identity: MediaIdentity, scope: HomeScope, title: String, runtimeTicks: Long) = Unit
        override fun onPlayback(positionTicks: Long, isPaused: Boolean, speed: Float) = Unit
        override fun detach() = Unit

        fun emitActive() {
            val comment = DanmakuComment(20_000_000, "こんにちは")
            mutable.value = DanmakuState.Active(
                endpointName = "Community",
                matchedTitle = "Arrival",
                totalComments = 1,
                overlay = DanmakuOverlaySnapshot(
                    positionTicks = 20_000_000,
                    isPaused = false,
                    speed = 1.0f,
                    laneCount = 1,
                    visible = listOf(DanmakuVisibleComment(comment, lane = 0, progress = 0.2f)),
                ),
            )
        }

        fun emitUnavailable(message: String) {
            mutable.value = DanmakuState.Unavailable(DanmakuUnavailableReason.EndpointUnreachable, message)
        }
    }
}
