package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.ServerSetupBoundary
import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ServerSetupFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun user_confirms_cleartext_and_final_address_before_sign_in() {
        val setup = FakeSetup()
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(setup), null) }
        }

        composeRule.onNodeWithText("Server Address").performTextInput("http://media.example/original")
        composeRule.onNodeWithText("Check server").performClick()
        composeRule.onNodeWithText("I understand, continue with HTTP").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("http://media.example/emby").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm and sign in").performClick()
        composeRule.onNodeWithText("Sign in to Cinema").assertIsDisplayed()
        composeRule.onNodeWithText("Username").performTextInput("Ada")
        composeRule.onNodeWithText("Password").performTextInput("secret-password")
        composeRule.onNodeWithText("Sign in", useUnmergedTree = true).performClick()
        composeRule.onNode(hasText("Home") and isHeading()).assertIsDisplayed()

        assertEquals("http://media.example/original", setup.submittedAddress)
        assertEquals("Ada", setup.authenticatedUsername)
        assertEquals("secret-password", setup.authenticatedPassword)
    }

    @Test
    fun reauthentication_returns_to_the_recorded_safe_destination() {
        val setup = FakeSetup().apply {
            state.value = ServerSetupState.Authenticated("Cinema", "Ada", "search")
        }
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(setup), null) }
        }

        composeRule.onNode(hasText("Search") and isHeading()).assertIsDisplayed()
    }

    private class FakeSetup : ServerSetupBoundary {
        override val state = MutableStateFlow<ServerSetupState>(ServerSetupState.EnterAddress())
        var submittedAddress = ""
        var authenticatedUsername = ""
        var authenticatedPassword = ""
        override fun submitAddress(address: String) {
            submittedAddress = address
            state.value = ServerSetupState.CleartextRisk(address)
        }
        override fun acceptCleartextRisk() {
            state.value = ServerSetupState.ConfirmServer(
                submittedAddress, "http://media.example/emby", "Cinema", "4.9.5.0", null,
            )
        }
        override fun acceptCertificateBypass() = Unit
        override fun confirmServer() {
            state.value = ServerSetupState.SignIn("http://media.example/emby", "Cinema")
        }
        override fun authenticate(username: String, password: String) {
            authenticatedUsername = username
            authenticatedPassword = password
            state.value = ServerSetupState.Authenticated("Cinema", username)
        }
        override fun retry() = Unit
        override fun authenticationExpired(requestedDestination: String?) = Unit
    }

    private fun boundaries(setup: ServerSetupBoundary) = AppBoundaries(
        gateway = object : EmbyGateway { override val connectionState = MutableStateFlow(GatewayConnectionState.Disconnected) },
        playback = object : PlaybackCoordinator { override val isPlaying = MutableStateFlow(false) },
        clock = AppClock { Instant.EPOCH },
        connectivity = object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
        serverSetup = setup,
    )
}
