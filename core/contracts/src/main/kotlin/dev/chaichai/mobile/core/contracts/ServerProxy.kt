package dev.chaichai.mobile.core.contracts

/**
 * Which proxy protocol a server's traffic is routed through. Deliberately narrow: exactly the two
 * transports OkHttp/`java.net.Proxy` support without extra dependencies.
 */
enum class ProxyKind { Http, Socks5 }

/**
 * Per-server Proxy Routing configuration. Android-free and, critically, **secret-free**: the proxy
 * username/password never travel inside this type. [hasCredentials] only records whether credentials
 * exist in the Keystore-protected vault so the UI can show/clear them; the secret itself is fetched
 * and applied only inside `platform:server` at the single routing chokepoint.
 *
 * There are NO embedded host/port/credential defaults: a fresh config is [disabled] with an empty
 * host, so absent configuration means direct (un-proxied) traffic.
 *
 * Proxy Routing is entirely independent of Certificate Bypass. Enabling a proxy never enables or
 * inherits Certificate Bypass, and proxy credentials are never sent to the target server.
 */
data class ServerProxyConfig(
    val kind: ProxyKind = ProxyKind.Http,
    val host: String = "",
    val port: Int = 0,
    val enabled: Boolean = false,
    /** When true, private/loopback target authorities skip the proxy and connect directly. */
    val lanBypass: Boolean = false,
    val hasCredentials: Boolean = false,
) {
    companion object {
        /** The migration/no-configuration default: direct traffic, disabled, no credentials. */
        val Direct = ServerProxyConfig()
    }
}

/**
 * Proxy authentication secret. A transient input to [ServerProxyBoundary.updateProxyConfig] only —
 * never persisted inside [ServerProxyConfig], never surfaced back across the boundary, and never
 * included in any diagnostic string.
 */
data class ProxyCredentials(val username: String, val password: String)

/**
 * Outcome of a Proxy Routing connection test. The classes are distinct on purpose (AC2): a proxy
 * test must tell apart a malformed configuration, a proxy-authentication rejection, an unreachable
 * proxy, a DNS failure, a timeout, a TLS failure, and a reachable-proxy-but-target-server failure —
 * never collapsing them into one generic error.
 */
enum class ProxyTestResult(val summary: String) {
    Success("Connected to the server through the proxy."),
    InvalidConfiguration("The proxy host or port is missing or malformed."),
    ProxyAuthenticationFailed("The proxy rejected the supplied credentials."),
    ProxyUnreachable("The proxy could not be reached. Check its host and port."),
    DnsFailure("The proxy host name could not be resolved."),
    Timeout("The connection through the proxy timed out."),
    TlsFailure("The server's certificate could not be verified through the proxy."),
    TargetServerUnreachable("The proxy was reached, but the target server returned an error."),
}

/**
 * Manage per-server Proxy Routing and run its connection test. Narrow and Android-free. The
 * non-secret [ServerProxyConfig] round-trips through here; the secret credentials only ever flow
 * *in* through [updateProxyConfig] and are stored in the Keystore-protected vault behind this
 * boundary. Attached to [AppBoundaries] as nullable so its absence is a no-op for existing wiring.
 */
interface ServerProxyBoundary {
    /** The current (non-secret) proxy config for [serverId]; [ServerProxyConfig.Direct] when none. */
    fun proxyConfig(serverId: String): ServerProxyConfig

    /**
     * Persist [config] for [serverId]. When [config].hasCredentials is true and [credentials] is
     * non-null, the secret is (re)stored in the Keystore-protected vault; when hasCredentials is
     * false the stored secret is cleared. Passing null [credentials] with hasCredentials true leaves
     * any already-stored secret in place (e.g. toggling other fields without re-typing the password).
     */
    fun updateProxyConfig(serverId: String, config: ServerProxyConfig, credentials: ProxyCredentials? = null)

    /** Run the distinguished connection test for [serverId]'s configured proxy against its server. */
    suspend fun testConnection(serverId: String): ProxyTestResult
}
