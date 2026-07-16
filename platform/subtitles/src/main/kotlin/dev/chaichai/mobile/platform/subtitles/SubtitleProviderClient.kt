package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.SubtitleCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The narrowed search parameters a provider is asked with, derived from the active media. */
data class SubtitleProviderQuery(
    val title: String,
    val language: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeTicks: Long? = null,
)

/**
 * Narrow contract for talking to a single subtitle provider. Implementations are pure I/O and are
 * ALLOWED to throw — the coordinator is the single place that catches and contains failures, so a
 * thrown exception (timeout, auth rejection, malformed body, transport error, download failure) becomes
 * a contained search/activation state and never reaches media playback.
 */
interface SubtitleProviderClient {
    /**
     * Search [provider] for subtitles matching [query]. [account] is the provider's account
     * credentials (sent to the provider host as HTTP Basic auth) or null when the provider is open.
     * Returns candidates carrying provenance + metadata. May throw on transport/auth/decoding failure.
     */
    suspend fun search(
        provider: SubtitleProvider,
        query: SubtitleProviderQuery,
        account: ProxyCredentials?,
    ): List<SubtitleCandidate>

    /** Download the subtitle bytes for [candidate] from [provider]. May throw on failure. */
    suspend fun download(
        provider: SubtitleProvider,
        candidate: SubtitleCandidate,
        account: ProxyCredentials?,
    ): ByteArray
}

/** Thrown when a provider responds 401/403 so the coordinator can map it to a distinct auth state. */
class SubtitleProviderAuthException(message: String) : Exception(message)

/**
 * OkHttp + kotlinx-serialization implementation. This is an independently authored generic JSON shape
 * (see wire models); it does NOT copy any TV Client protocol.
 *
 *   GET {baseUrl}/search?title=&language=&season=&episode=&runtimeTicks=
 *       -> { "results": [ { "id","language","languageLabel?","releaseName?","matchInfo?",
 *                           "format?","downloadUrl","matchScore?","hearingImpaired?" } ] }
 *   GET {downloadUrl (relative to baseUrl or absolute)}  -> subtitle bytes
 *
 * Each request runs on a PER-PROVIDER OkHttp client obtained from [clients], so every provider routes
 * through its own proxy (or directly) fully independently, and none of them can reach the Emby
 * Certificate Bypass (this module never depends on `:platform:server`). Provider ACCOUNT credentials,
 * when present, are attached as HTTP Basic auth to the PROVIDER host only.
 */
class OkHttpSubtitleProviderClient(
    private val clients: SubtitleProviderHttpClients = SubtitleProviderHttpClients(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SubtitleProviderClient {

    override suspend fun search(
        provider: SubtitleProvider,
        query: SubtitleProviderQuery,
        account: ProxyCredentials?,
    ): List<SubtitleCandidate> {
        val url = provider.baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("title", query.title)
            .apply {
                query.language?.let { addQueryParameter("language", it) }
                query.season?.let { addQueryParameter("season", it.toString()) }
                query.episode?.let { addQueryParameter("episode", it.toString()) }
                query.runtimeTicks?.let { addQueryParameter("runtimeTicks", it.toString()) }
            }
            .build()
        val body = get(provider, url.toString(), account)
        return json.decodeFromString<WireSearchResponse>(body).results.map { raw ->
            SubtitleCandidate(
                id = "${provider.id}:${raw.id}",
                providerId = provider.id,
                providerName = provider.name,
                language = raw.language,
                languageLabel = raw.languageLabel?.takeIf(String::isNotBlank),
                releaseName = raw.releaseName?.takeIf(String::isNotBlank),
                matchInfo = raw.matchInfo?.takeIf(String::isNotBlank),
                format = raw.format?.takeIf(String::isNotBlank) ?: "srt",
                downloadHint = raw.downloadUrl,
                matchScore = raw.matchScore,
                hearingImpaired = raw.hearingImpaired,
            )
        }
    }

    override suspend fun download(
        provider: SubtitleProvider,
        candidate: SubtitleCandidate,
        account: ProxyCredentials?,
    ): ByteArray {
        val base = provider.baseUrl.trimEnd('/').toHttpUrl()
        val resolved = base.resolve(candidate.downloadHint) ?: error("Malformed download URL")
        val request = authorized(Request.Builder().url(resolved).get(), account).build()
        clients.clientFor(provider).newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw SubtitleProviderAuthException("Provider rejected the credentials (${response.code}).")
            }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: error("Empty subtitle body")
            if (bytes.isEmpty()) error("Empty subtitle body")
            return bytes
        }
    }

    private fun get(provider: SubtitleProvider, url: String, account: ProxyCredentials?): String {
        val request = authorized(Request.Builder().url(url).get(), account).build()
        clients.clientFor(provider).newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw SubtitleProviderAuthException("Provider rejected the credentials (${response.code}).")
            }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("Empty body")
        }
    }

    private fun authorized(builder: Request.Builder, account: ProxyCredentials?): Request.Builder {
        if (account != null && (account.username.isNotEmpty() || account.password.isNotEmpty())) {
            builder.header("Authorization", Credentials.basic(account.username, account.password))
        }
        return builder
    }
}

@Serializable
private data class WireSearchResponse(val results: List<WireResult> = emptyList())

@Serializable
private data class WireResult(
    val id: String,
    val language: String = "",
    val languageLabel: String? = null,
    val releaseName: String? = null,
    val matchInfo: String? = null,
    val format: String? = null,
    val downloadUrl: String,
    val matchScore: Double? = null,
    val hearingImpaired: Boolean = false,
)
