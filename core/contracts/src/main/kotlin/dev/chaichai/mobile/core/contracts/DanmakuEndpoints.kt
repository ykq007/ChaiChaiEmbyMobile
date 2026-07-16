package dev.chaichai.mobile.core.contracts

/**
 * Per-endpoint routing choice for a Danmaku endpoint. The default is [Direct]: an endpoint inherits
 * direct (un-proxied) routing unless the user selects an explicit [Proxy] override. The override
 * reuses the exact #30 [ServerProxyConfig] type, so the danmaku proxy UI mirrors the per-server proxy
 * UI. Like [ServerProxyConfig], this carries NO secret — proxy credentials live only in the
 * Keystore-protected vault, keyed by the endpoint id.
 *
 * A danmaku endpoint's routing is entirely independent of any Emby Server Address: it never inherits,
 * and can never be handed, a Server Address's Certificate Bypass. Routing here only ever chooses a
 * proxy; TLS trust for a danmaku endpoint is always the system default.
 */
sealed interface DanmakuEndpointRouting {
    data object Direct : DanmakuEndpointRouting

    data class Proxy(val config: ServerProxyConfig) : DanmakuEndpointRouting
}

/**
 * A configured danmaku endpoint as the settings UI sees it. Non-secret: [routing]'s optional proxy
 * config records only whether credentials exist (via [ServerProxyConfig.hasCredentials]), never the
 * username/password. [id] is stable across renames so per-endpoint credentials and routing stay bound
 * to the right endpoint.
 */
data class DanmakuEndpointConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val routing: DanmakuEndpointRouting = DanmakuEndpointRouting.Direct,
)

/**
 * Narrow boundary for managing danmaku endpoints and their per-endpoint Proxy Routing. Android-free;
 * the settings feature talks only to this. Adding/naming/removing endpoints and choosing each
 * endpoint's routing all flow through here; the secret proxy credentials only ever travel *in* via
 * [updateRouting] and are stored in the Keystore-protected vault behind this boundary. Attached to
 * [AppBoundaries] as nullable so its absence is a no-op for existing wiring.
 *
 * Every change is isolated: a failing or reconfigured endpoint never affects another endpoint, and
 * never affects Emby API/artwork/subtitle/playback traffic (those route through a completely separate
 * client path). Routing changes never interrupt active playback — at most danmaku reloads.
 */
interface DanmakuEndpointBoundary {
    /** The current, non-secret endpoint list. */
    fun endpoints(): List<DanmakuEndpointConfig>

    /** Add a new endpoint (Direct routing by default). Returns its freshly assigned stable id. */
    fun addEndpoint(name: String, baseUrl: String): String

    /** Rename an endpoint without disturbing its routing or credentials. */
    fun renameEndpoint(id: String, name: String)

    /** Change an endpoint's base URL without disturbing its routing or credentials. */
    fun updateBaseUrl(id: String, baseUrl: String)

    /** Remove an endpoint and clear any credentials stored for it. */
    fun removeEndpoint(id: String)

    /**
     * Set [id]'s [routing]. When [routing] is a [DanmakuEndpointRouting.Proxy] whose config has
     * hasCredentials true and [credentials] is non-null, the secret is (re)stored in the vault; when
     * hasCredentials is false (or routing reverts to Direct) any stored secret is cleared. A null
     * [credentials] with hasCredentials true leaves an already-stored secret in place.
     */
    fun updateRouting(
        id: String,
        routing: DanmakuEndpointRouting,
        credentials: ProxyCredentials? = null,
    )

    /** Run the distinguished connection test for [id]'s configured route. */
    suspend fun testEndpoint(id: String): ProxyTestResult
}
