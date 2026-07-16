package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.ServerProxyBoundary
import dev.chaichai.mobile.core.contracts.ServerProxyConfig

/**
 * Real [ServerProxyBoundary] over the [ServerProxyStore] (non-secret config + Keystore-protected
 * credentials) and the [ProxyConnectionTester]. It only reads/writes per-server proxy state and runs
 * the connection test; the actual routing is applied elsewhere, at the single
 * [AuthorityScopedHttpClients] chokepoint that shares the same store through [VaultBackedProxySelector].
 */
class ServerProxyManager(
    private val store: ServerProxyStore,
    private val tester: ProxyConnectionTester,
) : ServerProxyBoundary {
    override fun proxyConfig(serverId: String): ServerProxyConfig = store.config(serverId)

    override fun updateProxyConfig(
        serverId: String,
        config: ServerProxyConfig,
        credentials: ProxyCredentials?,
    ) {
        store.update(serverId, config, credentials)
    }

    override suspend fun testConnection(serverId: String): ProxyTestResult = tester.test(serverId)
}
