package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuEndpointBoundary
import dev.chaichai.mobile.core.contracts.DanmakuEndpointConfig
import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.platform.proxy.ProxyCredentialVault
import java.util.UUID

/**
 * The real [DanmakuEndpointBoundary] over the non-secret [DanmakuConfigStore] and the
 * Keystore-protected [ProxyCredentialVault] (danmaku-namespaced, keyed by endpoint id). It owns
 * add/name/remove and per-endpoint routing, keeping the config's `hasCredentials` flag reconciled with
 * the vault so it can never disagree with (or leak) the presence of a secret.
 *
 * After every mutation it invokes [onConfigChanged]. Production wires that to the controller so a
 * routing change reloads only that endpoint's danmaku (never touching playback); the callback is a
 * no-op in tests. Each endpoint routes through its own client, so a change to one endpoint is isolated
 * from every other endpoint and from all Emby traffic.
 */
class DanmakuEndpointManager(
    private val configStore: DanmakuConfigStore,
    private val vault: ProxyCredentialVault,
    private val tester: DanmakuEndpointTester,
    private val onConfigChanged: () -> Unit = {},
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : DanmakuEndpointBoundary {

    override fun endpoints(): List<DanmakuEndpointConfig> =
        configStore.load().endpoints.map { it.toConfig(hasCredentials = vault.load(it.id) != null) }

    override fun addEndpoint(name: String, baseUrl: String): String {
        val id = idFactory()
        val endpoint = DanmakuEndpoint(name = name.trim(), baseUrl = baseUrl.trim(), id = id)
        configStore.setEndpoints(configStore.load().endpoints + endpoint)
        onConfigChanged()
        return id
    }

    override fun renameEndpoint(id: String, name: String) =
        mutate(id) { it.copy(name = name.trim()) }

    override fun updateBaseUrl(id: String, baseUrl: String) =
        mutate(id) { it.copy(baseUrl = baseUrl.trim()) }

    override fun removeEndpoint(id: String) {
        val remaining = configStore.load().endpoints.filterNot { it.id == id }
        vault.remove(id)
        configStore.setEndpoints(remaining)
        onConfigChanged()
    }

    override fun updateRouting(
        id: String,
        routing: DanmakuEndpointRouting,
        credentials: ProxyCredentials?,
    ) {
        val reconciled = when (routing) {
            is DanmakuEndpointRouting.Direct -> {
                // Reverting to Direct clears any stored secret so it can never linger at rest.
                vault.remove(id)
                DanmakuEndpointRouting.Direct
            }
            is DanmakuEndpointRouting.Proxy -> {
                val config = routing.config
                if (!config.hasCredentials) {
                    vault.remove(id)
                } else if (credentials != null) {
                    vault.save(id, credentials)
                }
                routing
            }
        }
        mutate(id) { it.copy(routing = reconciled) }
    }

    override suspend fun testEndpoint(id: String): ProxyTestResult {
        val endpoint = configStore.load().endpoints.firstOrNull { it.id == id }
            ?: return ProxyTestResult.InvalidConfiguration
        return tester.test(endpoint)
    }

    private inline fun mutate(id: String, transform: (DanmakuEndpoint) -> DanmakuEndpoint) {
        val updated = configStore.load().endpoints.map { if (it.id == id) transform(it) else it }
        configStore.setEndpoints(updated)
        onConfigChanged()
    }
}

/** Overlay the vault-derived credential presence onto the endpoint's non-secret routing config. */
internal fun DanmakuEndpoint.toConfig(hasCredentials: Boolean): DanmakuEndpointConfig =
    DanmakuEndpointConfig(
        id = id,
        name = name,
        baseUrl = baseUrl,
        routing = when (val routing = routing) {
            is DanmakuEndpointRouting.Direct -> DanmakuEndpointRouting.Direct
            is DanmakuEndpointRouting.Proxy ->
                DanmakuEndpointRouting.Proxy(routing.config.copy(hasCredentials = hasCredentials))
        },
    )
