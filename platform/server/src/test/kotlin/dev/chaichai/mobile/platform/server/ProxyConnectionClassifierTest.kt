package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConnectionClassifierTest {
    private fun threw(t: Throwable) = classifyProxyProbe(ProxyProbeOutcome.Threw(t))
    private fun responded(code: Int) = classifyProxyProbe(ProxyProbeOutcome.Responded(code))

    @Test fun malformed_config_is_invalid_configuration() {
        val base = ServerProxyConfig(kind = ProxyKind.Http, host = "p", port = 8080)
        assertEquals(ProxyTestResult.InvalidConfiguration, validateProxyForTest(base.copy(host = "")))
        assertEquals(ProxyTestResult.InvalidConfiguration, validateProxyForTest(base.copy(port = 0)))
        assertEquals(ProxyTestResult.InvalidConfiguration, validateProxyForTest(base.copy(port = 70000)))
        assertNull(validateProxyForTest(base))
    }

    @Test fun proxy_407_is_authentication_failure() {
        assertEquals(ProxyTestResult.ProxyAuthenticationFailed, responded(407))
    }

    @Test fun success_status_is_success() {
        assertEquals(ProxyTestResult.Success, responded(200))
        assertEquals(ProxyTestResult.Success, responded(302))
    }

    @Test fun reachable_proxy_with_server_error_is_target_server() {
        assertEquals(ProxyTestResult.TargetServerUnreachable, responded(500))
        assertEquals(ProxyTestResult.TargetServerUnreachable, responded(404))
    }

    @Test fun timeout_dns_tls_and_reachability_are_distinct() {
        assertEquals(ProxyTestResult.Timeout, threw(SocketTimeoutException("t")))
        assertEquals(ProxyTestResult.DnsFailure, threw(UnknownHostException("proxy.example")))
        assertEquals(ProxyTestResult.TlsFailure, threw(SSLHandshakeException("bad cert")))
        assertEquals(ProxyTestResult.ProxyUnreachable, threw(ConnectException("refused")))
        assertEquals(ProxyTestResult.ProxyUnreachable, threw(IOException("other")))
    }

    @Test fun tls_cause_wins_over_generic_io_wrapper() {
        val wrapped = IOException("wrapped", SSLHandshakeException("cert"))
        assertEquals(ProxyTestResult.TlsFailure, threw(wrapped))
    }
}
