package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ArtworkReference
import dev.chaichai.mobile.core.contracts.ArtworkKind
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayAuthenticationStatus
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.HomeSection
import dev.chaichai.mobile.core.contracts.HomeSectionContent
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MovieDetails
import dev.chaichai.mobile.core.contracts.MovieDetailsState
import dev.chaichai.mobile.core.contracts.MovieLibraryQuery
import dev.chaichai.mobile.core.contracts.MovieLibraryState
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.MovieSortField
import dev.chaichai.mobile.core.contracts.MovieTrackAvailability
import dev.chaichai.mobile.core.contracts.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

fun interface SessionVerifier {
    suspend fun verify(session: StoredSession): GatewayAuthenticationStatus
}

class AuthenticatedEmbyGateway(
    private val vault: SessionVault,
    private val clients: AuthorityScopedHttpClients = AuthorityScopedHttpClients(),
    private val homeCache: HomeCache = InMemoryHomeCache(),
    private val movieCache: MovieCache = InMemoryMovieCache(),
    private val deviceId: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EmbyGateway, SessionVerifier {
    private val mutableConnectionState = MutableStateFlow(GatewayConnectionState.Disconnected)
    override val connectionState: StateFlow<GatewayConnectionState> = mutableConnectionState
    private val mutableHomeFeed = MutableStateFlow<HomeFeedState>(HomeFeedState.Loading)
    override val homeFeed: StateFlow<HomeFeedState> = mutableHomeFeed
    private val mutableMovieLibrary = MutableStateFlow<MovieLibraryState>(MovieLibraryState.Loading)
    override val movieLibrary: StateFlow<MovieLibraryState> = mutableMovieLibrary
    private val json = Json { ignoreUnknownKeys = true }
    private var homeGeneration = 0L
    private var movieGeneration = 0L

    var onAuthenticationExpired: ((String?) -> Unit)? = null

    fun setConnected(connected: Boolean) {
        mutableConnectionState.value = if (connected) {
            GatewayConnectionState.Connected
        } else {
            homeGeneration += 1
            movieGeneration += 1
            mutableHomeFeed.value = HomeFeedState.Loading
            mutableMovieLibrary.value = MovieLibraryState.Loading
            GatewayConnectionState.Disconnected
        }
    }

    override suspend fun verifyAuthentication(requestedDestination: String?): GatewayAuthenticationStatus {
        val session = vault.restore() ?: return GatewayAuthenticationStatus.Expired.also {
            setConnected(false)
            onAuthenticationExpired?.invoke(requestedDestination)
        }
        return verify(session).also { status ->
            setConnected(status == GatewayAuthenticationStatus.Valid)
            if (status == GatewayAuthenticationStatus.Expired) {
                onAuthenticationExpired?.invoke(requestedDestination)
            }
        }
    }

    override suspend fun verify(session: StoredSession): GatewayAuthenticationStatus = withContext(ioDispatcher) {
        val request = authenticatedRequest(session)
            .url(session.address.apiUrl("Users/${session.userId}").toString())
            .get()
            .build()
        try {
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(request)
                .execute()
                .use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> GatewayAuthenticationStatus.Expired
                        response.isSuccessful -> GatewayAuthenticationStatus.Valid
                        else -> GatewayAuthenticationStatus.Unavailable
                    }
                }
        } catch (_: Exception) {
            GatewayAuthenticationStatus.Unavailable
        }
    }

    override suspend fun refreshHome() {
        refreshSections(HomeSection.entries.toSet())
    }

    override suspend fun retryHomeSection(section: HomeSection) {
        refreshSections(setOf(section))
    }

    override suspend fun loadArtwork(artwork: ArtworkReference): ByteArray? = withContext(ioDispatcher) {
        val session = vault.restore()?.takeIf { it.serverId == artwork.identity.serverId } ?: return@withContext null
        val scope = HomeScope(session.serverId, session.userId)
        homeCache.loadArtwork(scope, artwork)?.let { return@withContext it }
        val request = authenticatedRequest(session)
            .url(
                session.address.apiUrl("Items/${artwork.identity.itemId}/Images/${artwork.kind.routeName}").toString().toHttpUrl()
                    .newBuilder().addQueryParameter("tag", artwork.imageTag).addQueryParameter("maxWidth", "1280").build(),
            )
            .get()
            .build()
        try {
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(request).execute().use {
                    it.requireAuthenticatedSuccess("Artwork request failed")
                    it.body.bytes().also { bytes -> homeCache.saveArtwork(scope, artwork, bytes) }
                }
        } catch (_: AuthenticationExpiredException) {
            expireAuthentication(null)
            null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun refreshMovies(query: MovieLibraryQuery) = withContext(ioDispatcher) {
        val session = vault.restore()
        if (session == null) {
            mutableMovieLibrary.value = MovieLibraryState.Failure("Sign in again to browse movies.", query = query)
            return@withContext
        }
        val scope = HomeScope(session.serverId, session.userId)
        val generation = ++movieGeneration
        val cached = movieCache.loadLibrary(scope, query)
        if (!isActiveMovieScope(scope, generation)) return@withContext
        mutableMovieLibrary.value = cached?.takeIf { it.items.isNotEmpty() }?.let {
            MovieLibraryState.Ready(scope, it.items, it.totalCount, query, it.availableGenres)
        } ?: MovieLibraryState.Loading
        val refreshed = try {
            val genres = fetchGenres(session)
            val page = fetchMoviePage(session, query, 0)
            if (!isActiveMovieScope(scope, generation)) return@withContext
            movieCache.saveLibrary(scope, query, page.items, page.totalCount, genres)
            if (!isActiveMovieScope(scope, generation)) return@withContext
            when {
                page.items.isNotEmpty() -> MovieLibraryState.Ready(scope, page.items, page.totalCount, query, genres)
                query.genre != null -> MovieLibraryState.EmptyFiltered(scope, query, genres)
                else -> MovieLibraryState.EmptyLibrary(scope, genres)
            }
        } catch (_: AuthenticationExpiredException) {
            expireAuthentication("libraries")
            return@withContext
        } catch (_: Exception) {
            if (!isActiveMovieScope(scope, generation)) return@withContext
            cached?.takeIf { it.items.isNotEmpty() }?.let {
                MovieLibraryState.Ready(
                    scope, it.items, it.totalCount, query, it.availableGenres,
                    refreshFailureMessage = "Couldn't refresh movies. Loaded saved content.",
                )
            } ?: MovieLibraryState.Failure("Movies couldn't be loaded. Check the connection and retry.", scope, query)
        }
        if (isActiveMovieScope(scope, generation)) mutableMovieLibrary.value = refreshed
    }

    override suspend fun loadNextMoviePage() = withContext(ioDispatcher) {
        val ready = mutableMovieLibrary.value as? MovieLibraryState.Ready ?: return@withContext
        if (ready.isLoadingMore || ready.items.size >= ready.totalCount) return@withContext
        val session = vault.restore()?.takeIf { it.serverId == ready.scope.serverId && it.userId == ready.scope.userId }
            ?: return@withContext
        val generation = movieGeneration
        mutableMovieLibrary.value = ready.copy(isLoadingMore = true, pageFailureMessage = null)
        val updated = try {
            val page = fetchMoviePage(session, ready.query, ready.items.size)
            if (!isActiveMovieScope(ready.scope, generation)) return@withContext
            val updated = ready.copy(
                items = (ready.items + page.items).distinctBy { it.identity },
                totalCount = page.totalCount,
                pageFailureMessage = null,
                refreshFailureMessage = null,
            )
            movieCache.saveLibrary(ready.scope, ready.query, updated.items, updated.totalCount, updated.availableGenres)
            if (!isActiveMovieScope(ready.scope, generation)) return@withContext
            updated
        } catch (_: AuthenticationExpiredException) {
            expireAuthentication("libraries")
            return@withContext
        } catch (_: Exception) {
            if (!isActiveMovieScope(ready.scope, generation)) return@withContext
            ready.copy(pageFailureMessage = "Couldn't load more movies.")
        }
        if (isActiveMovieScope(ready.scope, generation)) mutableMovieLibrary.value = updated
    }

    override suspend fun retryMoviePage() = loadNextMoviePage()

    override suspend fun loadMovieDetails(identity: MediaIdentity): MovieDetailsState = withContext(ioDispatcher) {
        val session = vault.restore()?.takeIf { it.serverId == identity.serverId }
            ?: return@withContext MovieDetailsState.Failure("Movie details aren't available for this server.")
        val scope = HomeScope(session.serverId, session.userId)
        try {
            val request = authenticatedRequest(session)
                .url(session.address.apiUrl("Users/${session.userId}/Items/${identity.itemId}").toString().toHttpUrl()
                    .newBuilder().addQueryParameter("Fields", "Overview,Genres,MediaSources,RunTimeTicks,UserData").build())
                .get().build()
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(request).execute().use { response ->
                    response.requireAuthenticatedSuccess("Details request failed")
                    val dto = json.decodeFromString<MovieDetailsDto>(response.body.string())
                    val details = dto.toContract(identity)
                    if (!isActiveScope(scope)) return@use MovieDetailsState.Failure("Movie details are no longer active.")
                    movieCache.saveDetails(scope, details)
                    if (!isActiveScope(scope)) return@use MovieDetailsState.Failure("Movie details are no longer active.")
                    MovieDetailsState.Ready(details)
                }
        } catch (_: AuthenticationExpiredException) {
            expireAuthentication("libraries")
            MovieDetailsState.Failure("Sign in again to view movie details.")
        } catch (_: Exception) {
            val cached = movieCache.loadDetails(scope, identity)
            if (!isActiveScope(scope)) return@withContext MovieDetailsState.Failure("Movie details are no longer active.")
            cached?.copy(tracks = MovieTrackAvailability())?.let(MovieDetailsState::Ready)
                ?: MovieDetailsState.Failure("Movie details couldn't be loaded. Retry when the server is available.")
        }
    }

    private fun fetchGenres(session: StoredSession): List<String> {
        val url = session.address.apiUrl("Genres").toString().toHttpUrl().newBuilder()
            .addQueryParameter("UserId", session.userId)
            .addQueryParameter("IncludeItemTypes", "Movie")
            .addQueryParameter("Recursive", "true")
            .build()
        val request = authenticatedRequest(session).url(url).get().build()
        return clients.forRequest(session.address.authority, session.certificateBypassAuthority)
            .newCall(request).execute().use { response ->
                response.requireAuthenticatedSuccess("Genre request failed")
                val root = json.parseToJsonElement(response.body.string()) as? JsonObject
                (root?.get("Items") as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonObject)?.get("Name")?.let { name ->
                        (name as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                }.distinct().sorted()
            }
    }

    private fun fetchMoviePage(session: StoredSession, query: MovieLibraryQuery, startIndex: Int): MoviePage {
        val url = session.address.apiUrl("Users/${session.userId}/Items").toString().toHttpUrl().newBuilder().apply {
            addQueryParameter("Recursive", "true")
            addQueryParameter("IncludeItemTypes", "Movie")
            addQueryParameter("Fields", "ProductionYear,PrimaryImageAspectRatio")
            addQueryParameter("EnableImageTypes", "Primary")
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("StartIndex", startIndex.toString())
            addQueryParameter("Limit", MoviePageSize.toString())
            addQueryParameter("SortBy", query.sortField.toApiName())
            addQueryParameter("SortOrder", query.sortDirection.toApiName())
            query.genre?.let { addQueryParameter("Genres", it) }
        }.build()
        val request = authenticatedRequest(session).url(url).get().build()
        return clients.forRequest(session.address.authority, session.certificateBypassAuthority)
            .newCall(request).execute().use { response ->
                response.requireAuthenticatedSuccess("Movie request failed")
                val root = json.parseToJsonElement(response.body.string()) as? JsonObject ?: JsonObject(emptyMap())
                val items = (root["Items"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val dto = json.decodeFromJsonElement<MovieListItemDto>(element)
                    dto.id?.let { id -> dto.toContract(session.serverId, id) }
                }
                val total = (root["TotalRecordCount"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toIntOrNull() ?: items.size
                MoviePage(items, total)
            }
    }

    private suspend fun refreshSections(requested: Set<HomeSection>) {
        val session = vault.restore()
        if (session == null) {
            mutableHomeFeed.value = HomeFeedState.Failure("Sign in again to refresh Home.")
            return
        }
        val scope = HomeScope(session.serverId, session.userId)
        val generation = ++homeGeneration
        val current = (mutableHomeFeed.value as? HomeFeedState.Ready)?.takeIf { it.scope == scope }?.sections
        val previous = current ?: homeCache.loadFeed(scope).orEmpty()
        if (!isCurrent(scope, generation)) return
        mutableHomeFeed.value = if (previous.isEmpty()) HomeFeedState.Loading else HomeFeedState.Ready(scope, previous, true)
        loadSections(session, scope, generation, requested, previous)
    }

    private suspend fun loadSections(
        session: StoredSession,
        scope: HomeScope,
        generation: Long,
        requested: Set<HomeSection>,
        previous: Map<HomeSection, HomeSectionContent>,
    ) = withContext(ioDispatcher) {
        val merged = previous.toMutableMap()
        requested.forEach { section ->
            merged[section] = try {
                HomeSectionContent(fetchSection(session, section))
            } catch (_: AuthenticationExpiredException) {
                expireAuthentication("home")
                return@withContext
            } catch (_: Exception) {
                previous[section]?.copy(failureMessage = "Couldn't refresh ${section.title}.", isStale = true)
                    ?: HomeSectionContent(emptyList(), "Couldn't load ${section.title}.")
            }
        }
        val failures = merged.values.count { it.failureMessage != null }
        val successful = merged.values.filter { it.failureMessage == null }
        if (generation != homeGeneration) return@withContext
        if (!isCurrent(scope, generation)) {
            mutableHomeFeed.value = HomeFeedState.Loading
            return@withContext
        }
        val cacheable = merged.filterValues { it.failureMessage == null || it.items.isNotEmpty() }
        if (cacheable.isNotEmpty()) homeCache.saveFeed(scope, cacheable)
        if (!isCurrent(scope, generation)) return@withContext
        mutableHomeFeed.value = when {
            failures == merged.size && merged.values.none { it.items.isNotEmpty() } ->
                HomeFeedState.Failure("Home couldn't be loaded. Check the connection and retry.", scope)
            successful.isNotEmpty() && successful.all { it.items.isEmpty() } && failures == 0 -> HomeFeedState.Empty(scope)
            else -> HomeFeedState.Ready(scope, merged.toMap())
        }
    }

    private fun isCurrent(scope: HomeScope, generation: Long): Boolean {
        if (generation != homeGeneration) return false
        val activeSession = vault.restore() ?: return false
        return HomeScope(activeSession.serverId, activeSession.userId) == scope
    }

    private fun isActiveMovieScope(scope: HomeScope, generation: Long): Boolean =
        generation == movieGeneration && isActiveScope(scope)

    private fun isActiveScope(scope: HomeScope): Boolean {
        val activeSession = vault.restore() ?: return false
        return HomeScope(activeSession.serverId, activeSession.userId) == scope
    }

    private fun fetchSection(session: StoredSession, section: HomeSection): List<HomeMediaItem> {
        val route = when (section) {
            HomeSection.ContinueWatching -> "Users/${session.userId}/Items"
            HomeSection.NextUp -> "Shows/NextUp"
            HomeSection.LatestMovies, HomeSection.LatestEpisodes -> "Users/${session.userId}/Items/Latest"
            HomeSection.AccessibleLibraries -> "Users/${session.userId}/Views"
        }
        val url = session.address.apiUrl(route).toString().toHttpUrl().newBuilder().apply {
            addQueryParameter("UserId", session.userId)
            addQueryParameter("Fields", "Overview,RunTimeTicks,UserData,PrimaryImageAspectRatio")
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("EnableImageTypes", "Primary,Backdrop")
            addQueryParameter("Limit", if (section == HomeSection.AccessibleLibraries) "50" else "20")
            when (section) {
                HomeSection.ContinueWatching -> {
                    addQueryParameter("Recursive", "true")
                    addQueryParameter("Filters", "IsResumable")
                    addQueryParameter("IncludeItemTypes", "Movie,Episode")
                    addQueryParameter("SortBy", "DatePlayed")
                    addQueryParameter("SortOrder", "Descending")
                }
                HomeSection.NextUp -> Unit
                HomeSection.LatestMovies -> addQueryParameter("IncludeItemTypes", "Movie")
                HomeSection.LatestEpisodes -> {
                    addQueryParameter("IncludeItemTypes", "Episode")
                    addQueryParameter("GroupItems", "false")
                }
                HomeSection.AccessibleLibraries -> Unit
            }
        }.build()
        val request = authenticatedRequest(session).url(url).get().build()
        return clients.forRequest(session.address.authority, session.certificateBypassAuthority)
            .newCall(request).execute().use { response ->
                response.requireAuthenticatedSuccess("Home request failed")
                val root = json.parseToJsonElement(response.body.string())
                val elements = when (root) {
                    is JsonArray -> root
                    is JsonObject -> root["Items"] as? JsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                elements.mapNotNull { element ->
                    val dto = json.decodeFromJsonElement<HomeItemDto>(element)
                    dto.id?.let { dto.toContract(session.serverId, it) }
                }
            }
    }

    @Serializable
    private data class HomeItemDto(
        @kotlinx.serialization.SerialName("Id") val id: String? = null,
        @kotlinx.serialization.SerialName("Name") val name: String? = null,
        @kotlinx.serialization.SerialName("Type") val type: String? = null,
        @kotlinx.serialization.SerialName("SeriesName") val seriesName: String? = null,
        @kotlinx.serialization.SerialName("RunTimeTicks") val runtimeTicks: Long? = null,
        @kotlinx.serialization.SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
        @kotlinx.serialization.SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
        @kotlinx.serialization.SerialName("UserData") val userData: HomeUserDataDto? = null,
    ) {
        fun toContract(serverId: String, itemId: String): HomeMediaItem {
            val identity = MediaIdentity(serverId, itemId)
            return HomeMediaItem(
                identity = identity,
                title = name ?: "Untitled",
                mediaType = type ?: "Unknown",
                subtitle = seriesName,
                playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
                runtimeTicks = runtimeTicks,
                artwork = imageTags["Primary"]?.let { ArtworkReference(identity, it) },
                backdrop = backdropImageTags.firstOrNull()?.let {
                    ArtworkReference(identity, it, ArtworkKind.Backdrop)
                },
            )
        }
    }

    @Serializable
    private data class HomeUserDataDto(
        @kotlinx.serialization.SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    )

    @Serializable
    private data class MovieListItemDto(
        @kotlinx.serialization.SerialName("Id") val id: String? = null,
        @kotlinx.serialization.SerialName("Name") val name: String? = null,
        @kotlinx.serialization.SerialName("ProductionYear") val year: Int? = null,
        @kotlinx.serialization.SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    ) {
        fun toContract(serverId: String, itemId: String): MoviePoster {
            val identity = MediaIdentity(serverId, itemId)
            return MoviePoster(
                identity = identity,
                title = name ?: "Untitled",
                year = year,
                artwork = imageTags["Primary"]?.let { ArtworkReference(identity, it) },
            )
        }
    }

    @Serializable
    private data class MovieDetailsDto(
        @kotlinx.serialization.SerialName("Name") val name: String? = null,
        @kotlinx.serialization.SerialName("ProductionYear") val year: Int? = null,
        @kotlinx.serialization.SerialName("RunTimeTicks") val runtimeTicks: Long? = null,
        @kotlinx.serialization.SerialName("CommunityRating") val communityRating: Double? = null,
        @kotlinx.serialization.SerialName("CriticRating") val criticRating: Double? = null,
        @kotlinx.serialization.SerialName("Genres") val genres: List<String> = emptyList(),
        @kotlinx.serialization.SerialName("Overview") val overview: String? = null,
        @kotlinx.serialization.SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
        @kotlinx.serialization.SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
        @kotlinx.serialization.SerialName("UserData") val userData: MovieUserDataDto? = null,
        @kotlinx.serialization.SerialName("MediaSources") val mediaSources: List<MovieMediaSourceDto> = emptyList(),
    ) {
        fun toContract(identity: MediaIdentity): MovieDetails {
            val streams = mediaSources.flatMap { it.mediaStreams }
            return MovieDetails(
                identity = identity,
                title = name ?: "Untitled",
                year = year,
                runtimeTicks = runtimeTicks,
                communityRating = communityRating,
                criticRating = criticRating,
                genres = genres,
                overview = overview,
                playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
                played = userData?.played ?: false,
                tracks = MovieTrackAvailability(
                    audioTracks = streams.count { it.type == "Audio" },
                    subtitleTracks = streams.count { it.type == "Subtitle" },
                ),
                artwork = imageTags["Primary"]?.let { ArtworkReference(identity, it) },
                backdrop = backdropImageTags.firstOrNull()?.let { ArtworkReference(identity, it, ArtworkKind.Backdrop) },
            )
        }
    }

    @Serializable
    private data class MovieUserDataDto(
        @kotlinx.serialization.SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
        @kotlinx.serialization.SerialName("Played") val played: Boolean = false,
    )

    @Serializable
    private data class MovieMediaSourceDto(
        @kotlinx.serialization.SerialName("MediaStreams") val mediaStreams: List<MovieStreamDto> = emptyList(),
    )

    @Serializable
    private data class MovieStreamDto(@kotlinx.serialization.SerialName("Type") val type: String? = null)

    private data class MoviePage(val items: List<MoviePoster>, val totalCount: Int)

    private fun authenticatedRequest(session: StoredSession): Request.Builder = Request.Builder()
        .header("X-Emby-Token", session.accessToken.encoded())
        .header(
            "X-Emby-Authorization",
            "MediaBrowser Client=\"ChaiChai Mobile\", Device=\"Android Mobile\", " +
                "DeviceId=\"$deviceId\", Version=\"0.1.0\", UserId=\"${session.userId}\"",
        )

    private fun Response.requireAuthenticatedSuccess(message: String) {
        if (code == 401 || code == 403) throw AuthenticationExpiredException()
        check(isSuccessful) { message }
    }

    private fun expireAuthentication(destination: String?) {
        setConnected(false)
        onAuthenticationExpired?.invoke(destination)
    }

    private companion object { const val MoviePageSize = 40 }
}

private class AuthenticationExpiredException : Exception()

private fun MovieSortField.toApiName(): String = when (this) {
    MovieSortField.Name -> "SortName"
    MovieSortField.DateAdded -> "DateCreated"
    MovieSortField.ReleaseDate -> "PremiereDate"
}

private fun SortDirection.toApiName(): String = when (this) {
    SortDirection.Ascending -> "Ascending"
    SortDirection.Descending -> "Descending"
}
