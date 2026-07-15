package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chaichai.mobile.core.contracts.AccountBoundary
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackProgressSync
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection
import dev.chaichai.mobile.core.contracts.SignOutState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.playback.PlaybackHost
import dev.chaichai.mobile.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProgressRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun persistentProgressFailureIsSubtleAndRetryableWithoutReplacingPlayback() {
        val coordinator = FakePlaybackCoordinator()
        compose.setContent { ChaiChaiTheme(reducedMotion = false) { PlaybackHost(coordinator) } }

        compose.onNodeWithTag("playback-screen").assertIsDisplayed()
        compose.onNodeWithTag("progress-sync-failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        compose.runOnIdle {
            assertEquals(1, coordinator.progressRetries)
            assert(coordinator.state.value is PlaybackState.Active)
        }
    }

    @Test
    fun persistentProgressFailureRemainsActionableWhenTransportControlsAreHidden() {
        val coordinator = FakePlaybackCoordinator(controlsVisible = false)
        compose.setContent { ChaiChaiTheme(reducedMotion = false) { PlaybackHost(coordinator) } }

        compose.onNodeWithTag("progress-sync-failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, coordinator.progressRetries) }
    }

    @Test
    fun unsynchronizedSignOutRequiresExplicitLossConfirmation() {
        val account = FakeAccount()
        compose.setContent { ChaiChaiTheme(reducedMotion = false) { SettingsScreen(account = account) } }

        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Progress hasn't synced").assertIsDisplayed()
        compose.onNodeWithText("Discard progress and sign out").performClick()

        compose.runOnIdle { assertEquals(1, account.confirmations) }
    }

    private class FakePlaybackCoordinator(controlsVisible: Boolean = true) : PlaybackCoordinator {
        override val isPlaying = MutableStateFlow(true)
        override val state = MutableStateFlow<PlaybackState>(
            PlaybackState.Active(
                MediaIdentity("server-a", "movie"), "Arrival", 500, 1_000, false,
                controlsVisible = controlsVisible,
                progressSync = PlaybackProgressSync.Failed("Watch progress isn't syncing. Check your connection and retry."),
            ),
        )
        var progressRetries = 0
        override fun retryProgressSync() { progressRetries++ }
        override fun submit(request: MediaPlaybackRequest) = Unit
        override fun toggleControls() = Unit
        override fun playPause() = Unit
        override fun seekBy(deltaTicks: Long) = Unit
        override fun seekTo(positionTicks: Long) = Unit
        override fun selectTrack(selection: PlaybackTrackSelection) = Unit
        override fun retry() = Unit
        override fun exit() = Unit
    }

    private class FakeAccount : AccountBoundary {
        override val signOutState = MutableStateFlow<SignOutState>(SignOutState.Idle)
        var confirmations = 0
        override fun requestSignOut() { signOutState.value = SignOutState.ConfirmationRequired() }
        override fun confirmProgressLoss() { confirmations++; signOutState.value = SignOutState.SignedOut }
        override fun cancelSignOut() { signOutState.value = SignOutState.Idle }
    }
}
