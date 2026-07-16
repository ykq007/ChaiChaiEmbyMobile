package dev.chaichai.mobile.platform.proxy

import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The observable result of one proxy probe attempt, decoupled from I/O so the classifier below is a
 * pure, exhaustively unit-testable function from outcome → [ProxyTestResult]. Shared by the Emby and
 * Danmaku connection tests so both distinguish the same failure classes.
 */
sealed interface ProxyProbeOutcome {
    /** The proxy relayed an HTTP response with this status [code] (proxy itself was reachable). */
    data class Responded(val code: Int) : ProxyProbeOutcome

    /** The attempt threw before any HTTP response arrived. */
    data class Threw(val error: Throwable) : ProxyProbeOutcome
}

/**
 * Pure classification of a proxy probe into the distinguished result classes. Kept free of any
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

private fun Throwable.hasTlsCause(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException
    }

/** Reject a structurally invalid proxy config before any I/O. Pure; null means "looks usable". */
fun validateProxyForTest(config: ServerProxyConfig): ProxyTestResult? =
    if (config.host.isBlank() || config.port !in 1..65535) ProxyTestResult.InvalidConfiguration else null
