package dev.chaichai.mobile.platform.server

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.chaichai.mobile.core.contracts.ArtworkKind
import dev.chaichai.mobile.core.contracts.ArtworkReference
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.HomeSection
import dev.chaichai.mobile.core.contracts.HomeSectionContent
import dev.chaichai.mobile.core.contracts.MediaIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface HomeCache {
    suspend fun loadFeed(scope: HomeScope): Map<HomeSection, HomeSectionContent>?
    suspend fun saveFeed(scope: HomeScope, sections: Map<HomeSection, HomeSectionContent>)
    suspend fun loadArtwork(scope: HomeScope, reference: ArtworkReference): ByteArray?
    suspend fun saveArtwork(scope: HomeScope, reference: ArtworkReference, bytes: ByteArray)
    suspend fun clear(scope: HomeScope) = Unit
}

class InMemoryHomeCache : HomeCache {
    private val feeds = mutableMapOf<HomeScope, Map<HomeSection, HomeSectionContent>>()
    private val artwork = mutableMapOf<Pair<HomeScope, ArtworkReference>, ByteArray>()
    override suspend fun loadFeed(scope: HomeScope) = feeds[scope]
    override suspend fun saveFeed(scope: HomeScope, sections: Map<HomeSection, HomeSectionContent>) {
        feeds[scope] = sections
    }
    override suspend fun loadArtwork(scope: HomeScope, reference: ArtworkReference) = artwork[scope to reference]
    override suspend fun saveArtwork(scope: HomeScope, reference: ArtworkReference, bytes: ByteArray) {
        artwork[scope to reference] = bytes
    }
    override suspend fun clear(scope: HomeScope) {
        feeds.remove(scope)
        artwork.keys.removeAll { it.first == scope }
    }
}

fun createRoomHomeCache(context: Context): HomeCache = RoomHomeCache(
    Room.databaseBuilder(context, HomeCacheDatabase::class.java, "server_scoped_home_cache.db").build().dao(),
)

@Entity(tableName = "home_feeds", primaryKeys = ["serverId", "userId"])
internal data class HomeFeedEntity(val serverId: String, val userId: String, val payload: String)

@Entity(
    tableName = "home_artwork",
    primaryKeys = ["serverId", "userId", "itemId", "kind", "imageTag"],
)
internal data class HomeArtworkEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val kind: String,
    val imageTag: String,
    val bytes: ByteArray,
)

@Dao
internal interface HomeCacheDao {
    @Query("SELECT * FROM home_feeds WHERE serverId = :serverId AND userId = :userId")
    suspend fun feed(serverId: String, userId: String): HomeFeedEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveFeed(entity: HomeFeedEntity)
    @Query("SELECT * FROM home_artwork WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId AND kind = :kind AND imageTag = :tag")
    suspend fun artwork(serverId: String, userId: String, itemId: String, kind: String, tag: String): HomeArtworkEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveArtwork(entity: HomeArtworkEntity)
    @Query("DELETE FROM home_feeds WHERE serverId=:serverId AND userId=:userId") suspend fun clearFeed(serverId: String, userId: String)
    @Query("DELETE FROM home_artwork WHERE serverId=:serverId AND userId=:userId") suspend fun clearArtwork(serverId: String, userId: String)
}

@Database(entities = [HomeFeedEntity::class, HomeArtworkEntity::class], version = 1, exportSchema = false)
internal abstract class HomeCacheDatabase : RoomDatabase() { abstract fun dao(): HomeCacheDao }

internal class RoomHomeCache(private val dao: HomeCacheDao) : HomeCache {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun loadFeed(scope: HomeScope): Map<HomeSection, HomeSectionContent>? =
        dao.feed(scope.serverId, scope.userId)?.let { json.decodeFromString<CachedFeed>(it.payload).toContract() }

    override suspend fun saveFeed(scope: HomeScope, sections: Map<HomeSection, HomeSectionContent>) {
        dao.saveFeed(HomeFeedEntity(scope.serverId, scope.userId, json.encodeToString(CachedFeed.from(sections))))
    }

    override suspend fun loadArtwork(scope: HomeScope, reference: ArtworkReference): ByteArray? =
        dao.artwork(scope.serverId, scope.userId, reference.identity.itemId, reference.kind.name, reference.imageTag)?.bytes

    override suspend fun saveArtwork(scope: HomeScope, reference: ArtworkReference, bytes: ByteArray) {
        dao.saveArtwork(
            HomeArtworkEntity(
                scope.serverId, scope.userId, reference.identity.itemId, reference.kind.name, reference.imageTag, bytes,
            ),
        )
    }
    override suspend fun clear(scope: HomeScope) {
        dao.clearFeed(scope.serverId, scope.userId)
        dao.clearArtwork(scope.serverId, scope.userId)
    }
}

@Serializable
private data class CachedFeed(val sections: Map<String, CachedSection>) {
    fun toContract() = sections.mapNotNull { (name, section) ->
        HomeSection.entries.firstOrNull { it.name == name }?.let { it to section.toContract() }
    }.toMap()
    companion object {
        fun from(sections: Map<HomeSection, HomeSectionContent>) = CachedFeed(
            sections.mapKeys { it.key.name }.mapValues { CachedSection.from(it.value) },
        )
    }
}

@Serializable
private data class CachedSection(val items: List<CachedItem>) {
    fun toContract() = HomeSectionContent(items.map { it.toContract() })
    companion object { fun from(content: HomeSectionContent) = CachedSection(content.items.map(CachedItem::from)) }
}

@Serializable
private data class CachedItem(
    val serverId: String,
    val itemId: String,
    val title: String,
    val mediaType: String,
    val subtitle: String?,
    val playbackPositionTicks: Long,
    val runtimeTicks: Long?,
    val artworkTag: String?,
    val backdropTag: String?,
) {
    fun toContract(): HomeMediaItem {
        val identity = MediaIdentity(serverId, itemId)
        return HomeMediaItem(
            identity, title, mediaType, subtitle, playbackPositionTicks, runtimeTicks,
            artworkTag?.let { ArtworkReference(identity, it) },
            backdropTag?.let { ArtworkReference(identity, it, ArtworkKind.Backdrop) },
        )
    }
    companion object {
        fun from(item: HomeMediaItem) = CachedItem(
            item.identity.serverId, item.identity.itemId, item.title, item.mediaType, item.subtitle,
            item.playbackPositionTicks, item.runtimeTicks, item.artwork?.imageTag, item.backdrop?.imageTag,
        )
    }
}
