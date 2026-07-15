package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MovieDetailsState
import dev.chaichai.mobile.core.contracts.MovieLibraryQuery
import dev.chaichai.mobile.core.contracts.MovieLibraryState
import dev.chaichai.mobile.core.contracts.MovieSortField
import dev.chaichai.mobile.core.contracts.SortDirection
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            AuthenticatedEmbyGateway(vault, movieCache = cache).apply {
                refreshMovies()
                loadMovieDetails(MediaIdentity("server", "arrival"))
            }
            server.enqueue(MockResponse.Builder().code(503).build())

            val recreated = AuthenticatedEmbyGateway(vault, movieCache = cache)
            recreated.refreshMovies()

            assertEquals("Arrival", (recreated.movieLibrary.value as MovieLibraryState.Ready).items.single().title)
            server.enqueue(MockResponse.Builder().code(503).build())
            val restored = recreated.loadMovieDetails(MediaIdentity("server", "arrival")) as MovieDetailsState.Ready
            assertEquals("Saved details", restored.details.overview)
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

    private fun stored(address: ServerAddress) = StoredSession(
        address, "server", "user", "Ada", AccessToken.fromRaw("token-secret"), null, "Cinema",
    )

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
}
