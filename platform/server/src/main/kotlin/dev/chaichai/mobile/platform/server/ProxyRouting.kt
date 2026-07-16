package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.platform.proxy.ProxyRoute

/**
 * Server-side seam over the shared, Android-light `:platform:proxy` primitives. The reusable routing
 * decision, OkHttp proxy application, credential vault, redaction and connection classifier now live in
 * `:platform:proxy` so the Danmaku subsystem can reuse the identical building blocks WITHOUT depending
 * on `:platform:server` (and therefore without any path to Certificate Bypass). Server code imports the
 * proxy primitives directly; this thin overload keeps the [ServerAuthority]-based call sites (and the
 * routing/bypass independence proofs) unchanged.
 *
 * Delegates to the shared, host-based decision in `:platform:proxy`. Certificate Bypass is never
 * consulted here.
 */
fun resolveProxyRoute(config: ServerProxyConfig?, authority: ServerAuthority): ProxyRoute =
    dev.chaichai.mobile.platform.proxy.resolveProxyRoute(config, authority.host)
