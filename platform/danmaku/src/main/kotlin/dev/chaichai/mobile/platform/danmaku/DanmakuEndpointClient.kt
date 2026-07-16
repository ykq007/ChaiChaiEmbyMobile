package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuComment
import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.core.contracts.DanmakuPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * A user-configured, independently named danmaku endpoint. There are NO embedded defaults; the user
 * supplies the name and base URL. [name] distinguishes endpoints; [baseUrl] is a compatible HTTP
 * host exposing the narrow match/comments JSON contract below.
 *
 * [id] is a stable identifier assigned when the endpoint is added; it survives renames and is the key
 * under which any per-endpoint proxy credentials are stored. Legacy endpoints from #26/#27 (which had
 * no id) migrate with [id] defaulted to [name]. [routing] is the per-endpoint Proxy Routing choice —
 * default [DanmakuEndpointRouting.Direct] so every existing endpoint keeps connecting directly.
 */
data class DanmakuEndpoint(
    val name: String,
    val baseUrl: String,
    val id: String = name,
    val routing: DanmakuEndpointRouting = DanmakuEndpointRouting.Direct,
)

/** Everything the matcher knows about the current media, kept minimal and framework-free. */
data class DanmakuMatchQuery(
    val title: String,
    val runtimeTicks: Long,
    val season: Int? = null,
    val episode: Int? = null,
)

/** A candidate returned by an endpoint for a query, before local scoring picks the best. */
data class DanmakuMatchCandidate(
    val mediaId: String,
    val title: String,
    val runtimeTicks: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Narrow contract for talking to a single danmaku endpoint. Implementations must be pure I/O and are
 * allowed to throw — the controller is the single place that catches and contains failures, so a
 * thrown [Exception] (timeout, malformed body, transport error) becomes a contained danmaku status
 * and never reaches media playback.
 */
interface DanmakuEndpointClient {
    /** Search an endpoint for media matching [query]. May throw on transport/decoding failure. */
    suspend fun match(endpoint: DanmakuEndpoint, query: DanmakuMatchQuery): List<DanmakuMatchCandidate>

    /** Load the full time-indexed comment track for a matched media id. May throw. */
    suspend fun fetchComments(endpoint: DanmakuEndpoint, mediaId: String): List<DanmakuComment>
}

/**
 * OkHttp + kotlinx-serialization implementation of the endpoint contract. This is an independently
 * authored generic JSON shape (see wire models) — it does NOT copy any TV Client protocol.
 *
 *   GET {baseUrl}/match?title=&runtimeTicks=&season=&episode=
 *       -> { "candidates": [ { "mediaId", "title", "runtimeTicks?", "season?", "episode?" } ] }
 *   GET {baseUrl}/comments?mediaId=
 *       -> { "comments": [ { "timeMs", "text", "color?", "position?" } ] }
 *
 * Each request runs on a PER-ENDPOINT OkHttp client obtained from [clients], so every endpoint routes
 * through its own proxy (or directly) fully independently: a dead proxy on one endpoint cannot affect
 * another endpoint, nor any Emby traffic. Critically, [clients] has no notion of Certificate Bypass —
 * a danmaku endpoint client is always built with the system-default TLS trust, so a Server Address's
 * Certificate Bypass can never transfer to a danmaku endpoint or its proxy.
 */
class OkHttpDanmakuEndpointClient(
    private val clients: DanmakuEndpointHttpClients = DanmakuEndpointHttpClients(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DanmakuEndpointClient {

    override suspend fun match(endpoint: DanmakuEndpoint, query: DanmakuMatchQuery): List<DanmakuMatchCandidate> {
        val url = endpoint.baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("match")
            .addQueryParameter("title", query.title)
            .addQueryParameter("runtimeTicks", query.runtimeTicks.toString())
            .apply {
                query.season?.let { addQueryParameter("season", it.toString()) }
                query.episode?.let { addQueryParameter("episode", it.toString()) }
            }
            .build()
        val body = get(endpoint, url.toString())
        return json.decodeFromString<WireMatchResponse>(body).candidates.map {
            DanmakuMatchCandidate(it.mediaId, it.title, it.runtimeTicks, it.season, it.episode)
        }
    }

    override suspend fun fetchComments(endpoint: DanmakuEndpoint, mediaId: String): List<DanmakuComment> {
        val url = endpoint.baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("comments")
            .addQueryParameter("mediaId", mediaId)
            .build()
        val body = get(endpoint, url.toString())
        return json.decodeFromString<WireCommentResponse>(body).comments.map { it.toContract() }
    }

    private fun get(endpoint: DanmakuEndpoint, url: String): String {
        val request = Request.Builder().url(url).get().build()
        clients.clientFor(endpoint).newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("Empty body")
        }
    }
}

@Serializable
private data class WireMatchResponse(val candidates: List<WireCandidate> = emptyList())

@Serializable
private data class WireCandidate(
    val mediaId: String,
    val title: String = "",
    val runtimeTicks: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Serializable
private data class WireCommentResponse(val comments: List<WireComment> = emptyList())

@Serializable
private data class WireComment(
    val timeMs: Long,
    val text: String,
    val color: Int? = null,
    @SerialName("position") val positionName: String? = null,
) {
    fun toContract(): DanmakuComment = DanmakuComment(
        timeTicks = timeMs * 10_000L,
        text = text,
        position = when (positionName?.lowercase()) {
            "top" -> DanmakuPosition.Top
            "bottom" -> DanmakuPosition.Bottom
            else -> DanmakuPosition.Scroll
        },
        colorArgb = color ?: DanmakuComment.DEFAULT_COLOR_ARGB,
    )
}
