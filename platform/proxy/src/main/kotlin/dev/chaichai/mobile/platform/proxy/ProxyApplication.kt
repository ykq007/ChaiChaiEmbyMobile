package dev.chaichai.mobile.platform.proxy

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * The single reusable way to apply a resolved [ProxyRoute] to an OkHttp client builder. Both the Emby
 * `AuthorityScopedHttpClients` and the Danmaku endpoint clients build their clients through this
 * helper, so proxy application (including HTTP proxy authentication) behaves identically everywhere.
 *
 * [credentials] is resolved lazily on each 407 so a password change takes effect without rebuilding
 * the client and without ever being baked into a cache key. The proxy secret goes ONLY to the proxy
 * (a `Proxy-Authorization` header), never to the target server.
 *
 * Deliberately scoped to routing alone: this helper never touches TLS trust, so applying a proxy can
 * never enable Certificate Bypass.
 */
fun OkHttpClient.Builder.applyProxyRoute(
    route: ProxyRoute,
    credentials: () -> ProxyCredentials?,
): OkHttpClient.Builder {
    if (route !is ProxyRoute.Through) return this
    proxy(Proxy(route.proxyType, InetSocketAddress(route.host, route.port)))
    if (route.hasCredentials) {
        proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
            val c = credentials() ?: return@proxyAuthenticator null
            response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(c.username, c.password))
                .build()
        }
    }
    return this
}

/** A stable cache-key fragment for a route, carrying host/port but never any credential. */
fun ProxyRoute.cacheKey(): String = when (this) {
    is ProxyRoute.Direct -> "direct"
    is ProxyRoute.Through -> "$proxyType:$host:$port:creds=$hasCredentials"
}
