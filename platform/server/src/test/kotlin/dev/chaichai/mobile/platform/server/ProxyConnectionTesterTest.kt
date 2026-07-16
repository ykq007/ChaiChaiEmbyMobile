package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real proxy path end to end with MockWebServer acting as an HTTP proxy: the shared
 * [AuthorityScopedHttpClients] routes traffic through it, and [ProxyConnectionTester] classifies the
 * distinguished outcomes.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyConnectionTesterTest {
    private fun address(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address

    private fun session(serverId: String = "s") = StoredSession(
        address("http://target.invalid/emby"),
        serverId,
        "u",
        "User",
        AccessToken.fromRaw("token"),
        certificateBypassAuthority = null,
        serverName = "Target",
    )

    private class FixedVault(private val session: StoredSession) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) = Unit
        override fun clear() = Unit
        override fun sessions() = listOf(session)
    }

    private fun wiring(proxyHost: String, proxyPort: Int, config: ServerProxyConfig, creds: ProxyCredentials?):
        Pair<AuthorityScopedHttpClients, ProxyConnectionTester> {
        val vault = FixedVault(session())
        val store = ServerProxyStore(InMemoryRegistryPersistence(), InMemoryProxyCredentialVault())
        store.update("s", config.copy(host = proxyHost, port = proxyPort), creds)
        val clients = AuthorityScopedHttpClients(VaultBackedProxySelector(vault, store))
        return clients to ProxyConnectionTester(vault, store, clients)
    }

    @Test fun reachable_proxy_returning_success_classifies_as_success() = runTest {
        MockWebServer().use { proxy ->
            proxy.start()
            proxy.enqueue(MockResponse.Builder().code(200).body("{}").build())
            val config = ServerProxyConfig(ProxyKind.Http, enabled = true)
            val (_, tester) = wiring(proxy.hostName, proxy.port, config, null)
            assertEquals(ProxyTestResult.Success, tester.test("s"))
            // The request really went through the proxy.
            assertEquals(1, proxy.requestCount)
        }
    }

    @Test fun proxy_407_without_credentials_is_authentication_failure() = runTest {
        MockWebServer().use { proxy ->
            proxy.start()
            proxy.enqueue(MockResponse.Builder().code(407).addHeader("Proxy-Authenticate", "Basic realm=\"x\"").build())
            val config = ServerProxyConfig(ProxyKind.Http, enabled = true)
            val (_, tester) = wiring(proxy.hostName, proxy.port, config, null)
            assertEquals(ProxyTestResult.ProxyAuthenticationFailed, tester.test("s"))
        }
    }

    @Test fun credentials_answer_a_407_and_reach_the_server() = runTest {
        MockWebServer().use { proxy ->
            proxy.start()
            proxy.enqueue(MockResponse.Builder().code(407).addHeader("Proxy-Authenticate", "Basic realm=\"x\"").build())
            proxy.enqueue(MockResponse.Builder().code(200).body("{}").build())
            val config = ServerProxyConfig(ProxyKind.Http, enabled = true, hasCredentials = true)
            val (_, tester) = wiring(proxy.hostName, proxy.port, config, ProxyCredentials("alice", "s3cret"))
            assertEquals(ProxyTestResult.Success, tester.test("s"))
            proxy.takeRequest() // initial (challenged)
            val authed = proxy.takeRequest()
            assertTrue(authed.headers["Proxy-Authorization"].orEmpty().startsWith("Basic "))
            // The proxy secret went ONLY to the proxy, never to the target server.
            assertEquals(null, authed.headers["Authorization"])
        }
    }

    @Test fun invalid_proxy_config_short_circuits_before_io() = runTest {
        val vault = FixedVault(session())
        val store = ServerProxyStore(InMemoryRegistryPersistence(), InMemoryProxyCredentialVault())
        store.update("s", ServerProxyConfig(ProxyKind.Http, host = "", port = 0, enabled = true), null)
        val clients = AuthorityScopedHttpClients(VaultBackedProxySelector(vault, store))
        val tester = ProxyConnectionTester(vault, store, clients)
        assertEquals(ProxyTestResult.InvalidConfiguration, tester.test("s"))
    }

    @Test fun api_traffic_shares_the_same_proxy_route_via_forRequest() = runTest {
        MockWebServer().use { proxy ->
            proxy.start()
            proxy.enqueue(MockResponse.Builder().code(200).body("{}").build())
            val session = session()
            val (clients, _) = wiring(proxy.hostName, proxy.port, ServerProxyConfig(ProxyKind.Http, enabled = true), null)
            // A client obtained through the shared forRequest path (as every gateway does) proxies.
            val client = clients.forRequest(session.address.authority, null)
            client.newCall(Request.Builder().url(session.address.apiUrl("System/Info/Public").toString()).build())
                .execute().use { assertEquals(200, it.code) }
            assertEquals(1, proxy.requestCount)
        }
    }
}
