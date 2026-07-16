package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.platform.proxy.ProxyProbeOutcome
import dev.chaichai.mobile.platform.proxy.classifyProxyProbe
import dev.chaichai.mobile.platform.proxy.validateProxyForTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Runs a danmaku endpoint connection test through the endpoint's own per-endpoint client, reusing the
 * shared `:platform:proxy` classifier so a danmaku endpoint test distinguishes the same failure
 * classes as the Emby proxy test (auth rejected, unreachable, DNS, timeout, TLS, target error). The
 * test exercises the endpoint's real route via [DanmakuEndpointHttpClients], so it validates the proxy
 * override exactly as live matching would — with no Certificate Bypass anywhere in the path.
 */
class DanmakuEndpointTester(
    private val clients: DanmakuEndpointHttpClients,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun test(endpoint: DanmakuEndpoint): ProxyTestResult = withContext(ioDispatcher) {
        val baseUrl = runCatching { endpoint.baseUrl.trimEnd('/').toHttpUrl() }.getOrNull()
            ?: return@withContext ProxyTestResult.InvalidConfiguration
        (endpoint.routing as? DanmakuEndpointRouting.Proxy)?.let {
            validateProxyForTest(it.config)?.let { invalid -> return@withContext invalid }
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("match").addQueryParameter("title", "").build())
            .get()
            .build()
        val outcome = try {
            clients.clientFor(endpoint).newCall(request).execute().use { ProxyProbeOutcome.Responded(it.code) }
        } catch (error: Exception) {
            ProxyProbeOutcome.Threw(error)
        }
        classifyProxyProbe(outcome)
    }
}
