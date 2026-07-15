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
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MovieDetails
import dev.chaichai.mobile.core.contracts.MovieLibraryQuery
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.MovieSortField
import dev.chaichai.mobile.core.contracts.MovieTrackAvailability
import dev.chaichai.mobile.core.contracts.SortDirection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class MovieLibrarySnapshot(
    val items: List<MoviePoster>,
    val totalCount: Int,
    val availableGenres: List<String>,
)

interface MovieCache {
    suspend fun loadLibrary(scope: HomeScope, query: MovieLibraryQuery): MovieLibrarySnapshot?
    suspend fun saveLibrary(
        scope: HomeScope,
        query: MovieLibraryQuery,
        items: List<MoviePoster>,
        totalCount: Int,
        availableGenres: List<String>,
    )
    suspend fun loadDetails(scope: HomeScope, identity: MediaIdentity): MovieDetails?
    suspend fun saveDetails(scope: HomeScope, details: MovieDetails)
}

class InMemoryMovieCache : MovieCache {
    private val libraries = mutableMapOf<Pair<HomeScope, MovieLibraryQuery>, MovieLibrarySnapshot>()
    private val details = mutableMapOf<Pair<HomeScope, MediaIdentity>, MovieDetails>()
    override suspend fun loadLibrary(scope: HomeScope, query: MovieLibraryQuery) = libraries[scope to query]
    override suspend fun saveLibrary(scope: HomeScope, query: MovieLibraryQuery, items: List<MoviePoster>, totalCount: Int, availableGenres: List<String>) {
        libraries[scope to query] = MovieLibrarySnapshot(items, totalCount, availableGenres)
    }
    override suspend fun loadDetails(scope: HomeScope, identity: MediaIdentity) = details[scope to identity]
    override suspend fun saveDetails(scope: HomeScope, details: MovieDetails) { this.details[scope to details.identity] = details }
}

fun createRoomMovieCache(context: Context): MovieCache = RoomMovieCache(
    Room.databaseBuilder(context, MovieCacheDatabase::class.java, "server_scoped_movie_cache.db").build().dao(),
)

@Entity(tableName = "movie_libraries", primaryKeys = ["serverId", "userId", "sortField", "sortDirection", "genre"])
internal data class MovieLibraryEntity(
    val serverId: String,
    val userId: String,
    val sortField: String,
    val sortDirection: String,
    val genre: String,
    val payload: String,
)

@Entity(tableName = "movie_details", primaryKeys = ["serverId", "userId", "itemId"])
internal data class MovieDetailsEntity(val serverId: String, val userId: String, val itemId: String, val payload: String)

@Dao
internal interface MovieCacheDao {
    @Query("SELECT * FROM movie_libraries WHERE serverId=:serverId AND userId=:userId AND sortField=:sortField AND sortDirection=:sortDirection AND genre=:genre")
    suspend fun library(serverId: String, userId: String, sortField: String, sortDirection: String, genre: String): MovieLibraryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLibrary(entity: MovieLibraryEntity)
    @Query("SELECT * FROM movie_details WHERE serverId=:serverId AND userId=:userId AND itemId=:itemId")
    suspend fun details(serverId: String, userId: String, itemId: String): MovieDetailsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDetails(entity: MovieDetailsEntity)
}

@Database(entities = [MovieLibraryEntity::class, MovieDetailsEntity::class], version = 1, exportSchema = false)
internal abstract class MovieCacheDatabase : RoomDatabase() { abstract fun dao(): MovieCacheDao }

internal class RoomMovieCache(private val dao: MovieCacheDao) : MovieCache {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun loadLibrary(scope: HomeScope, query: MovieLibraryQuery): MovieLibrarySnapshot? =
        dao.library(scope.serverId, scope.userId, query.sortField.name, query.sortDirection.name, query.genre.orEmpty())
            ?.let { json.decodeFromString<CachedMovieLibrary>(it.payload).toContract() }

    override suspend fun saveLibrary(scope: HomeScope, query: MovieLibraryQuery, items: List<MoviePoster>, totalCount: Int, availableGenres: List<String>) {
        dao.saveLibrary(
            MovieLibraryEntity(
                scope.serverId, scope.userId, query.sortField.name, query.sortDirection.name, query.genre.orEmpty(),
                json.encodeToString(CachedMovieLibrary.from(items, totalCount, availableGenres)),
            ),
        )
    }

    override suspend fun loadDetails(scope: HomeScope, identity: MediaIdentity): MovieDetails? =
        dao.details(scope.serverId, scope.userId, identity.itemId)
            ?.let { json.decodeFromString<CachedMovieDetails>(it.payload).toContract() }

    override suspend fun saveDetails(scope: HomeScope, details: MovieDetails) {
        dao.saveDetails(
            MovieDetailsEntity(scope.serverId, scope.userId, details.identity.itemId, json.encodeToString(CachedMovieDetails.from(details))),
        )
    }
}

@Serializable
private data class CachedMovieLibrary(
    val items: List<CachedMoviePoster>,
    val totalCount: Int,
    val availableGenres: List<String>,
) {
    fun toContract() = MovieLibrarySnapshot(items.map { it.toContract() }, totalCount, availableGenres)
    companion object {
        fun from(items: List<MoviePoster>, totalCount: Int, genres: List<String>) =
            CachedMovieLibrary(items.map(CachedMoviePoster::from), totalCount, genres)
    }
}

@Serializable
private data class CachedMoviePoster(val serverId: String, val itemId: String, val title: String, val year: Int?, val artworkTag: String?) {
    fun toContract(): MoviePoster {
        val identity = MediaIdentity(serverId, itemId)
        return MoviePoster(identity, title, year, artworkTag?.let { ArtworkReference(identity, it) })
    }
    companion object {
        fun from(item: MoviePoster) = CachedMoviePoster(item.identity.serverId, item.identity.itemId, item.title, item.year, item.artwork?.imageTag)
    }
}

@Serializable
private data class CachedMovieDetails(
    val serverId: String,
    val itemId: String,
    val title: String,
    val year: Int?,
    val runtimeTicks: Long?,
    val communityRating: Double?,
    val criticRating: Double?,
    val genres: List<String>,
    val overview: String?,
    val playbackPositionTicks: Long,
    val played: Boolean,
    val audioTracks: Int,
    val subtitleTracks: Int,
    val artworkTag: String?,
    val backdropTag: String?,
) {
    fun toContract(): MovieDetails {
        val identity = MediaIdentity(serverId, itemId)
        return MovieDetails(
            identity, title, year, runtimeTicks, communityRating, criticRating, genres, overview,
            playbackPositionTicks, played, MovieTrackAvailability(audioTracks, subtitleTracks),
            artworkTag?.let { ArtworkReference(identity, it) },
            backdropTag?.let { ArtworkReference(identity, it, ArtworkKind.Backdrop) },
        )
    }
    companion object {
        fun from(details: MovieDetails) = CachedMovieDetails(
            details.identity.serverId, details.identity.itemId, details.title, details.year, details.runtimeTicks,
            details.communityRating, details.criticRating, details.genres, details.overview, details.playbackPositionTicks,
            details.played, details.tracks.audioTracks, details.tracks.subtitleTracks, details.artwork?.imageTag,
            details.backdrop?.imageTag,
        )
    }
}
