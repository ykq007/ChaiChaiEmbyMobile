package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.HomeScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyProgressRemoteTest {
    @Test
    fun `untouched item with zero position and no timestamp has no authoritative progress`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(
                """{"UserData":{"PlaybackPositionTicks":0}}""",
            ).build())
            val remote = EmbyProgressRemote(FixedVault(session(server)), "device")

            assertEquals(ServerProgressPosition.NoPosition, remote.serverPosition(progress()))
        }
    }

    @Test
    fun `reconciliation reads authoritative user position before idempotent progress post`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(
                """{"UserData":{"PlaybackPositionTicks":900,"LastPlayedDate":"2026-07-16T00:00:00Z"}}""",
            ).build())
            server.enqueue(MockResponse.Builder().code(204).build())
            val remote = EmbyProgressRemote(FixedVault(session(server)), "device")
            val progress = progress()

            assertEquals(ServerProgressPosition.Known(900, 1_784_160_000_000), remote.serverPosition(progress))
            assertEquals(ProgressSendResult.Confirmed, remote.send(progress))

            assertEquals("/emby/Users/user-a/Items/movie", server.takeRequest().url.encodedPath)
            val post = server.takeRequest()
            assertEquals("/emby/Sessions/Playing/Progress", post.url.encodedPath)
            assertTrue(post.body?.utf8().orEmpty().contains("\"PositionTicks\":500"))
            assertEquals("token", post.headers["X-Emby-Token"])
        }
    }

    @Test
    fun `credentials cannot cross a server user scope`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val remote = EmbyProgressRemote(FixedVault(session(server)), "device")

            val result = remote.serverPosition(progress(scope = HomeScope("server-b", "user-b")))

            assertEquals(ServerProgressPosition.Unavailable, result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `permanent client error is rejected instead of retried forever`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(400).build())
            val remote = EmbyProgressRemote(FixedVault(session(server)), "device")

            assertEquals(ProgressSendResult.Rejected, remote.send(progress()))
        }
    }

    private fun progress(scope: HomeScope = HomeScope("server-a", "user-a")) = PendingProgress(
        scope, "movie", "source", "session", PlaybackMethod.DirectPlay, 1_000, 500,
        false, PlaybackProgressEvent.Seek, 1,
    )

    private fun session(server: MockWebServer): StoredSession = StoredSession(
        (ServerAddress.parse(server.url("/emby").toString()) as AddressValidation.Valid).address,
        "server-a", "user-a", "User", AccessToken.fromRaw("token"), null, "Server",
    )

    private class FixedVault(private val session: StoredSession) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) = Unit
        override fun clear() = Unit
    }
}
