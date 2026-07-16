package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import dev.chaichai.mobile.platform.proxy.ProxyProbeOutcome
import dev.chaichai.mobile.platform.proxy.classifyProxyProbe
import dev.chaichai.mobile.platform.proxy.validateProxyForTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Runs a subtitle provider connection test through the provider's own per-provider client, reusing the
 * shared `:platform:proxy` classifier so a provider test distinguishes the same failure classes as the
 * Emby and danmaku proxy tests (auth rejected, unreachable, DNS, timeout, TLS, target error). The test
 * exercises the provider's real route via [SubtitleProviderHttpClients], validating the proxy override
 * exactly as a live search would — with no Certificate Bypass anywhere in the path.
 */
class SubtitleProviderTester(
    private val clients: SubtitleProviderHttpClients,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun test(provider: SubtitleProvider): ProxyTestResult = withContext(ioDispatcher) {
        val baseUrl = runCatching { provider.baseUrl.trimEnd('/').toHttpUrl() }.getOrNull()
            ?: return@withContext ProxyTestResult.InvalidConfiguration
        (provider.routing as? SubtitleProviderRouting.Proxy)?.let {
            validateProxyForTest(it.config)?.let { invalid -> return@withContext invalid }
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("search").addQueryParameter("title", "").build())
            .get()
            .build()
        val outcome = try {
            clients.clientFor(provider).newCall(request).execute().use { ProxyProbeOutcome.Responded(it.code) }
        } catch (error: Exception) {
            ProxyProbeOutcome.Threw(error)
        }
        classifyProxyProbe(outcome)
    }
}
