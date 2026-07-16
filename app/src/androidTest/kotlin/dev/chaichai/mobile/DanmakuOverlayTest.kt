package dev.chaichai.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.chaichai.mobile.core.contracts.DanmakuComment
import dev.chaichai.mobile.core.contracts.DanmakuController
import dev.chaichai.mobile.core.contracts.DanmakuMatchOption
import dev.chaichai.mobile.core.contracts.DanmakuMatchOptions
import dev.chaichai.mobile.core.contracts.DanmakuMediaKey
import dev.chaichai.mobile.core.contracts.DanmakuOverlaySnapshot
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuTuning
import dev.chaichai.mobile.core.contracts.DanmakuUnavailableReason
import dev.chaichai.mobile.core.contracts.DanmakuVisibleComment
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.playback.PlaybackHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * App-level acceptance for the danmaku overlay and its single entry point using an INDEPENDENTLY
 * AUTHORED fake controller (no platform code, no TV Client logic; extends the #26 fake with the
 * #27 manual-correction and tuning surface). Verifies that danmaku is contained: enabling shows the
 * overlay and status, a failure surfaces as danmaku status while playback keeps running, and the
 * single Danmaku entry opens a panel for search/select/tuning.
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

        // The single Danmaku entry opens the panel; enabling there drives the fake controller.
        compose.onNodeWithContentDescription("Danmaku").performClick()
        compose.onNodeWithTag("danmaku-panel").assertExists()
        compose.onNodeWithTag("danmaku-enable-switch").performClick()
        assertTrue(danmaku.enabledCalls.last())
        danmaku.emitActive()

        compose.onNodeWithTag("danmaku-overlay").assertExists()
        // Close the panel to check the status badge and transport in the collapsed header.
        compose.onNodeWithContentDescription("Close Danmaku").performClick()
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
        compose.onNodeWithContentDescription("Danmaku").performClick()
        compose.onNodeWithTag("danmaku-enable-switch").performClick()
        compose.onNodeWithContentDescription("Close Danmaku").performClick()
        danmaku.emitUnavailable("Couldn't reach danmaku endpoint. Media is playing normally.")

        compose.onNodeWithTag("danmaku-status").assertExists()
        compose.onNodeWithText("Couldn't reach danmaku endpoint. Media is playing normally.").assertIsDisplayed()
        // Failure never becomes a playback failure: transport controls remain.
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Forward 30 seconds").assertExists()
    }

    @Test
    fun searching_and_selecting_a_match_updates_status() {
        val danmaku = FakeDanmaku()
        val playback = activePlayback()
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback, danmaku = danmaku) }
        }
        compose.onNodeWithContentDescription("Danmaku").performClick()
        compose.onNodeWithTag("danmaku-enable-switch").performClick()
        danmaku.emitUnavailable("No danmaku match found for \"Arrival\".")

        compose.onNodeWithTag("danmaku-search-field").performTextInput("Arrival (2016)")
        compose.onNodeWithTag("danmaku-search-button").performClick()
        assertEquals("Arrival (2016)", danmaku.searchQueries.last())
        danmaku.emitSearchResults(listOf(DanmakuMatchOption("candidate-1", "Arrival (2016)")))

        compose.onNodeWithTag("danmaku-match-candidate-1").performClick()
        assertEquals("candidate-1", danmaku.selectedCandidateId)
        danmaku.emitActive()
        compose.onNodeWithText("Danmaku on · Community", substring = true).assertExists()
    }

    @Test
    fun tuning_a_slider_announces_via_live_region() {
        val danmaku = FakeDanmaku()
        val playback = activePlayback()
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(playback, danmaku = danmaku) }
        }
        compose.onNodeWithContentDescription("Danmaku").performClick()
        compose.onNodeWithTag("danmaku-enable-switch").performClick()
        danmaku.emitActive()

        compose.onNodeWithTag("danmaku-tuning-speed").assertExists()
        compose.onNodeWithText("1.0×").assertExists()
    }

    private class FakeDanmaku : DanmakuController {
        private val mutable = MutableStateFlow<DanmakuState>(DanmakuState.Disabled)
        override val state: StateFlow<DanmakuState> = mutable
        private val mutableMatchOptions = MutableStateFlow(DanmakuMatchOptions())
        override val matchOptions: StateFlow<DanmakuMatchOptions> = mutableMatchOptions
        private val mutableTuning = MutableStateFlow(DanmakuTuning.Neutral)
        override val tuning: StateFlow<DanmakuTuning> = mutableTuning

        val enabledCalls = mutableListOf<Boolean>()
        val searchQueries = mutableListOf<String>()
        var selectedCandidateId: String? = null

        override fun setEnabled(enabled: Boolean) {
            enabledCalls.add(enabled)
            mutable.value = if (enabled) {
                DanmakuState.Matching(MediaIdentity("server", "movie"))
            } else {
                DanmakuState.Disabled
            }
        }

        override fun attach(
            identity: MediaIdentity,
            scope: HomeScope,
            title: String,
            runtimeTicks: Long,
            mediaKey: DanmakuMediaKey?,
        ) = Unit

        override fun onPlayback(positionTicks: Long, isPaused: Boolean, speed: Float) = Unit
        override fun detach() = Unit

        override fun searchMatches(query: String) {
            searchQueries.add(query)
            mutableMatchOptions.value = DanmakuMatchOptions(query = query, isSearching = true)
        }

        override fun selectMatch(candidateId: String) {
            selectedCandidateId = candidateId
        }

        override fun clearMatch() = Unit

        override fun updateTuning(tuning: DanmakuTuning) {
            mutableTuning.value = tuning
        }

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
                tuning = mutableTuning.value,
            )
        }

        fun emitUnavailable(message: String) {
            mutable.value = DanmakuState.Unavailable(DanmakuUnavailableReason.EndpointUnreachable, message)
        }

        fun emitSearchResults(results: List<DanmakuMatchOption>) {
            mutableMatchOptions.value = DanmakuMatchOptions(isSearching = false, results = results)
        }
    }
}
