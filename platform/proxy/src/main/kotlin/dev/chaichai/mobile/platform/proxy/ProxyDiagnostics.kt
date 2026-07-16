package dev.chaichai.mobile.platform.proxy

import dev.chaichai.mobile.core.contracts.ServerProxyConfig

/**
 * Diagnostics for Proxy Routing, shared by the Emby and Danmaku subsystems. Host and port may appear
 * (they aid support), but proxy credentials are NEVER included — the username/password are redacted
 * here so no diagnostic, log or error string built from a proxy config can leak the secret.
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
