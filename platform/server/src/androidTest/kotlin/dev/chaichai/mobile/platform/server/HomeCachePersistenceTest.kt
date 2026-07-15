package dev.chaichai.mobile.platform.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chaichai.mobile.core.contracts.ArtworkReference
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.HomeSection
import dev.chaichai.mobile.core.contracts.HomeSectionContent
import dev.chaichai.mobile.core.contracts.MediaIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeCachePersistenceTest {
    @Test
    fun room_cache_survives_boundary_recreation_without_crossing_server_user_scope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("server_scoped_home_cache.db")
        val scope = HomeScope("server-a", "user-a")
        val identity = MediaIdentity(scope.serverId, "movie")
        val artwork = ArtworkReference(identity, "tag")
        createRoomHomeCache(context).apply {
            saveFeed(scope, mapOf(HomeSection.ContinueWatching to HomeSectionContent(listOf(HomeMediaItem(identity, "Arrival", "Movie")))))
            saveArtwork(scope, artwork, byteArrayOf(1, 2, 3))
        }

        val recreated = createRoomHomeCache(context)

        assertEquals(
            "Arrival",
            recreated.loadFeed(scope)!!.getValue(HomeSection.ContinueWatching).items.single().title,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), recreated.loadArtwork(scope, artwork))
        assertEquals(null, recreated.loadFeed(HomeScope("server-b", "user-a")))
    }
}
