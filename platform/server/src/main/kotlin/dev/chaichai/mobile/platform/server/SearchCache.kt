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
import dev.chaichai.mobile.core.contracts.ArtworkReference
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.SearchMediaType
import dev.chaichai.mobile.core.contracts.SearchResult
import dev.chaichai.mobile.core.contracts.SearchResultGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

interface SearchCache {
    suspend fun load(scope: HomeScope, query: String): List<SearchResultGroup>?
    suspend fun save(scope: HomeScope, query: String, groups: List<SearchResultGroup>)
}

class InMemorySearchCache : SearchCache {
    private val entries = mutableMapOf<SearchCacheKey, List<SearchResultGroup>>()

    override suspend fun load(scope: HomeScope, query: String) = entries[SearchCacheKey(scope, query)]

    override suspend fun save(scope: HomeScope, query: String, groups: List<SearchResultGroup>) {
        entries[SearchCacheKey(scope, query)] = groups
    }
}

private data class SearchCacheKey(val scope: HomeScope, val query: String)

fun createRoomSearchCache(context: Context): SearchCache = RoomSearchCache(
    Room.databaseBuilder(context, SearchCacheDatabase::class.java, "server_scoped_search_cache.db").build().dao(),
)

@Entity(tableName = "search_results", primaryKeys = ["serverId", "userId", "query"])
internal data class SearchCacheEntity(
    val serverId: String,
    val userId: String,
    val query: String,
    val payload: String,
)

@Dao
internal interface SearchCacheDao {
    @Query("SELECT * FROM search_results WHERE serverId=:serverId AND userId=:userId AND `query`=:query")
    suspend fun load(serverId: String, userId: String, query: String): SearchCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SearchCacheEntity)

    @Query("DELETE FROM search_results WHERE serverId=:serverId AND userId=:userId AND `query`=:query")
    suspend fun delete(serverId: String, userId: String, query: String)
}

@Database(entities = [SearchCacheEntity::class], version = 1, exportSchema = false)
internal abstract class SearchCacheDatabase : RoomDatabase() {
    abstract fun dao(): SearchCacheDao
}

internal class RoomSearchCache(private val dao: SearchCacheDao) : SearchCache {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(scope: HomeScope, query: String): List<SearchResultGroup>? {
        val entity = dao.load(scope.serverId, scope.userId, query) ?: return null
        return try {
            json.decodeFromString<CachedSearchGroups>(entity.payload).toContract(scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            try {
                dao.delete(scope.serverId, scope.userId, query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A cache miss is still safe when quarantine itself is unavailable.
            }
            null
        }
    }

    override suspend fun save(scope: HomeScope, query: String, groups: List<SearchResultGroup>) {
        dao.save(
            SearchCacheEntity(
                scope.serverId,
                scope.userId,
                query,
                json.encodeToString(CachedSearchGroups.from(groups)),
            ),
        )
    }
}

@Serializable
private data class CachedSearchGroups(val groups: List<CachedSearchGroup>) {
    fun toContract(scope: HomeScope) = groups.map { it.toContract(scope) }

    companion object {
        fun from(groups: List<SearchResultGroup>) = CachedSearchGroups(groups.map(CachedSearchGroup::from))
    }
}

@Serializable
private data class CachedSearchGroup(val type: String, val items: List<CachedSearchResult>) {
    fun toContract(scope: HomeScope) = SearchResultGroup(
        SearchMediaType.valueOf(type),
        items.map { it.toContract(scope) },
    )

    companion object {
        fun from(group: SearchResultGroup) = CachedSearchGroup(group.mediaType.name, group.items.map(CachedSearchResult::from))
    }
}

@Serializable
private data class CachedSearchResult(
    val itemId: String,
    val type: String,
    val title: String,
    val year: Int?,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val seriesItemId: String?,
    val seasonItemId: String?,
    val artworkTag: String?,
) {
    fun toContract(scope: HomeScope): SearchResult {
        val identity = MediaIdentity(scope.serverId, itemId)
        return SearchResult(
            scope = scope,
            identity = identity,
            mediaType = SearchMediaType.valueOf(type),
            title = title,
            year = year,
            seriesName = seriesName,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            seriesIdentity = seriesItemId?.let { MediaIdentity(scope.serverId, it) },
            seasonIdentity = seasonItemId?.let { MediaIdentity(scope.serverId, it) },
            artwork = artworkTag?.let { ArtworkReference(identity, it) },
        )
    }

    companion object {
        fun from(result: SearchResult) = CachedSearchResult(
            result.identity.itemId,
            result.mediaType.name,
            result.title,
            result.year,
            result.seriesName,
            result.seasonNumber,
            result.episodeNumber,
            result.seriesIdentity?.itemId,
            result.seasonIdentity?.itemId,
            result.artwork?.imageTag,
        )
    }
}
