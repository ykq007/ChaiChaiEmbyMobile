package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.EpisodeDetailsState
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.LibraryQuery
import dev.chaichai.mobile.core.contracts.LibrarySortField
import dev.chaichai.mobile.core.contracts.SeasonEpisodesState
import dev.chaichai.mobile.core.contracts.SeriesDetailsState
import dev.chaichai.mobile.core.contracts.SeriesLibraryState
import dev.chaichai.mobile.core.contracts.SortDirection
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MoviePoster
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesGatewayTest {
    @Test
    fun series_cache_is_scoped_by_full_server_and_user_identity() = runTest {
        val cache = InMemorySeriesCache()
        val query = LibraryQuery()
        val alice = HomeScope("server", "alice")
        cache.saveLibrary(
            alice, query,
            SeriesLibrarySnapshot(listOf(MoviePoster(MediaIdentity("server", "private"), "Alice's show")), 1, emptyList()),
        )

        assertEquals("Alice's show", cache.loadLibrary(alice, query)?.items?.single()?.title)
        assertNull(cache.loadLibrary(HomeScope("server", "bob"), query))
        assertNull(cache.loadLibrary(HomeScope("other-server", "alice"), query))
    }

    @Test
    fun restored_show_pages_are_revalidated_without_losing_the_browse_journey() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"Items":[]}"""))
            server.enqueue(json(seriesPage("Fresh", 80, 0, 40)))
            server.enqueue(json(seriesPage("Fresh", 80, 40, 40)))
            val scope = HomeScope("server", "user")
            val query = LibraryQuery(LibrarySortField.DateAdded, SortDirection.Descending)
            val cache = InMemorySeriesCache().apply {
                saveLibrary(
                    scope, query,
                    SeriesLibrarySnapshot(
                        (0 until 80).map { MoviePoster(MediaIdentity("server", "series-$it"), "Cached $it") },
                        80, emptyList(),
                    ),
                )
            }
            val gateway = AuthenticatedEmbyGateway(
                FakeVault(stored(valid(server.url("/emby").toString()))),
                seriesCache = cache,
                deviceId = "test-device",
            )

            gateway.refreshSeries(query)

            val ready = gateway.seriesLibrary.value as SeriesLibraryState.Ready
            assertEquals(80, ready.items.size)
            assertEquals("Fresh 79", ready.items.last().title)
            val requests = List(3) { server.takeRequest() }
            assertEquals("40", requests.last().url.queryParameter("StartIndex"))
        }
    }

    @Test
    fun shows_use_the_authenticated_paginated_query_and_server_scoped_artwork() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"Items":[{"Name":"Drama"}]}"""))
            server.enqueue(json("""{"Items":[{"Id":"expanse","Name":"The Expanse","ProductionYear":2015,"ImageTags":{"Primary":"poster"}}],"TotalRecordCount":41}"""))
            val gateway = seriesGateway(server)

            gateway.refreshSeries(LibraryQuery(LibrarySortField.ReleaseDate, SortDirection.Descending, "Drama"))

            val ready = gateway.seriesLibrary.value as SeriesLibraryState.Ready
            assertEquals("The Expanse", ready.items.single().title)
            assertEquals(MediaIdentity("server", "expanse"), ready.items.single().identity)
            assertEquals("poster", ready.items.single().artwork?.imageTag)
            assertEquals(41, ready.totalCount)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("Series", request.url.queryParameter("IncludeItemTypes"))
            assertEquals("PremiereDate", request.url.queryParameter("SortBy"))
            assertEquals("token-secret", request.headers["X-Emby-Token"])
        }
    }

    @Test
    fun series_hierarchy_tolerates_missing_metadata_and_empty_seasons() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"Name":"The Expanse","Overview":"Humanity has spread across the solar system.","BackdropImageTags":["backdrop"]}"""))
            server.enqueue(json("""{"Items":[{"Id":"s1","Name":"Season 1","IndexNumber":1},{"Id":"specials","Name":"Specials"},{"Name":"No identity"}]}"""))
            val gateway = seriesGateway(server)

            val details = gateway.loadSeriesDetails(MediaIdentity("server", "expanse")) as SeriesDetailsState.Ready

            assertEquals(listOf("Season 1", "Specials"), details.details.seasons.map { it.name })
            assertNull(details.details.seasons.last().indexNumber)
            assertEquals("Backdrop", details.details.backdrop?.kind?.routeName)

            server.enqueue(json("""{"Items":[]}"""))
            val empty = gateway.loadSeasonEpisodes(
                MediaIdentity("server", "expanse"), details.details.seasons.first().identity,
            )
            assertTrue(empty is SeasonEpisodesState.Empty)
        }
    }

    @Test
    fun episodes_expose_context_progress_artwork_and_explicit_resume_decision() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"Items":[{"Id":"e1","Name":"Dulcinea","SeriesName":"The Expanse","ParentIndexNumber":1,"IndexNumber":1,"Overview":"A distress call.","RunTimeTicks":25200000000,"ImageTags":{"Primary":"still"},"UserData":{"PlaybackPositionTicks":1200000000}}]}"""))
            val gateway = seriesGateway(server)

            val episodes = gateway.loadSeasonEpisodes(
                MediaIdentity("server", "expanse"), MediaIdentity("server", "s1"),
            ) as SeasonEpisodesState.Ready

            val episode = episodes.episodes.single()
            assertEquals("S1 E1", "S${episode.seasonNumber} E${episode.episodeNumber}")
            assertEquals("A distress call.", episode.overview)
            assertTrue(episode.hasMeaningfulResume)
            assertEquals("still", episode.artwork?.imageTag)

            server.enqueue(json("""{"Id":"e1","Name":"Dulcinea","SeriesName":"The Expanse","ParentIndexNumber":1,"IndexNumber":1,"RunTimeTicks":25200000000,"UserData":{"PlaybackPositionTicks":1200000000},"MediaSources":[{"MediaStreams":[{"Type":"Audio"},{"Type":"Subtitle"}]}]}"""))
            val detail = gateway.loadEpisodeDetails(episode.identity) as EpisodeDetailsState.Ready
            assertTrue(detail.details.episode.hasMeaningfulResume)
            assertEquals(1, detail.details.tracks.audioTracks)
            assertEquals(1, detail.details.tracks.subtitleTracks)
        }
    }

    @Test
    fun failed_next_show_page_preserves_loaded_content_for_retry() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"Items":[]}"""))
            server.enqueue(json(seriesPage("Show", 41, 0, 40)))
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(json(seriesPage("Recovered", 41, 40, 1)))
            val gateway = seriesGateway(server)
            gateway.refreshSeries()

            gateway.loadNextSeriesPage()
            val failed = gateway.seriesLibrary.value as SeriesLibraryState.Ready
            assertEquals(40, failed.items.size)
            assertEquals("Couldn't load more shows.", failed.pageFailureMessage)

            gateway.retrySeriesPage()
            val recovered = gateway.seriesLibrary.value as SeriesLibraryState.Ready
            assertEquals(41, recovered.items.size)
            assertEquals("Recovered 40", recovered.items.last().title)
            assertFalse(recovered.isLoadingMore)
        }
    }

    private fun seriesGateway(server: MockWebServer) = AuthenticatedEmbyGateway(
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

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun seriesPage(prefix: String, total: Int, start: Int, count: Int) =
        (start until start + count).joinToString(",", "{\"Items\":[", "],\"TotalRecordCount\":$total}") {
            "{\"Id\":\"series-$it\",\"Name\":\"$prefix $it\"}"
        }
}
