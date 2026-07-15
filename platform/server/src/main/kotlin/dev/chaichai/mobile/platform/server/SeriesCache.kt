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
import dev.chaichai.mobile.core.contracts.EpisodeDetails
import dev.chaichai.mobile.core.contracts.EpisodeSummary
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.LibraryQuery
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.SeriesDetails
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SeriesLibrarySnapshot(
    val items: List<MoviePoster>,
    val totalCount: Int,
    val availableGenres: List<String>,
)

interface SeriesCache {
    suspend fun loadLibrary(scope: HomeScope, query: LibraryQuery): SeriesLibrarySnapshot?
    suspend fun saveLibrary(scope: HomeScope, query: LibraryQuery, snapshot: SeriesLibrarySnapshot)
    suspend fun loadSeries(scope: HomeScope, identity: MediaIdentity): SeriesDetails?
    suspend fun saveSeries(scope: HomeScope, details: SeriesDetails)
    suspend fun loadEpisodes(scope: HomeScope, series: MediaIdentity, season: MediaIdentity): List<EpisodeSummary>?
    suspend fun saveEpisodes(scope: HomeScope, series: MediaIdentity, season: MediaIdentity, episodes: List<EpisodeSummary>)
    suspend fun loadEpisode(scope: HomeScope, identity: MediaIdentity): EpisodeDetails?
    suspend fun saveEpisode(scope: HomeScope, details: EpisodeDetails)
    suspend fun clear(scope: HomeScope) = Unit
}

class InMemorySeriesCache : SeriesCache {
    private val libraries = mutableMapOf<Pair<HomeScope, LibraryQuery>, SeriesLibrarySnapshot>()
    private val series = mutableMapOf<Pair<HomeScope, MediaIdentity>, SeriesDetails>()
    private val episodes = mutableMapOf<EpisodeCacheKey, List<EpisodeSummary>>()
    private val episodeDetails = mutableMapOf<Pair<HomeScope, MediaIdentity>, EpisodeDetails>()
    override suspend fun loadLibrary(scope: HomeScope, query: LibraryQuery) = libraries[scope to query]
    override suspend fun saveLibrary(scope: HomeScope, query: LibraryQuery, snapshot: SeriesLibrarySnapshot) { libraries[scope to query] = snapshot }
    override suspend fun loadSeries(scope: HomeScope, identity: MediaIdentity) = series[scope to identity]
    override suspend fun saveSeries(scope: HomeScope, details: SeriesDetails) { series[scope to details.identity] = details }
    override suspend fun loadEpisodes(scope: HomeScope, series: MediaIdentity, season: MediaIdentity) = episodes[EpisodeCacheKey(scope, series, season)]
    override suspend fun saveEpisodes(scope: HomeScope, series: MediaIdentity, season: MediaIdentity, episodes: List<EpisodeSummary>) {
        this.episodes[EpisodeCacheKey(scope, series, season)] = episodes
    }
    override suspend fun loadEpisode(scope: HomeScope, identity: MediaIdentity) = episodeDetails[scope to identity]
    override suspend fun saveEpisode(scope: HomeScope, details: EpisodeDetails) { episodeDetails[scope to details.episode.identity] = details }
    override suspend fun clear(scope: HomeScope) {
        libraries.keys.removeAll { it.first == scope }
        series.keys.removeAll { it.first == scope }
        episodes.keys.removeAll { it.scope == scope }
        episodeDetails.keys.removeAll { it.first == scope }
    }
}

private data class EpisodeCacheKey(val scope: HomeScope, val series: MediaIdentity, val season: MediaIdentity)

fun createRoomSeriesCache(context: Context): SeriesCache = RoomSeriesCache(
    Room.databaseBuilder(context, SeriesCacheDatabase::class.java, "server_scoped_series_cache.db").build().dao(),
)

@Entity(tableName = "series_cache", primaryKeys = ["kind", "serverId", "userId", "key"])
internal data class SeriesCacheEntity(val kind: String, val serverId: String, val userId: String, val key: String, val payload: String)

@Dao
internal interface SeriesCacheDao {
    @Query("SELECT * FROM series_cache WHERE kind=:kind AND serverId=:serverId AND userId=:userId AND `key`=:key")
    suspend fun load(kind: String, serverId: String, userId: String, key: String): SeriesCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(entity: SeriesCacheEntity)
    @Query("DELETE FROM series_cache WHERE serverId=:serverId AND userId=:userId") suspend fun clear(serverId: String, userId: String)
}

@Database(entities = [SeriesCacheEntity::class], version = 1, exportSchema = false)
internal abstract class SeriesCacheDatabase : RoomDatabase() { abstract fun dao(): SeriesCacheDao }

