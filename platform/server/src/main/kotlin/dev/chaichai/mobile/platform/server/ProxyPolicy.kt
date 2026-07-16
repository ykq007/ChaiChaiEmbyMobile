package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ServerProxyConfig

/**
 * Resolves the [ProxyRoute] and proxy credentials for a target [ServerAuthority]. This is the seam
 * [AuthorityScopedHttpClients] consults so that Proxy Routing is decided in exactly one place: every
 * API, artwork, playback and subtitle client for an authority is built through the same selector.
 */
interface ServerProxySelector {
    fun routeFor(authority: ServerAuthority): ProxyRoute
    fun credentialsFor(authority: ServerAuthority): ProxyCredentials?
}

/** No proxy for anyone — the default that keeps every existing client construction direct. */
object DirectProxySelector : ServerProxySelector {
    override fun routeFor(authority: ServerAuthority): ProxyRoute = ProxyRoute.Direct
    override fun credentialsFor(authority: ServerAuthority): ProxyCredentials? = null
}

/**
 * Maps a target authority to its server's stored proxy config by finding the stored session whose
 * Server Address authority matches, then applying the pure [resolveProxyRoute] decision. Credentials
 * are fetched only for the matched server, so a proxy secret can never be handed to a request for a
 * different authority (AC5 — no cross-authority credential leak).
 */
class VaultBackedProxySelector(
    private val vault: SessionVault,
    private val store: ServerProxyStore,
) : ServerProxySelector {
    private fun serverIdFor(authority: ServerAuthority): String? =
        vault.sessions().firstOrNull { it.address.authority == authority }?.serverId

    override fun routeFor(authority: ServerAuthority): ProxyRoute {
        val serverId = serverIdFor(authority) ?: return ProxyRoute.Direct
        return resolveProxyRoute(store.config(serverId), authority)
    }

    override fun credentialsFor(authority: ServerAuthority): ProxyCredentials? {
        val serverId = serverIdFor(authority) ?: return null
        // Only surface credentials when this authority actually routes through the proxy.
        if (routeFor(authority) !is ProxyRoute.Through) return null
        return store.credentialsFor(serverId)
    }
}

/**
 * Diagnostics for Proxy Routing. Host and port may appear (they aid support), but proxy credentials
 * are NEVER included — the username/password are redacted here so no diagnostic, log or error string
 * built from a proxy config can leak the secret (AC6 redaction).
 */
object ProxyDiagnostics {
    fun describe(config: ServerProxyConfig): String = buildString {
        append("proxy(kind=").append(config.kind.name)
        append(", host=").append(config.host.ifBlank { "<none>" })
        append(", port=").append(config.port)
        append(", enabled=").append(config.enabled)
        append(", lanBypass=").append(config.lanBypass)
        append(", credentials=").append(if (config.hasCredentials) REDACTED else "none")
        append(')')
    }

    /** A route description that, like [describe], carries host/port but never any credential. */
    fun describe(route: ProxyRoute): String = when (route) {
        is ProxyRoute.Direct -> "route(direct)"
        is ProxyRoute.Through -> "route(kind=${route.kind.name}, host=${route.host}, port=${route.port}, " +
            "credentials=${if (route.hasCredentials) REDACTED else "none"})"
    }

    const val REDACTED: String = "<redacted>"
}
