package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class DeterministicHarnessTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun controllable_boundaries_drive_the_real_app_shell() {
        var separatingHinge by mutableStateOf<SeparatingHinge?>(null)
        val gatewayState = MutableStateFlow(GatewayConnectionState.Connected)
        val playbackState = MutableStateFlow(true)
        val connectivityState = MutableStateFlow(false)
        val boundaries = AppBoundaries(
            gateway = object : EmbyGateway {
                override val connectionState = gatewayState
            },
            playback = object : PlaybackCoordinator {
                override val isPlaying = playbackState
            },
            clock = AppClock { Instant.parse("2026-01-01T00:00:00Z") },
            connectivity = object : ConnectivityMonitor {
                override val isOnline = connectivityState
            },
        )

        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) {
                MobileApp(boundaries, separatingHinge = separatingHinge)
            }
        }

        composeRule.onNode(hasText("Home") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Offline • Connected • Playback active • Checked 00:00 UTC",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNode(hasText("Settings") and isHeading()).assertIsDisplayed()

        composeRule.runOnIdle {
            separatingHinge = SeparatingHinge(
                leftPx = 500,
                topPx = 0,
                rightPx = 520,
                bottomPx = 2_000,
                orientation = HingeOrientation.Vertical,
            )
        }
        composeRule.onNode(hasText("Settings") and isHeading()).assertIsDisplayed()
    }
}
