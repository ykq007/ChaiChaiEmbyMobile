package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The observable result of one proxy probe attempt, decoupled from I/O so the classifier below is a
 * pure, exhaustively unit-testable function from outcome → [ProxyTestResult].
 */
sealed interface ProxyProbeOutcome {
    /** The proxy relayed an HTTP response with this status [code] (proxy itself was reachable). */
    data class Responded(val code: Int) : ProxyProbeOutcome

    /** The attempt threw before any HTTP response arrived. */
    data class Threw(val error: Throwable) : ProxyProbeOutcome
}

/**
 * Pure classification of a proxy probe into the distinguished AC2 result classes. Kept free of any
 * network/OkHttp dependency so every branch is directly testable.
 */
fun classifyProxyProbe(outcome: ProxyProbeOutcome): ProxyTestResult = when (outcome) {
    is ProxyProbeOutcome.Responded -> when {
        outcome.code == 407 -> ProxyTestResult.ProxyAuthenticationFailed
        outcome.code in 200..399 -> ProxyTestResult.Success
        // Any other status means the proxy relayed us to a server that answered with an error.
        else -> ProxyTestResult.TargetServerUnreachable
    }
    is ProxyProbeOutcome.Threw -> classifyThrowable(outcome.error)
}

private fun classifyThrowable(error: Throwable): ProxyTestResult = when {
    error.hasTlsCause() -> ProxyTestResult.TlsFailure
    error.hasCause<SocketTimeoutException>() -> ProxyTestResult.Timeout
    error.hasCause<UnknownHostException>() -> ProxyTestResult.DnsFailure
    error.hasCause<ConnectException>() || error.hasCause<NoRouteToHostException>() ->
        ProxyTestResult.ProxyUnreachable
    else -> ProxyTestResult.ProxyUnreachable
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { it is T }

/** Reject a structurally invalid proxy config before any I/O. Pure; null means "looks usable". */
fun validateProxyForTest(config: ServerProxyConfig): ProxyTestResult? =
    if (config.host.isBlank() || config.port !in 1..65535) ProxyTestResult.InvalidConfiguration else null

/**
 * Runs the Proxy Routing connection test for a server. It builds a client through the shared
 * [AuthorityScopedHttpClients] construction path — forcing the configured proxy route so the proxy
 * is exercised even when not yet enabled for normal traffic — and probes the server's public info
 * endpoint, then classifies the outcome. Certificate Bypass is applied only if the server already
 * had it; the proxy test never turns it on.
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
