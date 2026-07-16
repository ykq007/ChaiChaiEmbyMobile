package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chaichai.mobile.core.contracts.AccountBoundary
import dev.chaichai.mobile.core.contracts.DiagnosticsReport
import dev.chaichai.mobile.core.contracts.PlaybackDiagnostics
import dev.chaichai.mobile.core.contracts.SignOutState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * App-level coverage of the opt-in playback diagnostics Settings surface (Playback Polish, #35 AC3):
 * OFF by default, the scope explanation is always visible, opt-in reveals the (fake, but
 * production-shaped) redacted report, and the enabled state persists through the SAME boundary a real
 * recreation would keep alive (the boundary instance itself, mirroring how other Settings sections in
 * this suite prove persistence through their boundary rather than a full Activity restart).
 */
class PlaybackDiagnosticsSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    private class FakeDiagnostics : PlaybackDiagnostics {
        private val mutableEnabled = MutableStateFlow(false)
        override val enabled = mutableEnabled
        var snapshotCallCount = 0
        override fun setEnabled(enabled: Boolean) { mutableEnabled.value = enabled }
        override fun snapshot(): DiagnosticsReport {
            snapshotCallCount++
            return DiagnosticsReport(
                "Playback diagnostics (redacted)\n" +
                    "Negotiated max bitrate: 18000000 kbps\n" +
                    "Last playback failure: none",
            )
        }
    }

    private val account = object : AccountBoundary {
        override val signOutState = MutableStateFlow<SignOutState>(SignOutState.Idle)
        override fun requestSignOut() = Unit
        override fun confirmProgressLoss() = Unit
        override fun cancelSignOut() = Unit
    }

    private fun render(diagnostics: PlaybackDiagnostics) {
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) {
                SettingsScreen(account = account, playbackDiagnostics = diagnostics)
            }
        }
    }

    @Test fun diagnostics_are_off_by_default_and_explain_their_scope_before_opt_in() {
        val diagnostics = FakeDiagnostics()
        render(diagnostics)

        composeRule.onNodeWithTag("diagnostics-enable-switch").performScrollTo().assertIsDisplayed()
        assertFalse(diagnostics.enabled.value)
        // The scope explanation is visible even before opting in.
        composeRule.onNodeWithText(
            "Off by default. When on, this only captures device and server playback CAPABILITY " +
                "(supported formats, scale-mode support) and the KIND of the most recent playback " +
                "failure — never a server token, a full media or stream URL, subtitle text, or a " +
                "library title.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("view-diagnostics-report").assertDoesNotExist()
    }

    @Test fun opting_in_reveals_the_redacted_report_and_persists_through_the_boundary() {
        val diagnostics = FakeDiagnostics()
        render(diagnostics)

        composeRule.onNodeWithTag("diagnostics-enable-switch").performScrollTo().performClick()
        assertTrue(diagnostics.enabled.value)

        composeRule.onNodeWithTag("view-diagnostics-report").performScrollTo().performClick()
        composeRule.onNodeWithTag("diagnostics-report-text").assertIsDisplayed()
        composeRule.onNodeWithText("Negotiated max bitrate: 18000000 kbps", substring = true).assertIsDisplayed()

        // Turning it back off hides the report affordance again without losing the boundary's own state.
        composeRule.onNodeWithTag("diagnostics-enable-switch").performScrollTo().performClick()
        assertFalse(diagnostics.enabled.value)
        composeRule.onNodeWithTag("view-diagnostics-report").assertDoesNotExist()
    }
}
