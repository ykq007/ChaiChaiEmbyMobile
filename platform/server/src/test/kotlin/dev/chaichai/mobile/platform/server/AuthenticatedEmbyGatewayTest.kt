package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.GatewayAuthenticationStatus
import dev.chaichai.mobile.core.contracts.ArtworkReference
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.HomeSectionContent
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.HomeSection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthenticatedEmbyGatewayTest {
    @Test
    fun home_refresh_loads_every_server_scoped_section_and_authenticated_artwork() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(5) {
                server.enqueue(
                    MockResponse.Builder().code(200).body(
                        """{"Items":[{"Id":"item-$it","Name":"Title $it","Type":"Movie","ImageTags":{"Primary":"tag-$it"},"UserData":{"PlaybackPositionTicks":120000000}}]}""",
                    ).build(),
                )
            }
            server.enqueue(MockResponse.Builder().code(200).body("artwork").build())
            val gateway = gateway(FakeVault(stored(valid(server.url("/emby").toString()))))

            gateway.refreshHome()

            val ready = gateway.homeFeed.value as HomeFeedState.Ready
            assertEquals(HomeSection.entries.toSet(), ready.sections.keys)
            assertEquals("Title 0", ready.sections.getValue(HomeSection.ContinueWatching).items.single().title)
            val artwork = gateway.loadArtwork(ready.sections.getValue(HomeSection.ContinueWatching).items.single().artwork!!)
            assertEquals("artwork", artwork!!.decodeToString())
            val requests = List(6) { server.takeRequest() }
            assert(requests.take(5).all { it.headers["X-Emby-Token"] == "token-secret" })
            assertEquals("token-secret", requests.last().headers["X-Emby-Token"])
            assert(requests.all { it.url.encodedPath.startsWith("/emby/") })
        }
    }

    @Test
    fun stale_refresh_preserves_successful_content_and_marks_only_the_failed_section_recoverable() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(5) { server.enqueue(items("Before $it")) }
            val gateway = gateway(FakeVault(stored(valid(server.url("/emby").toString()))))
            gateway.refreshHome()
            server.enqueue(items("After continue"))
            server.enqueue(MockResponse.Builder().code(503).build())
            repeat(3) { server.enqueue(items("After ${it + 2}")) }

            gateway.refreshHome()

            val ready = gateway.homeFeed.value as HomeFeedState.Ready
            assertEquals("After continue", ready.sections.getValue(HomeSection.ContinueWatching).items.single().title)
            val stale = ready.sections.getValue(HomeSection.NextUp)
            assertEquals("Before 1", stale.items.single().title)
            assertEquals(true, stale.isStale)
            assertEquals("Couldn't refresh Next Up.", stale.failureMessage)
        }
    }

    @Test
    fun home_distinguishes_total_failure_from_successful_empty_data() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(5) { server.enqueue(MockResponse.Builder().code(503).build()) }
            val gateway = gateway(FakeVault(stored(valid(server.url("/emby").toString()))))
            gateway.refreshHome()
            assertEquals(HomeScope("server", "user"), (gateway.homeFeed.value as HomeFeedState.Failure).scope)
            repeat(5) { server.enqueue(MockResponse.Builder().code(200).body("{\"Items\":[]}").build()) }
            gateway.refreshHome()
            assertEquals(HomeScope("server", "user"), (gateway.homeFeed.value as HomeFeedState.Empty).scope)
        }
    }

    @Test
    fun process_recreation_restores_only_matching_server_user_content_and_artwork() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(5) { server.enqueue(items("Durable $it")) }
            server.enqueue(MockResponse.Builder().code(200).body("durable-art").build())
            val cache = InMemoryHomeCache()
            val vault = FakeVault(stored(valid(server.url("/emby").toString())))
            val first = gateway(vault, homeCache = cache)
            first.refreshHome()
            // The fixture without a tag has no artwork; use a server-scoped reference to exercise the cache.
            val reference = dev.chaichai.mobile.core.contracts.ArtworkReference(
                dev.chaichai.mobile.core.contracts.MediaIdentity("server", "item"), "tag",
            )
            assertEquals("durable-art", first.loadArtwork(reference)!!.decodeToString())
            repeat(5) { server.enqueue(MockResponse.Builder().code(503).build()) }

            val recreated = gateway(vault, homeCache = cache)
            recreated.refreshHome()

            val restored = recreated.homeFeed.value as HomeFeedState.Ready
            assertEquals("Durable 0", restored.sections.getValue(HomeSection.ContinueWatching).items.single().title)
            assertEquals(true, restored.sections.getValue(HomeSection.ContinueWatching).isStale)
            assertEquals("durable-art", recreated.loadArtwork(reference)!!.decodeToString())
            assertEquals(11, server.requestCount)

            vault.save(stored(valid(server.url("/emby").toString())).copy(serverId = "other-server"))
            repeat(5) { server.enqueue(MockResponse.Builder().code(503).build()) }
            recreated.refreshHome()
            assertEquals(HomeScope("other-server", "user"), (recreated.homeFeed.value as HomeFeedState.Failure).scope)
        }
    }

    @Test
    fun racing_old_and_new_sessions_only_publish_the_new_server_scope() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(10) { server.enqueue(items("Response $it")) }
            val vault = FakeVault(stored(valid(server.url("/emby").toString())))
            val cache = FirstLoadBlockingCache()
            val gateway = gateway(vault, homeCache = cache)
            val oldRefresh = backgroundScope.launch { gateway.refreshHome() }
            cache.firstLoadStarted.await()
            vault.save(stored(valid(server.url("/emby").toString())).copy(serverId = "server-b", userId = "user-b"))
            val newRefresh = backgroundScope.launch { gateway.refreshHome() }
            testScheduler.runCurrent()
            assertEquals(HomeScope("server-b", "user-b"), (gateway.homeFeed.value as HomeFeedState.Ready).scope)
            cache.releaseFirstLoad.complete(Unit)

            oldRefresh.join()
            newRefresh.join()

            assertEquals(HomeScope("server-b", "user-b"), (gateway.homeFeed.value as HomeFeedState.Ready).scope)
        }
    }

    @Test
    fun disconnect_invalidates_ready_content_and_rejects_in_flight_completion() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(5) { server.enqueue(items("Old scope $it")) }
            val cache = FirstLoadBlockingCache()
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))),
                homeCache = cache,
                deviceId = "test-device",
            )
            val refresh = backgroundScope.launch { gateway.refreshHome() }
            cache.firstLoadStarted.await()

            gateway.setConnected(false)
            cache.releaseFirstLoad.complete(Unit)
            refresh.join()

            assertEquals(HomeFeedState.Loading, gateway.homeFeed.value)
        }
    }

    @Test
    fun disconnect_during_cache_save_cannot_publish_old_scope_content() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(5) { server.enqueue(items("Old scope $it")) }
            val cache = SaveBlockingCache()
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))),
                homeCache = cache,
                deviceId = "test-device",
            )
            val refresh = backgroundScope.launch { gateway.refreshHome() }
            cache.saveStarted.await()

            gateway.setConnected(false)
            cache.releaseSave.complete(Unit)
            refresh.join()

            assertEquals(HomeFeedState.Loading, gateway.homeFeed.value)
        }
    }

    @Test
    fun authenticated_request_uses_scoped_token_and_reports_expiration() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            val gateway = gateway(FakeVault(stored(valid(server.url("/emby").toString()))))
            var expiredDestination: String? = null
            gateway.onAuthenticationExpired = { expiredDestination = it }

            val status = gateway.verifyAuthentication("search")

            assertEquals(GatewayAuthenticationStatus.Expired, status)
            assertEquals("search", expiredDestination)
            val request = server.takeRequest()
            assertEquals("/emby/Users/user", request.url.encodedPath)
            assertEquals("token-secret", request.headers["X-Emby-Token"])
            assertEquals(
                "MediaBrowser Client=\"ChaiChai Mobile\", Device=\"Android Mobile\", " +
                    "DeviceId=\"test-device\", Version=\"0.1.0\", UserId=\"user\"",
                request.headers["X-Emby-Authorization"],
            )
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
                val gateway = gateway(FakeVault(stored(valid(first.url("/emby").toString()))))

                assertEquals(GatewayAuthenticationStatus.Unavailable, gateway.verifyAuthentication())
                assertEquals(0, second.requestCount)
            }
        }
    }

    @Test
    fun artwork_cache_load_cannot_return_bytes_after_user_scope_changes() = runTest {
        val address = valid("http://127.0.0.1:8096/emby")
        val vault = FakeVault(stored(address))
        val cache = BlockingArtworkCache(blockLoad = true)
        val gateway = gateway(vault, homeCache = cache)
        val reference = ArtworkReference(MediaIdentity("server", "private"), "tag")
        val result = backgroundScope.async { gateway.loadArtwork(reference) }
        cache.loadStarted.await()

        vault.save(stored(address).copy(userId = "user-b"))
        cache.releaseLoad.complete(Unit)

        assertNull(result.await())
    }

    @Test
    fun artwork_network_bytes_are_not_saved_or_returned_after_user_scope_changes() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("private-art").build())
            val vault = FakeVault(stored(valid(server.url("/emby").toString())))
            val cache = BlockingArtworkCache(blockSave = true)
            val gateway = gateway(vault, homeCache = cache)
            val reference = ArtworkReference(MediaIdentity("server", "private"), "tag")
            val result = backgroundScope.async { gateway.loadArtwork(reference) }
            cache.saveStarted.await()

            vault.save(stored(valid(server.url("/emby").toString())).copy(userId = "user-b"))
            cache.releaseSave.complete(Unit)

            assertNull(result.await())
        }
    }

    private fun gateway(
        vault: SessionVault,
        homeCache: HomeCache = InMemoryHomeCache(),
    ) = AuthenticatedEmbyGateway(vault, homeCache = homeCache, deviceId = "test-device")

    private class FakeVault(private var session: StoredSession?) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null }
    }

    private class BlockingArtworkCache(
        private val blockLoad: Boolean = false,
        private val blockSave: Boolean = false,
    ) : HomeCache {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        override suspend fun loadFeed(scope: HomeScope): Map<HomeSection, HomeSectionContent>? = null
        override suspend fun saveFeed(scope: HomeScope, sections: Map<HomeSection, HomeSectionContent>) = Unit
        override suspend fun loadArtwork(scope: HomeScope, reference: ArtworkReference): ByteArray? {
            if (blockLoad) {
                loadStarted.complete(Unit)
                releaseLoad.await()
                return "old-private-art".encodeToByteArray()
            }
            return null
        }
        override suspend fun saveArtwork(scope: HomeScope, reference: ArtworkReference, bytes: ByteArray) {
            if (blockSave) {
                saveStarted.complete(Unit)
                releaseSave.await()
            }
        }
    }

    private class FirstLoadBlockingCache : HomeCache {
        val firstLoadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        private val isFirst = AtomicBoolean(true)
        override suspend fun loadFeed(scope: HomeScope): Map<HomeSection, HomeSectionContent>? {
            if (isFirst.compareAndSet(true, false)) {
                firstLoadStarted.complete(Unit)
                releaseFirstLoad.await()
            }
            val identity = MediaIdentity(scope.serverId, "cached-item")
            return mapOf(
                HomeSection.ContinueWatching to HomeSectionContent(
                    listOf(HomeMediaItem(identity, "Cached ${scope.serverId}", "Movie")),
                ),
            )
        }
        override suspend fun saveFeed(scope: HomeScope, sections: Map<HomeSection, HomeSectionContent>) = Unit
        override suspend fun loadArtwork(scope: HomeScope, reference: ArtworkReference): ByteArray? = null
        override suspend fun saveArtwork(scope: HomeScope, reference: ArtworkReference, bytes: ByteArray) = Unit
    }

    private class SaveBlockingCache : HomeCache {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        override suspend fun loadFeed(scope: HomeScope): Map<HomeSection, HomeSectionContent>? = null
        override suspend fun saveFeed(scope: HomeScope, sections: Map<HomeSection, HomeSectionContent>) {
            saveStarted.complete(Unit)
            releaseSave.await()
        }
        override suspend fun loadArtwork(scope: HomeScope, reference: ArtworkReference): ByteArray? = null
        override suspend fun saveArtwork(scope: HomeScope, reference: ArtworkReference, bytes: ByteArray) = Unit
    }

    private fun items(title: String) = MockResponse.Builder().code(200).body(
        """{"Items":[{"Id":"item","Name":"$title","Type":"Movie"}]}""",
    ).build()

    private fun stored(address: ServerAddress) = StoredSession(
        address, "server", "user", "Ada", AccessToken.fromRaw("token-secret"), null, "Cinema",
    )

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
}
