package dev.chaichai.mobile.platform.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MovieDetails
import dev.chaichai.mobile.core.contracts.MovieLibraryQuery
import dev.chaichai.mobile.core.contracts.MoviePoster
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovieCachePersistenceTest {
    @Test
    fun movie_pages_and_details_survive_recreation_only_inside_server_user_scope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("server_scoped_movie_cache.db")
        val scope = HomeScope("server-a", "user-a")
        val query = MovieLibraryQuery(genre = "Drama")
        val identity = MediaIdentity(scope.serverId, "arrival")
        createRoomMovieCache(context).apply {
            saveLibrary(scope, query, listOf(MoviePoster(identity, "Arrival", 2016)), 1, listOf("Drama"))
            saveDetails(scope, MovieDetails(identity, "Arrival", overview = "Language changes everything."))
        }

        val recreated = createRoomMovieCache(context)

        assertEquals("Arrival", recreated.loadLibrary(scope, query)?.items?.single()?.title)
        assertEquals("Language changes everything.", recreated.loadDetails(scope, identity)?.overview)
        assertNull(recreated.loadLibrary(HomeScope("server-b", "user-a"), query))
        assertNull(recreated.loadDetails(HomeScope("server-a", "user-b"), identity))
    }
}