internal class RoomSeriesCache(private val dao: SeriesCacheDao) : SeriesCache {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun loadLibrary(scope: HomeScope, query: LibraryQuery) = load<CachedSeriesLibrary>("library", scope, query.cacheKey())?.toContract()
    override suspend fun saveLibrary(scope: HomeScope, query: LibraryQuery, snapshot: SeriesLibrarySnapshot) =
        save("library", scope, query.cacheKey(), CachedSeriesLibrary.from(snapshot))
    override suspend fun loadSeries(scope: HomeScope, identity: MediaIdentity) = load<CachedSeriesDetails>("series", scope, identity.itemId)?.toContract()
    override suspend fun saveSeries(scope: HomeScope, details: SeriesDetails) = save("series", scope, details.identity.itemId, CachedSeriesDetails.from(details))
    override suspend fun loadEpisodes(scope: HomeScope, series: MediaIdentity, season: MediaIdentity) =
        load<CachedEpisodes>("episodes", scope, "${series.itemId}:${season.itemId}")?.items?.map { it.toContract() }
    override suspend fun saveEpisodes(scope: HomeScope, series: MediaIdentity, season: MediaIdentity, episodes: List<EpisodeSummary>) =
        save("episodes", scope, "${series.itemId}:${season.itemId}", CachedEpisodes(episodes.map(CachedEpisode::from)))
    override suspend fun loadEpisode(scope: HomeScope, identity: MediaIdentity) = load<CachedEpisodeDetails>("episode", scope, identity.itemId)?.toContract(scope)
    override suspend fun saveEpisode(scope: HomeScope, details: EpisodeDetails) = save("episode", scope, details.episode.identity.itemId, CachedEpisodeDetails.from(details))
    override suspend fun clear(scope: HomeScope) = dao.clear(scope.serverId, scope.userId)
    private suspend inline fun <reified T> load(kind: String, scope: HomeScope, key: String): T? =
        dao.load(kind, scope.serverId, scope.userId, key)?.let { json.decodeFromString<T>(it.payload) }
    private suspend inline fun <reified T> save(kind: String, scope: HomeScope, key: String, value: T) {
        dao.save(SeriesCacheEntity(kind, scope.serverId, scope.userId, key, json.encodeToString(value)))
    }
}

private fun LibraryQuery.cacheKey() = "${sortField.name}:${sortDirection.name}:${genre.orEmpty()}"

@Serializable private data class CachedSeriesLibrary(val items: List<CachedPoster>, val total: Int, val genres: List<String>) {
    fun toContract() = SeriesLibrarySnapshot(items.map { it.toContract() }, total, genres)
    companion object { fun from(value: SeriesLibrarySnapshot) = CachedSeriesLibrary(value.items.map(CachedPoster::from), value.totalCount, value.availableGenres) }
}
@Serializable private data class CachedPoster(val server: String, val item: String, val title: String, val year: Int?, val art: String?) {
    fun toContract(): MoviePoster { val id = MediaIdentity(server, item); return MoviePoster(id, title, year, art?.let { dev.chaichai.mobile.core.contracts.ArtworkReference(id, it) }) }
    companion object { fun from(value: MoviePoster) = CachedPoster(value.identity.serverId, value.identity.itemId, value.title, value.year, value.artwork?.imageTag) }
}
@Serializable private data class CachedSeason(val server: String, val item: String, val name: String, val number: Int?)
@Serializable private data class CachedSeriesDetails(
    val server: String, val item: String, val title: String, val year: Int?, val overview: String?, val genres: List<String>,
    val art: String?, val backdrop: String?, val seasons: List<CachedSeason>,
) {
    fun toContract(): SeriesDetails { val id = MediaIdentity(server, item); return SeriesDetails(id, title, year, overview, genres, art?.let { dev.chaichai.mobile.core.contracts.ArtworkReference(id, it) }, backdrop?.let { dev.chaichai.mobile.core.contracts.ArtworkReference(id, it, dev.chaichai.mobile.core.contracts.ArtworkKind.Backdrop) }, seasons.map { dev.chaichai.mobile.core.contracts.SeasonSummary(MediaIdentity(it.server, it.item), it.name, it.number) }) }
    companion object { fun from(v: SeriesDetails) = CachedSeriesDetails(v.identity.serverId, v.identity.itemId, v.title, v.year, v.overview, v.genres, v.artwork?.imageTag, v.backdrop?.imageTag, v.seasons.map { CachedSeason(it.identity.serverId, it.identity.itemId, it.name, it.indexNumber) }) }
}
@Serializable private data class CachedEpisode(
    val server: String, val item: String, val title: String, val series: String?, val season: Int?, val number: Int?,
    val overview: String?, val runtime: Long?, val position: Long, val played: Boolean, val art: String?,
) {
    fun toContract(): EpisodeSummary { val id = MediaIdentity(server, item); return EpisodeSummary(id, title, series, season, number, overview, runtime, position, played, art?.let { dev.chaichai.mobile.core.contracts.ArtworkReference(id, it) }) }
    companion object { fun from(v: EpisodeSummary) = CachedEpisode(v.identity.serverId, v.identity.itemId, v.title, v.seriesName, v.seasonNumber, v.episodeNumber, v.overview, v.runtimeTicks, v.playbackPositionTicks, v.played, v.artwork?.imageTag) }
}
@Serializable private data class CachedEpisodes(val items: List<CachedEpisode>)
@Serializable private data class CachedEpisodeDetails(val episode: CachedEpisode, val community: Double?, val critic: Double?, val genres: List<String>, val audio: Int, val subtitles: Int, val backdrop: String?) {
    fun toContract(scope: HomeScope): EpisodeDetails { val value = episode.toContract(); return EpisodeDetails(value, community, critic, genres, dev.chaichai.mobile.core.contracts.MovieTrackAvailability(audio, subtitles), backdrop?.let { dev.chaichai.mobile.core.contracts.ArtworkReference(value.identity, it, dev.chaichai.mobile.core.contracts.ArtworkKind.Backdrop) }, scope) }
    companion object { fun from(v: EpisodeDetails) = CachedEpisodeDetails(CachedEpisode.from(v.episode), v.communityRating, v.criticRating, v.genres, v.tracks.audioTracks, v.tracks.subtitleTracks, v.backdrop?.imageTag) }
}
