package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.core.contracts.SetupFailure
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSetupCoordinatorTest {
    @Test
    fun certificate_bypass_confirmation_scopes_to_the_failed_redirect_authority() = runTest {
        val entered = valid("https://one.example/emby")
        val redirected = valid("https://two.example/emby")
        val probe = SequencedProbe(
            mutableListOf(
                ProbeResult.Failure(ProbeFailure.Tls, redirected),
                ProbeResult.Success(entered, redirected, server(), 1),
            ),
        )
        val coordinator = ServerSetupCoordinator(this, probe, FakeAuthenticator(success(redirected)), FakeVault(), "device")
        advanceUntilIdle()

        coordinator.submitAddress(entered.value)
        advanceUntilIdle()
        assertEquals(redirected.value, (coordinator.state.value as ServerSetupState.CertificateRisk).address)
        coordinator.acceptCertificateBypass()
        advanceUntilIdle()

        assertEquals(redirected.authority, probe.bypasses.last())
        assertTrue(coordinator.state.value is ServerSetupState.ConfirmServer)
    }
    @Test
    fun setup_requires_cleartext_and_redirect_confirmation_before_authentication() = runTest {
        val entered = valid("http://media.example/original")
        val final = valid("http://media.example/emby")
        val probe = FakeProbe(ProbeResult.Success(entered, final, server(), 1))
        val authenticator = FakeAuthenticator(success(final))
        val coordinator = ServerSetupCoordinator(this, probe, authenticator, FakeVault(), "device")
        advanceUntilIdle()

        coordinator.submitAddress(entered.value)
        assertTrue(coordinator.state.value is ServerSetupState.CleartextRisk)
        coordinator.acceptCleartextRisk()
        advanceUntilIdle()
        val confirmation = coordinator.state.value as ServerSetupState.ConfirmServer
        assertEquals(final.value, confirmation.finalAddress)
        assertEquals(0, authenticator.passwords.size)

        coordinator.confirmServer()
        coordinator.authenticate("Ada", "secret-password")
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ServerSetupState.Authenticated)
        assertEquals(listOf("secret-password"), authenticator.passwords)
    }

    @Test
    fun rejected_password_is_discarded_while_address_and_username_are_retained() = runTest {
        val address = valid("https://media.example/emby")
        val coordinator = ServerSetupCoordinator(
            this,
            FakeProbe(ProbeResult.Success(address, address, server(), 0)),
            FakeAuthenticator(AuthenticationResult.Failure(AuthenticationFailure.InvalidCredentials)),
            FakeVault(),
            "device",
        )
        advanceUntilIdle()
        coordinator.submitAddress(address.value)
        advanceUntilIdle()
        coordinator.confirmServer()
        coordinator.authenticate("Ada", "rejected-secret")
        advanceUntilIdle()

        val signIn = coordinator.state.value as ServerSetupState.SignIn
        assertEquals(address.value, signIn.address)
        assertEquals("Ada", signIn.username)
        assertEquals(SetupFailure.InvalidCredentials, signIn.error)
        assertFalse(signIn.toString().contains("rejected-secret"))
    }

    @Test
    fun saved_server_user_session_restores_and_expiration_preserves_safe_return_destination() = runTest {
        val address = valid("https://media.example/emby")
        val vault = FakeVault(stored(address))
        val coordinator = ServerSetupCoordinator(
            this, FakeProbe(ProbeResult.Failure(ProbeFailure.Unreachable)), FakeAuthenticator(success(address)), vault, "device",
        )
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ServerSetupState.Authenticated)

        coordinator.authenticationExpired("libraries")
        val signIn = coordinator.state.value as ServerSetupState.SignIn
        assertEquals("Ada", signIn.username)
        assertNull(vault.session)
        coordinator.authenticate("Ada", "new-secret")
        advanceUntilIdle()

        assertEquals(
            "libraries",
            (coordinator.state.value as ServerSetupState.Authenticated).returnDestination,
        )
    }

    private class FakeProbe(private val result: ProbeResult) : ServerProbe {
        override suspend fun probe(initialAddress: ServerAddress, certificateBypassAuthority: ServerAuthority?) = result
    }

    private class SequencedProbe(private val results: MutableList<ProbeResult>) : ServerProbe {
        val bypasses = mutableListOf<ServerAuthority?>()
        override suspend fun probe(initialAddress: ServerAddress, certificateBypassAuthority: ServerAuthority?): ProbeResult {
            bypasses += certificateBypassAuthority
            return results.removeAt(0)
        }
    }

    private class FakeAuthenticator(private val result: AuthenticationResult) : InteractiveAuthenticator {
        val passwords = mutableListOf<String>()
        override suspend fun authenticate(
            address: ServerAddress, serverId: String, username: String, password: String,
            deviceId: String, certificateBypassAuthority: ServerAuthority?,
        ): AuthenticationResult {
            passwords += password
            return result
        }
    }

    private class FakeVault(var session: StoredSession? = null) : SessionVault {
        override fun restore(): StoredSession? = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null }
    }

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
    private fun server() = DiscoveredServer("server", "Cinema", "4.9.5.0", Compatibility.Supported)
    private fun success(address: ServerAddress) = AuthenticationResult.Success(
        AuthenticatedSession(address, "server", "user", "Ada", "token"),
    )
    private fun stored(address: ServerAddress) = StoredSession(address, "server", "user", "Ada", "token", null, "Cinema")
}
