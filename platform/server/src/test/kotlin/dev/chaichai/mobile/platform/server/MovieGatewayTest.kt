package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.MovieDetails
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.MovieDetailsState
import dev.chaichai.mobile.core.contracts.MovieLibraryQuery
import dev.chaichai.mobile.core.contracts.MovieLibraryState
import dev.chaichai.mobile.core.contracts.MovieSortField
import dev.chaichai.mobile.core.contracts.SortDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MovieGatewayTest {
    @Test
    fun movie_query_maps_reduced_unknown_tolerant_dtos_and_server_scoped_artwork() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{"Items":[{"Name":"Drama"},{"Name":"Science Fiction"}]}"""))
            server.enqueue(ok("""{
                "Items":[
                  {"Id":"arrival","Name":"Arrival","ProductionYear":2016,"ImageTags":{"Primary":"poster-v2"},"Unknown":"safe"},
                  {"Id":"plugin","Name":"Plugin Film","Type":"FutureMovie"},
                  {"Name":"missing identity"}
                ],
                "TotalRecordCount":2,
                "UnknownRoot":true
            }"""))
            val gateway = gateway(server)

            gateway.refreshMovies(
                MovieLibraryQuery(MovieSortField.ReleaseDate, SortDirection.Descending, "Science Fiction"),
            )

            val state = gateway.movieLibrary.value as MovieLibraryState.Ready
            assertEquals(listOf("Drama", "Science Fiction"), state.availableGenres)
            assertEquals(listOf("Arrival", "Plugin Film"), state.items.map { it.title })
            assertEquals(MediaIdentity("server", "arrival"), state.items.first().identity)
            assertEquals("poster-v2", state.items.first().artwork?.imageTag)
            val request = server.takeRequest()
            assertEquals("/emby/Genres", request.url.encodedPath)
            val itemsRequest = server.takeRequest()
            assertEquals("PremiereDate", itemsRequest.url.queryParameter("SortBy"))
            assertEquals("Descending", itemsRequest.url.queryParameter("SortOrder"))
            assertEquals("Science Fiction", itemsRequest.url.queryParameter("Genres"))
            assertEquals("token-secret", itemsRequest.headers["X-Emby-Token"])
            assertTrue(itemsRequest.headers["X-Emby-Authorization"]!!.contains("DeviceId=\"test-device\""))
            assertTrue(itemsRequest.headers["X-Emby-Authorization"]!!.contains("UserId=\"user\""))
        }
    }

    @Test
    fun movie_work_is_dispatched_off_the_calling_context() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{"Items":[]}"""))
            server.enqueue(ok("""{"Items":[]}"""))
            val cache = ThreadRecordingMovieCache()
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "movie-io") }
            executor.asCoroutineDispatcher().use { dispatcher ->
                val gateway = AuthenticatedEmbyGateway(
                    FakeVault(stored(valid(server.url("/emby").toString()))),
                    movieCache = cache,
                    deviceId = "test-device",
                    ioDispatcher = dispatcher,
                )

                gateway.refreshMovies()

                assertEquals("movie-io", cache.loadThread)
            }
        }
    }

    @Test
    fun revoked_movie_token_invalidates_content_and_enters_reauthentication() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            val gateway = gateway(server)
            var expiredDestination: String? = null
            gateway.onAuthenticationExpired = { expiredDestination = it }
            gateway.setConnected(true)

            gateway.refreshMovies()

            assertEquals(GatewayConnectionState.Disconnected, gateway.connectionState.value)
            assertEquals(MovieLibraryState.Loading, gateway.movieLibrary.value)
            assertEquals("libraries", expiredDestination)
        }
    }

    @Test
    fun stale_revocation_cannot_disconnect_a_newer_same_user_credential() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).headersDelay(1, TimeUnit.SECONDS).build())
            val vault = FakeVault(stored(valid(server.url("/emby").toString())))
            val gateway = AuthenticatedEmbyGateway(vault, movieCache = InMemoryMovieCache(), deviceId = "test-device")
            gateway.setConnected(true)
            var expirations = 0
            gateway.onAuthenticationExpired = { expirations += 1 }
            val refresh = backgroundScope.launch(Dispatchers.Default) { gateway.refreshMovies() }
            server.takeRequest()

            vault.save(stored(valid(server.url("/emby").toString())).copy(accessToken = AccessToken.fromRaw("new-token")))
            gateway.setConnected(true)
            refresh.join()

            assertEquals(GatewayConnectionState.Connected, gateway.connectionState.value)
            assertEquals(0, expirations)
        }
    }

    @Test
    fun scope_change_during_cache_load_cannot_publish_the_previous_users_movies() = runTest {
        MockWebServer().use { server ->
            server.start()
            val vault = FakeVault(stored(valid(server.url("/emby").toString())))
            val cache = BlockingLibraryCache()
            val gateway = AuthenticatedEmbyGateway(vault, movieCache = cache, deviceId = "test-device")
            val refresh = backgroundScope.launch { gateway.refreshMovies() }
            cache.loadStarted.await()

            vault.save(stored(valid(server.url("/emby").toString())).copy(serverId = "server-b", userId = "user-b"))
            cache.releaseLoad.complete(Unit)
            refresh.join()

            assertEquals(MovieLibraryState.Loading, gateway.movieLibrary.value)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun scope_change_during_detail_fallback_cannot_return_the_previous_users_details() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).build())
            val vault = FakeVault(stored(valid(server.url("/emby").toString())))
            val cache = BlockingDetailsCache()
            val gateway = AuthenticatedEmbyGateway(vault, movieCache = cache, deviceId = "test-device")
            val result = backgroundScope.launch {
                cache.result = gateway.loadMovieDetails(MediaIdentity("server", "arrival"))
            }
            cache.loadStarted.await()

            vault.save(stored(valid(server.url("/emby").toString())).copy(serverId = "server-b", userId = "user-b"))
            cache.releaseLoad.complete(Unit)
            result.join()

            assertTrue(cache.result is MovieDetailsState.Failure)
        }
    }

    @Test
    fun failed_additional_page_preserves_loaded_content_for_inline_retry() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{"Items":[]}"""))
            server.enqueue(ok(page("First", 50, 0, 40)))
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(ok(page("Recovered", 50, 40, 10)))
            val gateway = gateway(server)
            gateway.refreshMovies()

            gateway.loadNextMoviePage()

            val failed = gateway.movieLibrary.value as MovieLibraryState.Ready
            assertEquals(40, failed.items.size)
            assertEquals("Couldn't load more movies.", failed.pageFailureMessage)
            assertFalse(failed.isLoadingMore)

            gateway.retryMoviePage()

            val recovered = gateway.movieLibrary.value as MovieLibraryState.Ready
            assertEquals(50, recovered.items.size)
            assertEquals("Recovered 49", recovered.items.last().title)
            assertNull(recovered.pageFailureMessage)
            val requests = List(4) { server.takeRequest() }
            assertEquals("40", requests[2].url.queryParameter("StartIndex"))
            assertEquals("40", requests[3].url.queryParameter("StartIndex"))
        }
    }

    @Test
    fun details_omit_absent_metadata_and_classify_progress_and_tracks() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{
              "Id":"arrival","Name":"Arrival","ProductionYear":2016,"RunTimeTicks":69600000000,
              "CommunityRating":7.9,"Genres":["Drama","Science Fiction"],"Overview":"Language changes everything.",
              "ImageTags":{"Primary":"p"},"BackdropImageTags":["b"],
              "UserData":{"PlaybackPositionTicks":1200000000,"Played":false},
              "MediaSources":[{"MediaStreams":[{"Type":"Audio"},{"Type":"Audio"},{"Type":"Subtitle"},{"Type":"Data"}]}],
              "UnknownField":{"future":true}
            }"""))
            val gateway = gateway(server)

            val ready = gateway.loadMovieDetails(MediaIdentity("server", "arrival")) as MovieDetailsState.Ready

            assertEquals("Arrival", ready.details.title)
            assertEquals(2, ready.details.tracks.audioTracks)
            assertEquals(1, ready.details.tracks.subtitleTracks)
            assertTrue(ready.details.hasMeaningfulResume)
            assertNull(ready.details.criticRating)
            assertEquals("Backdrop", ready.details.backdrop?.kind?.routeName)

            server.enqueue(ok("""{"Id":"arrival","Name":"Arrival","RunTimeTicks":69600000000,"UserData":{"PlaybackPositionTicks":90000000}}"""))
            val negligible = gateway.loadMovieDetails(MediaIdentity("server", "arrival")) as MovieDetailsState.Ready
            assertFalse(negligible.details.hasMeaningfulResume)
            server.enqueue(ok("""{"Id":"arrival","Name":"Arrival","RunTimeTicks":69600000000,"UserData":{"PlaybackPositionTicks":69050000000,"Played":true}}"""))
            val completed = gateway.loadMovieDetails(MediaIdentity("server", "arrival")) as MovieDetailsState.Ready
            assertFalse(completed.details.hasMeaningfulResume)
        }
    }

    @Test
    fun saved_movie_content_and_details_restore_only_for_the_matching_scope() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{"Items":[{"Name":"Drama"}]}"""))
            server.enqueue(ok("""{"Items":[{"Id":"arrival","Name":"Arrival"}],"TotalRecordCount":1}"""))
            server.enqueue(ok("""{"Id":"arrival","Name":"Arrival","Overview":"Saved details"}"""))
            val cache = InMemoryMovieCache()
            val vault = FakeVault(stored(valid(server.url("/emby").toString())))
            AuthenticatedEmbyGateway(vault, movieCache = cache, deviceId = "test-device").apply {
                refreshMovies()
                loadMovieDetails(MediaIdentity("server", "arrival"))
            }
            server.enqueue(MockResponse.Builder().code(503).build())

            val recreated = AuthenticatedEmbyGateway(vault, movieCache = cache, deviceId = "test-device")
            recreated.refreshMovies()

            assertEquals("Arrival", (recreated.movieLibrary.value as MovieLibraryState.Ready).items.single().title)
            server.enqueue(MockResponse.Builder().code(503).build())
            val restored = recreated.loadMovieDetails(MediaIdentity("server", "arrival")) as MovieDetailsState.Ready
            assertEquals("Saved details", restored.details.overview)
        }
    }

    @Test
    fun successful_first_page_refresh_preserves_the_restored_pagination_journey() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{"Items":[]}"""))
            server.enqueue(ok(page("Fresh", 80, 0, 40)))
            server.enqueue(ok(page("Fresh", 80, 40, 40)))
            val scope = HomeScope("server", "user")
            val query = MovieLibraryQuery(MovieSortField.DateAdded, SortDirection.Descending)
            val cachedItems = (0 until 80).map {
                MoviePoster(MediaIdentity("server", "movie-$it"), "Cached $it")
            }
            val cache = InMemoryMovieCache().apply {
                saveLibrary(scope, query, cachedItems, 80, emptyList())
            }
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))),
                movieCache = cache,
                deviceId = "test-device",
            )

            gateway.refreshMovies(query)

            val ready = gateway.movieLibrary.value as MovieLibraryState.Ready
            assertEquals(80, ready.items.size)
            assertEquals("Fresh 0", ready.items.first().title)
            assertEquals("Fresh 79", ready.items.last().title)
            assertEquals(80, cache.loadLibrary(scope, query)!!.items.size)
        }
    }

    @Test
    fun unchanged_first_page_revalidates_tail_deletion_reorder_and_metadata() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{"Items":[]}"""))
            server.enqueue(ok(page("Fresh", 79, 0, 40)))
            val tailIds = (40 until 80).filterNot { it == 60 }.toMutableList().apply {
                val index = indexOf(50)
                this[index] = 51
                this[index + 1] = 50
            }
            server.enqueue(ok(tailIds.joinToString(",", "{\"Items\":[", "],\"TotalRecordCount\":79}") {
                "{\"Id\":\"movie-$it\",\"Name\":\"Updated $it\"}"
            }))
            val scope = HomeScope("server", "user")
            val query = MovieLibraryQuery(MovieSortField.DateAdded, SortDirection.Descending)
            val cache = InMemoryMovieCache().apply {
                saveLibrary(
                    scope,
                    query,
                    (0 until 80).map { MoviePoster(MediaIdentity("server", "movie-$it"), "Cached $it") },
                    80,
                    emptyList(),
                )
            }
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))),
                movieCache = cache,
                deviceId = "test-device",
            )

            gateway.refreshMovies(query)

            val ready = gateway.movieLibrary.value as MovieLibraryState.Ready
            assertEquals(79, ready.items.size)
            assertFalse(ready.items.any { it.identity.itemId == "movie-60" })
            assertEquals(listOf("movie-51", "movie-50"), ready.items.slice(50..51).map { it.identity.itemId })
            assertEquals("Updated 79", ready.items.last().title)
            val requests = List(3) { server.takeRequest() }
            assertEquals("40", requests[2].url.queryParameter("StartIndex"))
        }
    }

    @Test
    fun restored_page_revalidation_advances_by_a_short_successful_page_count() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{"Items":[]}"""))
            server.enqueue(ok(page("Fresh", 80, 0, 40)))
            server.enqueue(ok(page("Fresh", 80, 40, 20)))
            server.enqueue(ok(page("Fresh", 80, 60, 20)))
            val scope = HomeScope("server", "user")
            val query = MovieLibraryQuery(MovieSortField.DateAdded, SortDirection.Descending)
            val cache = InMemoryMovieCache().apply {
                saveLibrary(
                    scope, query,
                    (0 until 80).map { MoviePoster(MediaIdentity("server", "movie-$it"), "Cached $it") },
                    80, emptyList(),
                )
            }
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))), movieCache = cache,
                deviceId = "test-device",
            )

            gateway.refreshMovies(query)

            assertEquals(80, (gateway.movieLibrary.value as MovieLibraryState.Ready).items.size)
            val requests = List(4) { server.takeRequest() }
            assertEquals("40", requests[2].url.queryParameter("StartIndex"))
            assertEquals("60", requests[3].url.queryParameter("StartIndex"))
        }
    }

    @Test
    fun pagination_queued_while_cached_refresh_is_in_flight_uses_the_refreshed_prefix() = runTest {
        MockWebServer().use { server ->
            server.start()
            val scope = HomeScope("server", "user")
            val query = MovieLibraryQuery(MovieSortField.DateAdded, SortDirection.Descending)
            val cache = InMemoryMovieCache().apply {
                saveLibrary(
                    scope, query,
                    (0 until 80).map { MoviePoster(MediaIdentity("server", "movie-$it"), "Cached $it") },
                    100, emptyList(),
                )
            }
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))), movieCache = cache,
                deviceId = "test-device",
            )
            val refresh = launch { gateway.refreshMovies(query) }
            withTimeout(5_000) {
                while ((gateway.movieLibrary.value as? MovieLibraryState.Ready)?.isRefreshing != true) yield()
            }

            val pagination = async { gateway.loadNextMoviePage() }
            server.enqueue(ok("""{"Items":[]}"""))
            server.enqueue(ok(page("Fresh", 100, 1, 40)))
            server.enqueue(ok(page("Fresh", 100, 41, 40)))
            refresh.join()
            pagination.await()

            val ready = gateway.movieLibrary.value as MovieLibraryState.Ready
            assertFalse(ready.isRefreshing)
            assertEquals(80, ready.items.size)
            assertEquals("movie-1", ready.items.first().identity.itemId)
            assertEquals("movie-80", ready.items.last().identity.itemId)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun refresh_mutex_excludes_pagination_before_refresh_state_is_published() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(2) {
                server.enqueue(ok("""{"Items":[]}"""))
                server.enqueue(ok(page("Fresh", 40, 0, 40)))
            }
            val scope = HomeScope("server", "user")
            val query = MovieLibraryQuery(MovieSortField.DateAdded, SortDirection.Descending)
            val cache = SwitchableBlockingLibraryCache(
                MovieLibrarySnapshot(
                    (0 until 40).map { MoviePoster(MediaIdentity("server", "movie-$it"), "Cached $it") },
                    40,
                    emptyList(),
                ),
            )
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))), movieCache = cache,
                deviceId = "test-device",
            )
            gateway.refreshMovies(query)
            cache.blockLoads = true
            val refresh = launch { gateway.refreshMovies(query) }
            cache.loadStarted.await()

            val pagination = async { gateway.loadNextMoviePage() }
            yield()
            assertFalse(pagination.isCompleted)
            cache.releaseLoad.complete(Unit)
            refresh.join()
            pagination.await()

            assertEquals(40, (gateway.movieLibrary.value as MovieLibraryState.Ready).items.size)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun changed_first_page_invalidates_cached_tail_and_advances_with_authoritative_offsets() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(ok("""{"Items":[]}"""))
            val insertedFirstPage = buildList {
                add("{\"Id\":\"inserted\",\"Name\":\"Inserted\"}")
                addAll((0 until 39).map { "{\"Id\":\"movie-$it\",\"Name\":\"Fresh $it\"}" })
            }.joinToString(",", "{\"Items\":[", "],\"TotalRecordCount\":81}")
            server.enqueue(ok(insertedFirstPage))
            server.enqueue(ok(page("Fresh", 81, 39, 40)))
            server.enqueue(ok(page("Fresh", 81, 79, 1)))
            val scope = HomeScope("server", "user")
            val query = MovieLibraryQuery(MovieSortField.DateAdded, SortDirection.Descending)
            val cache = InMemoryMovieCache().apply {
                saveLibrary(
                    scope,
                    query,
                    (0 until 80).map { MoviePoster(MediaIdentity("server", "movie-$it"), "Cached $it") },
                    80,
                    emptyList(),
                )
            }
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))),
                movieCache = cache,
                deviceId = "test-device",
            )

            gateway.refreshMovies(query)
            assertEquals(40, (gateway.movieLibrary.value as MovieLibraryState.Ready).items.size)
            gateway.loadNextMoviePage()
            gateway.loadNextMoviePage()

            val ready = gateway.movieLibrary.value as MovieLibraryState.Ready
            assertEquals(81, ready.items.size)
            assertEquals(81, ready.items.distinctBy { it.identity }.size)
            val requests = List(4) { server.takeRequest() }
            assertEquals("40", requests[2].url.queryParameter("StartIndex"))
            assertEquals("80", requests[3].url.queryParameter("StartIndex"))
        }
    }

    @Test
    fun insert_delete_and_reorder_each_invalidate_the_cached_page_tail() = runTest {
        val firstPageIdentities = listOf(
            listOf("inserted") + (0 until 39).map { "movie-$it" },
            (1..40).map { "movie-$it" },
            listOf("movie-1", "movie-0") + (2 until 40).map { "movie-$it" },
        )
        firstPageIdentities.forEach { identities ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(ok("""{"Items":[]}"""))
                server.enqueue(ok(identities.joinToString(",", "{\"Items\":[", "],\"TotalRecordCount\":80}") {
                    "{\"Id\":\"$it\",\"Name\":\"Fresh $it\"}"
                }))
                val scope = HomeScope("server", "user")
                val query = MovieLibraryQuery(MovieSortField.DateAdded, SortDirection.Descending)
                val cache = InMemoryMovieCache().apply {
                    saveLibrary(
                        scope,
                        query,
                        (0 until 80).map { MoviePoster(MediaIdentity("server", "movie-$it"), "Cached $it") },
                        80,
                        emptyList(),
                    )
                }
                val gateway = AuthenticatedEmbyGateway(
                    FakeVault(stored(valid(server.url("/emby").toString()))),
                    movieCache = cache,
                    deviceId = "test-device",
                )

                gateway.refreshMovies(query)

                assertEquals(40, (gateway.movieLibrary.value as MovieLibraryState.Ready).items.size)
            }
        }
    }

    @Test
    fun detail_revocation_preserves_the_encoded_return_destination() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            val gateway = gateway(server)
            gateway.setConnected(true)
            var destination: String? = null
            gateway.onAuthenticationExpired = { destination = it }

            gateway.loadMovieDetails(
                MediaIdentity("server", "arrival"),
                "movies/server/arrival",
            )

            assertEquals("movies/server/arrival", destination)
            assertEquals(GatewayConnectionState.Disconnected, gateway.connectionState.value)
        }
    }

    @Test
    fun library_distinguishes_empty_unfiltered_filtered_and_initial_failure() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(ok("""{"Items":[]}""")) }
            server.enqueue(ok("""{"Items":[]}"""))
            server.enqueue(MockResponse.Builder().code(503).build())
            val gateway = gateway(server)
            gateway.refreshMovies()
            assertTrue(gateway.movieLibrary.value is MovieLibraryState.EmptyLibrary)
            gateway.refreshMovies(MovieLibraryQuery(genre = "Drama"))
            assertTrue(gateway.movieLibrary.value is MovieLibraryState.EmptyFiltered)
            gateway.refreshMovies()
            assertTrue(gateway.movieLibrary.value is MovieLibraryState.Failure)
        }
    }

    private fun gateway(server: MockWebServer) = AuthenticatedEmbyGateway(
        FakeVault(stored(valid(server.url("/emby").toString()))),
        deviceId = "test-device",
    )

    private fun page(prefix: String, total: Int, start: Int, count: Int): String =
        (start until start + count).joinToString(",", "{\"Items\":[", "],\"TotalRecordCount\":$total}") {
            "{\"Id\":\"movie-$it\",\"Name\":\"$prefix $it\"}"
        }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private class FakeVault(private var session: StoredSession?) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null }
    }

    private class ThreadRecordingMovieCache : MovieCache {
        var loadThread: String? = null
        override suspend fun loadLibrary(scope: HomeScope, query: MovieLibraryQuery): MovieLibrarySnapshot? {
            loadThread = Thread.currentThread().name
            return null
        }
        override suspend fun saveLibrary(
            scope: HomeScope,
            query: MovieLibraryQuery,
            items: List<MoviePoster>,
            totalCount: Int,
            availableGenres: List<String>,
        ) = Unit
        override suspend fun loadDetails(scope: HomeScope, identity: MediaIdentity): MovieDetails? = null
        override suspend fun saveDetails(scope: HomeScope, details: MovieDetails) = Unit
    }

    private class BlockingLibraryCache : MovieCache {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        override suspend fun loadLibrary(scope: HomeScope, query: MovieLibraryQuery): MovieLibrarySnapshot? {
            loadStarted.complete(Unit)
            releaseLoad.await()
            return MovieLibrarySnapshot(
                listOf(MoviePoster(MediaIdentity(scope.serverId, "old"), "Old private movie")),
                1,
                emptyList(),
            )
        }
        override suspend fun saveLibrary(
            scope: HomeScope,
            query: MovieLibraryQuery,
            items: List<MoviePoster>,
            totalCount: Int,
            availableGenres: List<String>,
        ) = Unit
        override suspend fun loadDetails(scope: HomeScope, identity: MediaIdentity): MovieDetails? = null
        override suspend fun saveDetails(scope: HomeScope, details: MovieDetails) = Unit
    }

    private class BlockingDetailsCache : MovieCache {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        var result: MovieDetailsState? = null
        override suspend fun loadLibrary(scope: HomeScope, query: MovieLibraryQuery): MovieLibrarySnapshot? = null
        override suspend fun saveLibrary(
            scope: HomeScope,
            query: MovieLibraryQuery,
            items: List<MoviePoster>,
            totalCount: Int,
            availableGenres: List<String>,
        ) = Unit
        override suspend fun loadDetails(scope: HomeScope, identity: MediaIdentity): MovieDetails {
            loadStarted.complete(Unit)
            releaseLoad.await()
            return MovieDetails(identity, "Old private details")
        }
        override suspend fun saveDetails(scope: HomeScope, details: MovieDetails) = Unit
    }

    private class SwitchableBlockingLibraryCache(
        private var snapshot: MovieLibrarySnapshot,
    ) : MovieCache {
        var blockLoads = false
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        override suspend fun loadLibrary(scope: HomeScope, query: MovieLibraryQuery): MovieLibrarySnapshot {
            if (blockLoads) {
                loadStarted.complete(Unit)
                releaseLoad.await()
            }
            return snapshot
        }
        override suspend fun saveLibrary(
            scope: HomeScope,
            query: MovieLibraryQuery,
            items: List<MoviePoster>,
            totalCount: Int,
            availableGenres: List<String>,
        ) {
            snapshot = MovieLibrarySnapshot(items, totalCount, availableGenres)
        }
        override suspend fun loadDetails(scope: HomeScope, identity: MediaIdentity): MovieDetails? = null
        override suspend fun saveDetails(scope: HomeScope, details: MovieDetails) = Unit
    }

    private fun stored(address: ServerAddress) = StoredSession(
        address, "server", "user", "Ada", AccessToken.fromRaw("token-secret"), null, "Cinema",
    )

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
}
