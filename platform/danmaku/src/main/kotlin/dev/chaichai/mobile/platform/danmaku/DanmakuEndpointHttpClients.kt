package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import dev.chaichai.mobile.platform.proxy.ProxyCredentialVault
import dev.chaichai.mobile.platform.proxy.ProxyRoute
import dev.chaichai.mobile.platform.proxy.applyProxyRoute
import dev.chaichai.mobile.platform.proxy.cacheKey
import dev.chaichai.mobile.platform.proxy.resolveProxyRoute
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Builds and caches ONE OkHttp client per danmaku endpoint, applying that endpoint's per-endpoint
 * Proxy Routing via the shared `:platform:proxy` [applyProxyRoute] primitive — the very same primitive
 * the Emby [AuthorityScopedHttpClients][dev.chaichai.mobile.platform.server] uses. Because every
 * endpoint gets its own client keyed by (endpoint id + route), routing is fully independent: changing
 * or breaking one endpoint's proxy can never touch another endpoint's client, and none of them are
 * the Emby client, so a danmaku proxy failure is isolated from Emby API/artwork/subtitle/playback.
 *
 * CERTIFICATE BYPASS NON-TRANSFER (AC4): this factory takes NO `bypassAuthority` and has no reference
 * to any certificate-trust exception. It lives in `:platform:danmaku`, which does not depend on
 * `:platform:server`, so the Emby Certificate Bypass code (`AuthorityScopedHttpClients`
 * .applyCertificateBypass) is not even on this module's classpath. Every danmaku endpoint client is
 * therefore built with the system-default TLS trust and rejects untrusted certificates — a Server
 * Address's Certificate Bypass can never leak into a danmaku endpoint or its proxy.
 *
 * Per-endpoint proxy credentials are resolved lazily from [credentials], keyed by the endpoint id, and
 * handed only to that endpoint's proxy on a 407 (never to the target danmaku host).
 */
class DanmakuEndpointHttpClients(
    private val credentials: ProxyCredentialVault = InMemoryProxyCredentialVault(),
    private val baseClient: OkHttpClient = defaultBaseClient(),
) {
    private val cache = ConcurrentHashMap<String, OkHttpClient>()

    /** The independent OkHttp client for [endpoint], applying its per-endpoint route. */
    fun clientFor(endpoint: DanmakuEndpoint): OkHttpClient {
        val route = routeFor(endpoint)
        val key = "${endpoint.id}|${route.cacheKey()}"
        return cache.getOrPut(key) {
            baseClient.newBuilder()
                .applyProxyRoute(route) { credentials.load(endpoint.id) }
                .build()
        }
    }

    /** The resolved route for [endpoint]: Direct unless it carries an explicit proxy override. */
    fun routeFor(endpoint: DanmakuEndpoint): ProxyRoute = when (val routing = endpoint.routing) {
        is DanmakuEndpointRouting.Direct -> ProxyRoute.Direct
        is DanmakuEndpointRouting.Proxy -> resolveProxyRoute(routing.config, host = endpoint.hostOrBlank())
    }

    private companion object {
        fun defaultBaseClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

/** The endpoint's target host (for LAN-bypass decisions), or blank if the base URL is malformed. */
internal fun DanmakuEndpoint.hostOrBlank(): String =
    runCatching { baseUrl.trimEnd('/').toHttpUrl().host }.getOrDefault("")
