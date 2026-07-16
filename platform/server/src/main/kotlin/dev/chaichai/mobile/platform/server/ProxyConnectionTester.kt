package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.platform.proxy.ProxyProbeOutcome
import dev.chaichai.mobile.platform.proxy.ProxyRoute
import dev.chaichai.mobile.platform.proxy.classifyProxyProbe
import dev.chaichai.mobile.platform.proxy.validateProxyForTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Runs the Proxy Routing connection test for a server. It builds a client through the shared
 * [AuthorityScopedHttpClients] construction path — forcing the configured proxy route so the proxy
 * is exercised even when not yet enabled for normal traffic — and probes the server's public info
 * endpoint, then classifies the outcome via the shared classifier in `:platform:proxy`. Certificate
 * Bypass is applied only if the server already had it; the proxy test never turns it on.
 */
class ProxyConnectionTester(
    private val vault: SessionVault,
    private val store: ServerProxyStore,
    private val clients: AuthorityScopedHttpClients,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun test(serverId: String): ProxyTestResult = withContext(ioDispatcher) {
        val session = vault.sessions().firstOrNull { it.serverId == serverId }
            ?: return@withContext ProxyTestResult.InvalidConfiguration
        val config = store.config(serverId)
        validateProxyForTest(config)?.let { return@withContext it }
        val credentials: ProxyCredentials? = if (config.hasCredentials) store.credentialsFor(serverId) else null
        val route = ProxyRoute.Through(config.kind, config.host.trim(), config.port, config.hasCredentials)
        val client = clients.clientFor(
            authority = session.address.authority,
            bypassAuthority = session.certificateBypassAuthority,
            route = route,
            credentials = credentials,
        )
        val request = Request.Builder()
            .url(session.address.apiUrl(PUBLIC_INFO_ROUTE).toString())
            .get()
            .build()
        val outcome = try {
            client.newCall(request).execute().use { ProxyProbeOutcome.Responded(it.code) }
        } catch (error: Exception) {
            ProxyProbeOutcome.Threw(error)
        }
        classifyProxyProbe(outcome)
    }

    private companion object {
        const val PUBLIC_INFO_ROUTE = "System/Info/Public"
    }
}
