package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.SearchMediaType
import dev.chaichai.mobile.core.contracts.SearchState
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SearchGatewayTest {
    @Test
    fun aggregated_search_groups_reduced_results_with_full_scope_and_provenance() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                json(
                    """{"Items":[
                      {"Id":"movie","Name":"Arrival","Type":"Movie","ProductionYear":2016,"ImageTags":{"Primary":"movie-art"}},
                      {"Id":"series","Name":"The Expanse","Type":"Series"},
                      {"Id":"season","Name":"Season 1","Type":"Season","SeriesId":"series","SeriesName":"The Expanse","IndexNumber":1},
                      {"Id":"episode","Type":"Episode","SeriesId":"series","SeriesName":"The Expanse","SeasonId":"season","ParentIndexNumber":1,"IndexNumber":1},
                      {"Id":"orphan-season","Name":"Season without a series","Type":"Season"},
                      {"Id":"person","Name":"Ada","Type":"Person"},
                      {"Name":"Missing identity","Type":"Movie"}
                    ],"TotalRecordCount":6}""",
                ),
            )
            val gateway = gateway(server)

            gateway.search("  ar  ")

            val ready = gateway.searchState.value as SearchState.Results
            assertEquals(HomeScope("server", "user"), ready.scope)
            assertEquals("ar", ready.query)
            assertEquals(SearchMediaType.entries, ready.groups.map { it.mediaType })
            assertEquals(MediaIdentity("server", "movie"), ready.groups[0].items.single().identity)
            assertEquals("movie-art", ready.groups[0].items.single().artwork?.imageTag)
            assertEquals(MediaIdentity("server", "series"), ready.groups[2].items.single().seriesIdentity)
            assertEquals(MediaIdentity("server", "season"), ready.groups[3].items.single().seasonIdentity)
            assertEquals("Untitled episode", ready.groups[3].items.single().title)
            assertNull(ready.groups[1].items.single().year)

            val request = server.takeRequest()
            assertEquals("ar", request.url.queryParameter("SearchTerm"))
            assertEquals("Movie,Series,Season,Episode", request.url.queryParameter("IncludeItemTypes"))
            assertEquals("true", request.url.queryParameter("Recursive"))
            assertEquals("token-secret", request.headers["X-Emby-Token"])
            assertEquals("/emby/Users/user/Items", request.url.encodedPath)
        }
    }

    @Test
    fun superseded_search_response_cannot_replace_the_latest_query() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"Items":[{"Id":"old","Name":"Old","Type":"Movie"}]}"""))
            server.enqueue(json("""{"Items":[{"Id":"new","Name":"New","Type":"Movie"}]}"""))
            val cache = BlockingFirstSaveSearchCache()
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))),
                searchCache = cache,
                deviceId = "test-device",
            )
            val oldSearch = backgroundScope.launch(Dispatchers.Default) { gateway.search("old") }
            cache.firstSaveStarted.await()

            gateway.search("new")
            cache.releaseFirstSave.complete(Unit)
            oldSearch.join()

            val ready = gateway.searchState.value as SearchState.Results
            assertEquals("new", ready.query)
            assertEquals("New", ready.groups.first().items.single().title)
        }
    }

    @Test
    fun starting_a_new_search_cancels_the_in_flight_http_request() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .headersDelay(30, TimeUnit.SECONDS)
                    .body("""{"Items":[{"Id":"old","Name":"Old","Type":"Movie"}]}""")
                    .build(),
            )
            server.enqueue(json("""{"Items":[{"Id":"new","Name":"New","Type":"Movie"}]}"""))
            val gateway = gateway(server)
            val oldSearch = backgroundScope.launch(Dispatchers.Default) { gateway.search("old") }
            server.takeRequest()

            withContext(Dispatchers.Default) {
                withTimeout(2_000) { gateway.search("new") }
            }
            oldSearch.join()

            val ready = gateway.searchState.value as SearchState.Results
            assertEquals("new", ready.query)
            assertEquals("New", ready.groups.first().items.single().title)
        }
    }

    @Test
    fun saved_search_results_restore_for_only_the_matching_server_user_and_failure_stays_recoverable() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"Items":[{"Id":"movie","Name":"Saved","Type":"Movie"}]}"""))
            val cache = InMemorySearchCache()
            val address = valid(server.url("/emby").toString())
            AuthenticatedEmbyGateway(FakeVault(stored(address)), searchCache = cache, deviceId = "test-device")
                .search("saved")
            server.enqueue(MockResponse.Builder().code(503).build())

            val recreated = AuthenticatedEmbyGateway(
                FakeVault(stored(address)), searchCache = cache, deviceId = "test-device",
            )
            recreated.search("saved")

            val failure = recreated.searchState.value as SearchState.Failure
            assertEquals("Saved", failure.restoredGroups.first().items.single().title)
            assertEquals(HomeScope("server", "user"), failure.scope)
            assertNull(cache.load(HomeScope("server", "other-user"), "saved"))
            assertNull(cache.load(HomeScope("other-server", "user"), "saved"))
        }
    }

    @Test
    fun short_queries_do_not_reach_the_server_and_empty_results_are_distinct() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = gateway(server)

            gateway.search(" a ")

            assertTrue(gateway.searchState.value is SearchState.Initial)
            assertEquals(0, server.requestCount)
            server.enqueue(json("""{"Items":[]}"""))
            gateway.search("none")
            assertTrue(gateway.searchState.value is SearchState.Empty)
        }
    }

    private fun gateway(server: MockWebServer) = AuthenticatedEmbyGateway(
        FakeVault(stored(valid(server.url("/emby").toString()))),
        deviceId = "test-device",
    )

    private class FakeVault(private var session: StoredSession?) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null }
    }

    private fun stored(address: ServerAddress) = StoredSession(
        address, "server", "user", "Ada", AccessToken.fromRaw("token-secret"), null, "Cinema",
    )

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address

    private fun json(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private class BlockingFirstSaveSearchCache : SearchCache {
        val firstSaveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        private var saves = 0

        override suspend fun load(scope: HomeScope, query: String): List<dev.chaichai.mobile.core.contracts.SearchResultGroup>? = null

        override suspend fun save(
            scope: HomeScope,
            query: String,
            groups: List<dev.chaichai.mobile.core.contracts.SearchResultGroup>,
        ) {
            if (saves++ == 0) {
                firstSaveStarted.complete(Unit)
                releaseFirstSave.await()
            }
        }
    }
}
