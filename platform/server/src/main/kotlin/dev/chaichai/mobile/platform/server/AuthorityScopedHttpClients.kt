package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.platform.proxy.ProxyRoute
import dev.chaichai.mobile.platform.proxy.applyProxyRoute
import dev.chaichai.mobile.platform.proxy.cacheKey
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The SINGLE place OkHttp clients for a Server Address authority are built. Every traffic class —
 * API, authenticated artwork, playback and subtitle — resolves its client through [forRequest], so
 * they all share one routing policy: whatever proxy the [ServerProxySelector] decides for that
 * authority is applied to all of them, and none of them can silently opt out.
 *
 * Two independent per-authority decisions are made here and MUST NOT be conflated:
 *  - Proxy Routing (via [selector]) — whether/what proxy to route through, plus proxy credentials;
 *  - Certificate Bypass (via [bypassAuthority] equality) — whether to accept an untrusted cert.
 * Applying a proxy never touches TLS trust, and enabling Certificate Bypass never touches routing.
 */
class AuthorityScopedHttpClients(
    private val selector: ServerProxySelector = DirectProxySelector,
) {
    private val cache = ConcurrentHashMap<String, OkHttpClient>()

    fun forRequest(authority: ServerAuthority, bypassAuthority: ServerAuthority?): OkHttpClient {
        val route = selector.routeFor(authority)
        val bypass = bypassAuthority == authority
        val key = "${route.cacheKey()}|bypass=$bypass|host=${authority.host}"
        return cache.getOrPut(key) {
            // Credentials are resolved lazily on each 407 so a password change takes effect without
            // rebuilding (and without ever being baked into the cache key).
            build(authority, bypass, route) { selector.credentialsFor(authority) }
        }
    }

    /**
     * Build an un-cached client for an explicit [route] and [credentials]. Used by the connection
     * test so it can exercise a configured proxy directly, through this exact construction path, even
     * when the proxy is not (yet) enabled for normal traffic.
     */
    fun clientFor(
        authority: ServerAuthority,
        bypassAuthority: ServerAuthority?,
        route: ProxyRoute,
        credentials: ProxyCredentials?,
    ): OkHttpClient = build(authority, bypassAuthority == authority, route) { credentials }

    private fun build(
        authority: ServerAuthority,
        bypass: Boolean,
        route: ProxyRoute,
        credentials: () -> ProxyCredentials?,
    ): OkHttpClient {
        // Proxy application is the shared `:platform:proxy` primitive; Certificate Bypass is applied
        // here and ONLY here, gated on an authority-equality check that danmaku clients never reach.
        val builder = baseBuilder().applyProxyRoute(route, credentials)
        if (bypass) applyCertificateBypass(builder, authority)
        return builder.build()
    }

    private fun applyCertificateBypass(builder: OkHttpClient.Builder, authority: ServerAuthority) {
        // This trust exception is reachable only through an authority equality check. Automatic
        // redirects are disabled, so a redirected authority always returns to the normal client.
        val scopedTrust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(scopedTrust), SecureRandom())
        }
        builder
            .sslSocketFactory(context.socketFactory, scopedTrust)
            .hostnameVerifier { host, _ -> host.equals(authority.host, ignoreCase = true) }
    }

    private fun baseBuilder() = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(Duration.ofSeconds(12))
}
