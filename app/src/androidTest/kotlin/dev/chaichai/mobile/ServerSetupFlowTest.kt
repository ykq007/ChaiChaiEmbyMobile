package dev.chaichai.mobile

import android.content.Context
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
import dev.chaichai.mobile.platform.server.AuthenticatedEmbyGateway
import dev.chaichai.mobile.platform.server.EmbyAuthenticator
import dev.chaichai.mobile.platform.server.EmbyProbe
import dev.chaichai.mobile.platform.server.KeystoreSessionVault
import dev.chaichai.mobile.platform.server.ServerSetupCoordinator
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ServerSetupFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun supported_4_8_server_completes_binding_setup_restore_and_expiry_flow() =
        bindingServerFlow("4.8.11.0")

    @Test
    fun supported_4_9_server_completes_binding_setup_restore_and_expiry_flow() =
        bindingServerFlow("4.9.5.0")

    private fun bindingServerFlow(version: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreSessionVault(context).also { it.clear() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(publicInfo(version))
            server.enqueue(authenticated("token-one"))
            repeat(5) { server.enqueue(MockResponse.Builder().body("{\"Items\":[]}").build()) }
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(authenticated("token-two"))
            server.enqueue(MockResponse.Builder().body("{}").build())
            val gateway = AuthenticatedEmbyGateway(vault)
            val setup = ServerSetupCoordinator(
                scope, EmbyProbe(), EmbyAuthenticator(), vault, "binding-device", gateway,
            )
            gateway.onAuthenticationExpired = setup::authenticationExpired
            composeRule.setContent {
                ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(setup, gateway), null) }
            }

            val serverAddress = server.url("/emby/").toString()
            composeRule.onNodeWithText("Server Address").performTextInput(serverAddress)
            composeRule.onNodeWithText("Check server").performClick()
            composeRule.onNodeWithText("I understand, continue with HTTP").assertIsDisplayed().performClick()
            composeRule.onNodeWithText("Confirm and sign in").performClick()
            composeRule.onNodeWithText("Username").performTextInput("Ada")
            composeRule.onNodeWithText("Password").performTextInput("secret-password")
            composeRule.onNodeWithText("Sign in", useUnmergedTree = true).performClick()
            composeRule.onNode(hasText("Home") and isHeading()).assertIsDisplayed()
            assertEquals("user", vault.restore()!!.userId)

            composeRule.onNodeWithText("Search").performClick()
            composeRule.onNodeWithText("Sign in to Cinema").assertIsDisplayed()
            composeRule.onNodeWithText("Password").performTextInput("replacement-password")
            composeRule.onNodeWithText("Sign in", useUnmergedTree = true).performClick()
            composeRule.onNode(hasText("Search") and isHeading()).assertIsDisplayed()

            val restoredGateway = AuthenticatedEmbyGateway(vault)
            val restored = ServerSetupCoordinator(
                scope, EmbyProbe(), EmbyAuthenticator(), vault, "binding-device", restoredGateway,
            )
            assertTrue(runBlocking {
                withTimeout(5_000) { restored.state.first { it !is ServerSetupState.Restoring } }
            } is ServerSetupState.Authenticated)
        }
        vault.clear()
        scope.cancel()
    }

    @Test
    fun fake_seam_confirms_cleartext_and_final_address_before_sign_in() {
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

    private fun publicInfo(version: String) = MockResponse.Builder()
        .body("""{"Id":"server","ServerName":"Cinema","Version":"$version"}""")
        .build()

    private fun authenticated(token: String) = MockResponse.Builder()
        .body(
            """{"AccessToken":"$token","User":{"Id":"user","Name":"Ada","Policy":{"IsDisabled":false,"EnableMediaPlayback":true}}}""",
        )
        .build()

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

    private fun boundaries(
        setup: ServerSetupBoundary,
        gateway: EmbyGateway = object : EmbyGateway {
            override val connectionState = MutableStateFlow(GatewayConnectionState.Disconnected)
        },
    ) = AppBoundaries(
        gateway = gateway,
        playback = object : PlaybackCoordinator { override val isPlaying = MutableStateFlow(false) },
        clock = AppClock { Instant.EPOCH },
        connectivity = object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
        serverSetup = setup,
    )
}
