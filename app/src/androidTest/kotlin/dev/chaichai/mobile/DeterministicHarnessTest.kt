package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        val boundaries = AppBoundaries(
            gateway = object : EmbyGateway {
                override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
            },
            playback = object : PlaybackCoordinator {
                override val isPlaying = MutableStateFlow(false)
            },
            clock = AppClock { Instant.parse("2026-01-01T00:00:00Z") },
            connectivity = object : ConnectivityMonitor {
                override val isOnline = MutableStateFlow(true)
            },
        )

        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) {
                MobileApp(boundaries, hasSeparatingVerticalHinge = false)
            }
        }

        composeRule.onNode(hasText("Home") and isHeading()).assertIsDisplayed()
    }
}
