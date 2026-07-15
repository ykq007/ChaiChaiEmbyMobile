package dev.chaichai.mobile.platform.server

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.SearchMediaType
import dev.chaichai.mobile.core.contracts.SearchResult
import dev.chaichai.mobile.core.contracts.SearchResultGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchCachePersistenceTest {
    @Test
    fun room_search_cache_restores_full_provenance_for_only_the_matching_scope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("server_scoped_search_cache.db")
        val scope = HomeScope("server", "user")
        val series = MediaIdentity("server", "series")
        val season = MediaIdentity("server", "season")
        createRoomSearchCache(context).save(
            scope,
            "dulcinea",
            listOf(
                SearchResultGroup(
                    SearchMediaType.Episode,
                    listOf(
                        SearchResult(
                            scope,
                            MediaIdentity("server", "episode"),
                            SearchMediaType.Episode,
                            "Dulcinea",
                            seriesName = "The Expanse",
                            seasonNumber = 1,
                            episodeNumber = 1,
                            seriesIdentity = series,
                            seasonIdentity = season,
                        ),
                    ),
                ),
            ),
        )

        val recreated = createRoomSearchCache(context)
        val restored = recreated.load(scope, "dulcinea")!!.single().items.single()

        assertEquals(MediaIdentity("server", "episode"), restored.identity)
        assertEquals(series, restored.seriesIdentity)
        assertEquals(season, restored.seasonIdentity)
        assertEquals(HomeScope("server", "user"), restored.scope)
        assertNull(recreated.load(HomeScope("server", "other-user"), "dulcinea"))
    }

    @Test
    fun malformed_and_obsolete_cache_payloads_are_deleted_and_treated_as_misses() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "corrupt_search_cache.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, SearchCacheDatabase::class.java, databaseName).build()
        val scope = HomeScope("server", "user")
        try {
            listOf(
                "malformed" to "{not-json",
                "obsolete" to """{"groups":[{"type":"FutureMedia","items":[]}]}""",
            ).forEach { (query, payload) ->
                database.dao().save(SearchCacheEntity(scope.serverId, scope.userId, query, payload))

                assertNull(RoomSearchCache(database.dao()).load(scope, query))
                assertNull(database.dao().load(scope.serverId, scope.userId, query))
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
