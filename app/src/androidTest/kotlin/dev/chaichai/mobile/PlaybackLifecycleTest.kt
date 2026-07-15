package dev.chaichai.mobile

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.MovieDetails
import dev.chaichai.mobile.core.contracts.MovieDetailsState
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.ServerSetupBoundary
import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.platform.adaptive.PlaybackSystemBars
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun active_playback_survives_recreation_rotation_resize_split_screen_and_fold_changes() {
        val restoration = StateRestorationTester(compose)
        val playback = RecordingPlayback(active())
        var size by mutableStateOf(DpSize(400.dp, 700.dp))
        var hinge by mutableStateOf<SeparatingHinge?>(null)
        val systemBars = mutableListOf<PlaybackSystemBars>()
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ChaiChaiTheme(reducedMotion = true) {
                    MobileApp(
                        boundaries(playback = playback),
                        separatingHinge = hinge,
                        modifier = Modifier.size(size.width, size.height),
                        onPlaybackSystemBarsChanged = systemBars::add,
                    )
                }
            }
        }

        compose.onNodeWithText("Arrival").assertIsDisplayed()
        // Compact landscape.
        compose.runOnIdle { size = DpSize(580.dp, 350.dp) }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        // Medium split-screen.
        compose.runOnIdle { size = DpSize(700.dp, 700.dp) }
        compose.onNodeWithText("Arrival").assertIsDisplayed()
        // Expanded/unfolded.
        compose.runOnIdle { size = DpSize(1_000.dp, 700.dp) }
        compose.onNodeWithContentDescription("Tracks").assertIsDisplayed()
        // Vertical separating fold.
        compose.runOnIdle {
            hinge = SeparatingHinge(480, 0, 520, 700, HingeOrientation.Vertical)
        }
        var safeControls = compose.onNodeWithTag("playback-controls", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(safeControls.right <= 480.dp || safeControls.left >= 520.dp)
        // Horizontal tabletop fold.
        compose.runOnIdle {
            hinge = SeparatingHinge(0, 330, 1_000, 370, HingeOrientation.Horizontal)
        }
        safeControls = compose.onNodeWithTag("playback-controls", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(safeControls.bottom <= 330.dp || safeControls.top >= 370.dp)
        // Folded and unfolded again.
        compose.runOnIdle {
            hinge = null
            size = DpSize(420.dp, 700.dp)
        }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnIdle { size = DpSize(1_000.dp, 700.dp) }
        compose.onNodeWithContentDescription("Tracks").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Arrival").assertIsDisplayed()
        assertEquals(MediaIdentity("server", "movie"), (playback.state.value as PlaybackState.Active).identity)
        assertEquals(600_000_000, (playback.state.value as PlaybackState.Active).positionTicks)
        assertEquals(0, playback.submitCount)
        assertEquals(0, playback.exitCount)
        assertTrue(systemBars.contains(PlaybackSystemBars.Immersive))
    }

    @Test
    fun unsafe_process_restore_returns_to_details_without_autoplay() {
        val playback = RecordingPlayback(active())
        val setup = AuthenticatedSetup("movies/server/movie")
        val gateway = object : EmbyGateway {
            override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
            override suspend fun loadMovieDetails(
                identity: MediaIdentity,
                authenticationReturnDestination: String?,
            ) = MovieDetailsState.Ready(MovieDetails(identity, "Arrival"))
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ChaiChaiTheme(reducedMotion = true) {
                MobileApp(boundaries(gateway, playback, setup), null)
            }
        }

        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnIdle {
            // Process death removes the service/coordinator session; only navigation state is safe to restore.
            playback.state.value = PlaybackState.Idle
            playback.isPlaying.value = false
        }
        restoration.emulateSavedInstanceStateRestore()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Arrival").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Arrival").assertIsDisplayed()
        assertEquals(PlaybackState.Idle, playback.state.value)
        assertEquals(0, playback.submitCount)
    }

    private fun active() = PlaybackState.Active(
        MediaIdentity("server", "movie"), "Arrival", 600_000_000, 7_200_000_000, false,
    )

    private fun boundaries(
        gateway: EmbyGateway = object : EmbyGateway {
            override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
        },
        playback: RecordingPlayback,
        setup: ServerSetupBoundary? = null,
    ) = AppBoundaries(
        gateway = gateway,
        playback = playback,
        clock = AppClock { Instant.EPOCH },
        connectivity = object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
        serverSetup = setup,
    )

    private class RecordingPlayback(initial: PlaybackState) : NoOpPlaybackCoordinator() {
        override val state = MutableStateFlow(initial)
        override val isPlaying = MutableStateFlow(initial is PlaybackState.Active && !initial.isPaused)
        var submitCount = 0
        var exitCount = 0
        override fun submit(request: MediaPlaybackRequest) { submitCount++ }
        override fun exit() { exitCount++ }
    }

    private class AuthenticatedSetup(destination: String) : ServerSetupBoundary {
        override val state = MutableStateFlow<ServerSetupState>(
            ServerSetupState.Authenticated("Cinema", "Ada", destination),
        )
        override fun submitAddress(address: String) = Unit
        override fun acceptCleartextRisk() = Unit
        override fun acceptCertificateBypass() = Unit
        override fun confirmServer() = Unit
        override fun authenticate(username: String, password: String) = Unit
        override fun retry() = Unit
        override fun authenticationExpired(requestedDestination: String?) = Unit
    }
}
