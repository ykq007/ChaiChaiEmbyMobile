package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import dev.chaichai.mobile.platform.proxy.ProxyDiagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider credentials (account AND proxy) never appear in any diagnostic string (AC6). The non-secret
 * config surfaced by the manager only records presence; the shared [ProxyDiagnostics] redacts the proxy
 * secret; and nothing the manager returns carries the account username/password.
 */
class SubtitleProviderRedactionTest {

    @Test
    fun proxy_diagnostics_redact_the_provider_proxy_secret() {
        val config = ServerProxyConfig(ProxyKind.Http, "p.example", 8080, enabled = true, hasCredentials = true)
        val described = ProxyDiagnostics.describe(config)
        assertTrue(described.contains(ProxyDiagnostics.REDACTED))
        assertFalse(described.contains("proxypw"))
    }

    @Test
    fun manager_surfaced_config_never_contains_account_or_proxy_secrets() {
        val vault = InMemoryProxyCredentialVault()
        val manager = SubtitleProviderManager(
            InMemorySubtitleProviderConfigStore(),
            vault,
            SubtitleProviderTester(SubtitleProviderHttpClients(vault)),
            idFactory = { "id-fixed" },
        )
        val id = manager.addProvider("OpenSubs", "https://o.example")
        manager.updateCredentials(id, ProxyCredentials("secret-user", "secret-pass"))
        manager.updateRouting(
            id,
            SubtitleProviderRouting.Proxy(ServerProxyConfig(ProxyKind.Http, "p.example", 8080, enabled = true, hasCredentials = true)),
            ProxyCredentials("proxy-user", "proxy-pass"),
        )
        val rendered = manager.providers().toString()
        assertFalse(rendered.contains("secret-user"))
        assertFalse(rendered.contains("secret-pass"))
        assertFalse(rendered.contains("proxy-user"))
        assertFalse(rendered.contains("proxy-pass"))
    }
}
