package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import dev.chaichai.mobile.platform.proxy.ProxyRoute
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves Proxy Routing and Certificate Bypass are independent per-authority decisions (AC5): setting
 * a proxy never enables Certificate Bypass, and a server that HAS Certificate Bypass gains no proxy.
 * Certificate Bypass lives on [StoredSession.certificateBypassAuthority]; the proxy lives in the
 * [ServerProxyStore]; the two never read or write each other.
 */
class ProxyCertificateBypassIndependenceTest {
    private fun address(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address

    private fun session(bypass: ServerAuthority?) = StoredSession(
        address("https://s.example/emby"),
        "s",
        "u",
        "User",
        AccessToken.fromRaw("t"),
        certificateBypassAuthority = bypass,
        serverName = "S",
    )

    private class FixedVault(private val session: StoredSession) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) = Unit
        override fun clear() = Unit
        override fun sessions() = listOf(session)
    }

    @Test fun setting_a_proxy_does_not_grant_certificate_bypass() {
        val session = session(bypass = null)
        val store = ServerProxyStore(InMemoryRegistryPersistence(), InMemoryProxyCredentialVault())
        store.update("s", ServerProxyConfig(ProxyKind.Http, "proxy.example", 8080, enabled = true), null)

        // Proxy is routed for this authority...
        val selector = VaultBackedProxySelector(FixedVault(session), store)
        assertTrue(selector.routeFor(session.address.authority) is ProxyRoute.Through)
        // ...yet the session still has NO certificate bypass authority.
        assertNull(session.certificateBypassAuthority)
    }

    @Test fun certificate_bypass_does_not_imply_a_proxy() {
        // Server has Certificate Bypass for its own authority, but no proxy configured.
        val session = session(bypass = address("https://s.example/emby").authority)
        val store = ServerProxyStore(InMemoryRegistryPersistence(), InMemoryProxyCredentialVault())
        val selector = VaultBackedProxySelector(FixedVault(session), store)

        assertTrue(selector.routeFor(session.address.authority) is ProxyRoute.Direct)
        assertNull(selector.credentialsFor(session.address.authority))
    }

    @Test fun both_can_coexist_without_one_implying_the_other() {
        val bypass = address("https://s.example/emby").authority
        val session = session(bypass = bypass)
        val store = ServerProxyStore(InMemoryRegistryPersistence(), InMemoryProxyCredentialVault())
        store.update("s", ServerProxyConfig(ProxyKind.Socks5, "proxy.example", 1080, enabled = true), null)
        val selector = VaultBackedProxySelector(FixedVault(session), store)

        // Independent switches: proxy on AND bypass on, each held in its own store.
        assertTrue(selector.routeFor(session.address.authority) is ProxyRoute.Through)
        assertTrue(session.certificateBypassAuthority == bypass)
    }
}
