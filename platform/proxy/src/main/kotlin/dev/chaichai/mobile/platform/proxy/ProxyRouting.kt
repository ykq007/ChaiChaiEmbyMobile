package dev.chaichai.mobile.platform.proxy

import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import java.net.Proxy

/**
 * The resolved decision for one authority: connect directly, or through a specific proxy. Pure data;
 * carries no secret (credentials are resolved separately, at the client-building chokepoint, so a
 * route can never leak a proxy password into logs).
 *
 * This lives in the Android-light `:platform:proxy` module so BOTH the Emby server subsystem
 * (`:platform:server`) and the Danmaku subsystem (`:platform:danmaku`) can reuse the identical
 * routing primitive without either depending on the other. Critically, nothing in this module knows
 * anything about Certificate Bypass or Emby sessions, so a Danmaku endpoint client built here can
 * never reach the Server-Address certificate-trust exception.
 */
sealed interface ProxyRoute {
    data object Direct : ProxyRoute

    data class Through(
        val kind: ProxyKind,
        val host: String,
        val port: Int,
        val hasCredentials: Boolean,
    ) : ProxyRoute {
        val proxyType: Proxy.Type = kind.toProxyType()
    }
}

/** Maps the Android-free [ProxyKind] to the concrete `java.net.Proxy.Type`. */
fun ProxyKind.toProxyType(): Proxy.Type = when (this) {
    ProxyKind.Http -> Proxy.Type.HTTP
    ProxyKind.Socks5 -> Proxy.Type.SOCKS
}

/**
 * The one pure, table-driven routing decision. Given a proxy [config] and the target [host] of the
 * request, decide whether to proxy. This function is the ONLY place LAN bypass is applied, so the
 * bypass is explicit and unit-testable.
 *
 * Rules, in order:
 *  - no config, or a disabled config, or a config with no host ⇒ [ProxyRoute.Direct] (no defaults);
 *  - LAN bypass on AND the target [host] is private/loopback ⇒ [ProxyRoute.Direct];
 *  - otherwise ⇒ [ProxyRoute.Through] with the configured kind/host/port.
 *
 * Note what this function deliberately does NOT touch: Certificate Bypass. Proxy Routing and
 * Certificate Bypass are independent decisions; nothing here reads or sets TLS trust.
 */
fun resolveProxyRoute(config: ServerProxyConfig?, host: String): ProxyRoute {
    if (config == null || !config.enabled) return ProxyRoute.Direct
    if (config.host.isBlank() || config.port !in 1..65535) return ProxyRoute.Direct
    if (config.lanBypass && isLanAuthority(host)) return ProxyRoute.Direct
    return ProxyRoute.Through(config.kind, config.host.trim(), config.port, config.hasCredentials)
}

/**
 * Whether [host] is a private, loopback, link-local or mDNS (`.local`) address that LAN bypass keeps
 * off the proxy. Pure and testable; recognizes both IPv4 private ranges and IPv6 loopback/ULA.
 */
fun isLanAuthority(host: String): Boolean {
    val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
    if (h.isEmpty()) return false
    if (h == "localhost" || h.endsWith(".localhost")) return true
    if (h.endsWith(".local")) return true
    // IPv6 loopback and unique-local (fc00::/7) / link-local (fe80::/10).
    if (h == "::1") return true
    if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) return true
    val octets = h.split('.')
    if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
        val (a, b) = octets[0].toInt() to octets[1].toInt()
        return when {
            a == 127 -> true                       // 127.0.0.0/8 loopback
            a == 10 -> true                         // 10.0.0.0/8
            a == 192 && b == 168 -> true            // 192.168.0.0/16
            a == 172 && b in 16..31 -> true         // 172.16.0.0/12
            a == 169 && b == 254 -> true            // 169.254.0.0/16 link-local
            else -> false
        }
    }
    return false
}
