package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.platform.proxy.ProxyRoute
import dev.chaichai.mobile.platform.proxy.isLanAuthority
import dev.chaichai.mobile.platform.proxy.toProxyType
import java.net.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRoutingTest {
    private fun authority(host: String) = ServerAuthority("https", host, 443)

    private val httpProxy = ServerProxyConfig(
        kind = ProxyKind.Http, host = "proxy.example", port = 8080, enabled = true,
    )

    @Test fun kind_maps_to_java_proxy_type() {
        assertEquals(Proxy.Type.HTTP, ProxyKind.Http.toProxyType())
        assertEquals(Proxy.Type.SOCKS, ProxyKind.Socks5.toProxyType())
    }

    @Test fun no_config_or_disabled_or_empty_host_is_direct() {
        assertTrue(resolveProxyRoute(null, authority("emby.example")) is ProxyRoute.Direct)
        assertTrue(resolveProxyRoute(httpProxy.copy(enabled = false), authority("emby.example")) is ProxyRoute.Direct)
        assertTrue(resolveProxyRoute(httpProxy.copy(host = ""), authority("emby.example")) is ProxyRoute.Direct)
        assertTrue(resolveProxyRoute(httpProxy.copy(port = 0), authority("emby.example")) is ProxyRoute.Direct)
    }

    @Test fun enabled_public_authority_routes_through_proxy() {
        val route = resolveProxyRoute(httpProxy, authority("emby.example"))
        assertTrue(route is ProxyRoute.Through)
        route as ProxyRoute.Through
        assertEquals("proxy.example", route.host)
        assertEquals(8080, route.port)
        assertEquals(Proxy.Type.HTTP, route.proxyType)
    }

    @Test fun socks_kind_produces_socks_route() {
        val route = resolveProxyRoute(httpProxy.copy(kind = ProxyKind.Socks5), authority("emby.example"))
        assertEquals(Proxy.Type.SOCKS, (route as ProxyRoute.Through).proxyType)
    }

    @Test fun lan_bypass_sends_private_and_loopback_direct_but_public_through_proxy() {
        val bypass = httpProxy.copy(lanBypass = true)
        // Private / loopback / link-local / mDNS → direct.
        listOf("127.0.0.1", "10.1.2.3", "192.168.0.10", "172.16.5.5", "172.31.9.9",
            "169.254.1.1", "localhost", "nas.local", "::1", "fd12:3456::1").forEach {
            assertTrue("$it should bypass", resolveProxyRoute(bypass, authority(it)) is ProxyRoute.Direct)
        }
        // Public → still proxied even with LAN bypass on.
        listOf("emby.example", "8.8.8.8", "172.32.0.1", "172.15.0.1").forEach {
            assertTrue("$it should proxy", resolveProxyRoute(bypass, authority(it)) is ProxyRoute.Through)
        }
    }

    @Test fun lan_bypass_off_proxies_private_authorities() {
        assertTrue(resolveProxyRoute(httpProxy, authority("192.168.0.10")) is ProxyRoute.Through)
    }

    @Test fun isLanAuthority_table() {
        assertTrue(isLanAuthority("127.0.0.1"))
        assertTrue(isLanAuthority("192.168.1.1"))
        assertFalse(isLanAuthority("172.32.0.1"))
        assertFalse(isLanAuthority("1.1.1.1"))
        assertFalse(isLanAuthority("emby.example"))
    }
}
