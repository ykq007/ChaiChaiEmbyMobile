package dev.chaichai.mobile.platform.server

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyAuthenticatorTest {
    @Test
    fun interactive_authentication_preserves_prefix_and_returns_server_scoped_identity() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """{"AccessToken":"token-secret","User":{"Id":"user-7","Name":"Ada","Policy":{"IsDisabled":false,"EnableMediaPlayback":true}}}""",
                ).build(),
            )
            val address = valid(server.url("/family/emby/").toString())

            val result = EmbyAuthenticator().authenticate(
                address = address,
                serverId = "server-1",
                username = "Ada",
                password = "password-secret",
                deviceId = "stable-device",
            )

            val session = (result as AuthenticationResult.Success).session
            assertEquals("server-1", session.serverId)
            assertEquals("user-7", session.userId)
            assertEquals("token-secret", session.accessToken)
            val request = server.takeRequest()
            assertEquals("/family/emby/Users/AuthenticateByName", request.url.encodedPath)
            assertTrue(request.headers["X-Emby-Authorization"]!!.contains("DeviceId=\"stable-device\""))
            assertNull(request.headers["Authorization"])
            assertTrue(request.body!!.utf8().contains("password-secret"))
        }
    }

    @Test
    fun rejected_password_and_insufficient_access_are_distinct() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(
                MockResponse.Builder().body(
                    """{"AccessToken":"token","User":{"Id":"user","Name":"Ada","Policy":{"IsDisabled":false,"EnableMediaPlayback":false}}}""",
                ).build(),
            )
            val authenticator = EmbyAuthenticator()
            val address = valid(server.url("/emby").toString())

            assertEquals(
                AuthenticationFailure.InvalidCredentials,
                (authenticator.authenticate(address, "server", "Ada", "wrong", "device") as AuthenticationResult.Failure).reason,
            )
            assertEquals(
                AuthenticationFailure.InsufficientAccess,
                (authenticator.authenticate(address, "server", "Ada", "right", "device") as AuthenticationResult.Failure).reason,
            )
        }
    }

    @Test
    fun authentication_never_follows_a_redirect() = runTest {
        MockWebServer().use { first ->
            MockWebServer().use { redirected ->
                first.start()
                redirected.start()
                first.enqueue(
                    MockResponse.Builder().code(307).addHeader("Location", redirected.url("/capture")).build(),
                )

                val result = EmbyAuthenticator().authenticate(
                    valid(first.url("/emby").toString()), "server", "Ada", "password", "device",
                )

                assertEquals(
                    AuthenticationFailure.TransportPolicy,
                    (result as AuthenticationResult.Failure).reason,
                )
                assertEquals(0, redirected.requestCount)
                assertFalse(first.takeRequest().body!!.utf8().isBlank())
            }
        }
    }

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
}
