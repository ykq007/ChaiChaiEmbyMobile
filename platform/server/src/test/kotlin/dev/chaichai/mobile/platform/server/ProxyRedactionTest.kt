package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRedactionTest {
    private val secretUser = "topsecretuser"
    private val secretPass = "topsecretpass"

    @Test fun config_diagnostics_expose_host_port_but_never_credentials() {
        val config = ServerProxyConfig(ProxyKind.Http, "proxy.example", 8080, enabled = true, hasCredentials = true)
        val diagnostic = ProxyDiagnostics.describe(config)
        assertTrue(diagnostic.contains("proxy.example"))
        assertTrue(diagnostic.contains("8080"))
        assertTrue(diagnostic.contains(ProxyDiagnostics.REDACTED))
        assertFalse(diagnostic.contains(secretUser))
        assertFalse(diagnostic.contains(secretPass))
    }

    @Test fun route_diagnostics_never_include_credentials() {
        val diagnostic = ProxyDiagnostics.describe(
            ProxyRoute.Through(ProxyKind.Socks5, "proxy.example", 1080, hasCredentials = true),
        )
        assertTrue(diagnostic.contains("proxy.example"))
        assertTrue(diagnostic.contains(ProxyDiagnostics.REDACTED))
        assertFalse(diagnostic.contains(secretUser))
        assertFalse(diagnostic.contains(secretPass))
    }

    @Test fun no_credentials_reads_as_none_not_redacted() {
        val diagnostic = ProxyDiagnostics.describe(
            ServerProxyConfig(ProxyKind.Http, "proxy.example", 8080, enabled = true, hasCredentials = false),
        )
        assertTrue(diagnostic.contains("credentials=none"))
    }
}
