package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.ArtworkReference
import dev.chaichai.mobile.core.contracts.GatewayAuthenticationStatus
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.HomeSection
import dev.chaichai.mobile.core.contracts.HomeSectionContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
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
) : EmbyGateway, SessionVerifier {
    private val mutableConnectionState = MutableStateFlow(GatewayConnectionState.Disconnected)
    override val connectionState: StateFlow<GatewayConnectionState> = mutableConnectionState
    private val mutableHomeFeed = MutableStateFlow<HomeFeedState>(HomeFeedState.Loading)
    override val homeFeed: StateFlow<HomeFeedState> = mutableHomeFeed
    private val json = Json { ignoreUnknownKeys = true }

    var onAuthenticationExpired: ((String?) -> Unit)? = null

    fun setConnected(connected: Boolean) {
        mutableConnectionState.value = if (connected) {
            GatewayConnectionState.Connected
        } else {
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

    override suspend fun verify(session: StoredSession): GatewayAuthenticationStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(session.address.apiUrl("Users/${session.userId}").toString())
            .header("X-Emby-Token", session.accessToken.encoded())
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
        val previous = (mutableHomeFeed.value as? HomeFeedState.Ready)?.sections.orEmpty()
        mutableHomeFeed.value = if (previous.isEmpty()) HomeFeedState.Loading else HomeFeedState.Ready(previous, true)
        loadSections(HomeSection.entries.toSet(), previous)
    }

    override suspend fun retryHomeSection(section: HomeSection) {
        val previous = (mutableHomeFeed.value as? HomeFeedState.Ready)?.sections.orEmpty()
        mutableHomeFeed.value = if (previous.isEmpty()) HomeFeedState.Loading else HomeFeedState.Ready(previous, true)
        loadSections(setOf(section), previous)
    }

    override suspend fun loadArtwork(artwork: ArtworkReference): ByteArray? = withContext(Dispatchers.IO) {
        val session = vault.restore()?.takeIf { it.serverId == artwork.serverId } ?: return@withContext null
        val request = Request.Builder()
            .url(
                session.address.apiUrl("Items/${artwork.itemId}/Images/${artwork.imageType}").toString().toHttpUrl()
                    .newBuilder().addQueryParameter("tag", artwork.imageTag).addQueryParameter("maxWidth", "1280").build(),
            )
            .header("X-Emby-Token", session.accessToken.encoded())
            .get()
            .build()
        try {
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(request).execute().use { if (it.isSuccessful) it.body.bytes() else null }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadSections(
        requested: Set<HomeSection>,
        previous: Map<HomeSection, HomeSectionContent>,
    ) = withContext(Dispatchers.IO) {
        val session = vault.restore()
        if (session == null) {
            mutableHomeFeed.value = HomeFeedState.Failure("Sign in again to refresh Home.")
            return@withContext
        }
        val merged = previous.toMutableMap()
        requested.forEach { section ->
            merged[section] = try {
                HomeSectionContent(fetchSection(session, section))
            } catch (_: Exception) {
                previous[section]?.copy(failureMessage = "Couldn't refresh ${section.title}.", isStale = true)
                    ?: HomeSectionContent(emptyList(), "Couldn't load ${section.title}.")
            }
        }
        val failures = merged.values.count { it.failureMessage != null }
        val successful = merged.values.filter { it.failureMessage == null }
        mutableHomeFeed.value = when {
            failures == merged.size && merged.values.none { it.items.isNotEmpty() } ->
                HomeFeedState.Failure("Home couldn't be loaded. Check the connection and retry.")
            successful.isNotEmpty() && successful.all { it.items.isEmpty() } && failures == 0 -> HomeFeedState.Empty()
            else -> HomeFeedState.Ready(merged.toMap())
        }
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
        val request = Request.Builder().url(url).header("X-Emby-Token", session.accessToken.encoded()).get().build()
        return clients.forRequest(session.address.authority, session.certificateBypassAuthority)
            .newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Home request failed" }
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
        fun toContract(serverId: String, itemId: String) = HomeMediaItem(
            serverId = serverId,
            itemId = itemId,
            title = name ?: "Untitled",
            mediaType = type ?: "Unknown",
            subtitle = seriesName,
            playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
            runtimeTicks = runtimeTicks,
            artwork = imageTags["Primary"]?.let { ArtworkReference(serverId, itemId, it) },
            backdrop = backdropImageTags.firstOrNull()?.let { ArtworkReference(serverId, itemId, it, "Backdrop") },
        )
    }

    @Serializable
    private data class HomeUserDataDto(
        @kotlinx.serialization.SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    )
}
