package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import dev.chaichai.mobile.platform.proxy.ProxyCredentialVault
import dev.chaichai.mobile.platform.proxy.ProxyRoute
import dev.chaichai.mobile.platform.proxy.applyProxyRoute
import dev.chaichai.mobile.platform.proxy.cacheKey
import dev.chaichai.mobile.platform.proxy.resolveProxyRoute
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Builds and caches ONE OkHttp client per subtitle provider, applying that provider's per-provider
 * Proxy Routing via the shared `:platform:proxy` [applyProxyRoute] primitive — the very same primitive
 * the Emby and Danmaku subsystems use. Because every provider gets its own client keyed by
 * (provider id + route), routing is fully independent: changing or breaking one provider's proxy can
 * never touch another provider's client, and none of them are the Emby client, so a subtitle-provider
 * proxy failure is isolated from Emby API/artwork/subtitle/playback.
 *
 * CERTIFICATE BYPASS NON-TRANSFER (AC5): this factory takes NO `bypassAuthority` and has no reference
 * to any certificate-trust exception. It lives in `:platform:subtitles`, which does NOT depend on
 * `:platform:server`, so the Emby Certificate Bypass code is not even on this module's classpath. Every
 * provider client is built with the system-default TLS trust and rejects untrusted certificates — a
 * Server Address's Certificate Bypass can never leak into a subtitle provider or its proxy.
 *
 * Per-provider PROXY credentials (distinct from the provider ACCOUNT credentials the client sends to
 * the provider host) are resolved lazily from [proxyCredentials], keyed by "proxy:{id}", and handed
 * only to that provider's proxy on a 407 — never to the target provider host.
 */
class SubtitleProviderHttpClients(
    private val proxyCredentials: ProxyCredentialVault = InMemoryProxyCredentialVault(),
    private val baseClient: OkHttpClient = defaultBaseClient(),
) {
    private val cache = ConcurrentHashMap<String, OkHttpClient>()

    fun clientFor(provider: SubtitleProvider): OkHttpClient {
        val route = routeFor(provider)
        val key = "${provider.id}|${route.cacheKey()}"
        return cache.getOrPut(key) {
            baseClient.newBuilder()
                .applyProxyRoute(route) { proxyCredentials.load(proxyCredentialKey(provider.id)) }
                .build()
        }
    }

    fun routeFor(provider: SubtitleProvider): ProxyRoute = when (val routing = provider.routing) {
        is SubtitleProviderRouting.Direct -> ProxyRoute.Direct
        is SubtitleProviderRouting.Proxy -> resolveProxyRoute(routing.config, host = provider.hostOrBlank())
    }

    companion object {
        /** Vault key namespace for a provider's PROXY credentials (kept apart from account creds). */
        fun proxyCredentialKey(id: String): String = "proxy:$id"

        /** Vault key namespace for a provider's ACCOUNT credentials (sent to the provider host). */
        fun accountCredentialKey(id: String): String = "auth:$id"

        private fun defaultBaseClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
