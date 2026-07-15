package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.GatewayAuthenticationStatus
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthenticatedEmbyGatewayTest {
    @Test
    fun authenticated_request_uses_scoped_token_and_reports_expiration() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            val gateway = AuthenticatedEmbyGateway(FakeVault(stored(valid(server.url("/emby").toString()))))
            var expiredDestination: String? = null
            gateway.onAuthenticationExpired = { expiredDestination = it }

            val status = gateway.verifyAuthentication("search")

            assertEquals(GatewayAuthenticationStatus.Expired, status)
            assertEquals("search", expiredDestination)
            val request = server.takeRequest()
            assertEquals("/emby/Users/user", request.url.encodedPath)
            assertEquals("token-secret", request.headers["X-Emby-Token"])
            assertNull(request.headers["Authorization"])
        }
    }

    @Test
    fun authenticated_request_never_follows_redirect_to_another_authority() = runTest {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.start()
                second.start()
                first.enqueue(
                    MockResponse.Builder().code(307).addHeader("Location", second.url("/capture")).build(),
                )
                val gateway = AuthenticatedEmbyGateway(FakeVault(stored(valid(first.url("/emby").toString()))))

                assertEquals(GatewayAuthenticationStatus.Unavailable, gateway.verifyAuthentication())
                assertEquals(0, second.requestCount)
            }
        }
    }

    private class FakeVault(private var session: StoredSession?) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null }
    }

    private fun stored(address: ServerAddress) = StoredSession(
        address, "server", "user", "Ada", AccessToken.fromRaw("token-secret"), null, "Cinema",
    )

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
}
