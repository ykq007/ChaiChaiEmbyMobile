package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.platform.proxy.ProxyDiagnostics
import dev.chaichai.mobile.platform.proxy.ProxyRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

/**
 * Each danmaku endpoint resolves and applies its per-endpoint route independently (AC1/AC3). Direct
 * endpoints get an un-proxied client; proxy endpoints get a client carrying exactly their proxy.
 */
class DanmakuEndpointRoutingTest {

    private val clients = DanmakuEndpointHttpClients()

    private fun proxyRouting(
        kind: ProxyKind = ProxyKind.Http,
        host: String = "proxy.example",
        port: Int = 8080,
        lanBypass: Boolean = false,
        hasCredentials: Boolean = false,
    ) = DanmakuEndpointRouting.Proxy(
        ServerProxyConfig(kind = kind, host = host, port = port, enabled = true, lanBypass = lanBypass, hasCredentials = hasCredentials),
    )

    @Test
    fun direct_endpoint_routes_directly_with_no_proxy_on_its_client() {
        val endpoint = DanmakuEndpoint("Direct", "https://a.example", id = "a")
        assertTrue(clients.routeFor(endpoint) is ProxyRoute.Direct)
        assertNull(clients.clientFor(endpoint).proxy)
    }

    @Test
    fun http_proxy_endpoint_routes_through_that_exact_proxy() {
        val endpoint = DanmakuEndpoint("Proxied", "https://b.example", id = "b", routing = proxyRouting())
        val route = clients.routeFor(endpoint) as ProxyRoute.Through
        assertEquals("proxy.example", route.host)
        assertEquals(8080, route.port)
        assertEquals(Proxy.Type.HTTP, route.proxyType)
        val proxy = clients.clientFor(endpoint).proxy!!
        assertEquals(Proxy.Type.HTTP, proxy.type())
    }

    @Test
    fun socks_proxy_endpoint_maps_to_socks_type() {
        val endpoint = DanmakuEndpoint("Socks", "https://c.example", id = "c", routing = proxyRouting(kind = ProxyKind.Socks5, port = 1080))
        val route = clients.routeFor(endpoint) as ProxyRoute.Through
        assertEquals(Proxy.Type.SOCKS, route.proxyType)
        assertEquals(Proxy.Type.SOCKS, clients.clientFor(endpoint).proxy!!.type())
    }

    @Test
    fun lan_bypass_keeps_a_private_target_direct() {
        val endpoint = DanmakuEndpoint("Lan", "http://192.168.1.5:7000", id = "lan", routing = proxyRouting(lanBypass = true))
        assertTrue(clients.routeFor(endpoint) is ProxyRoute.Direct)
        assertNull(clients.clientFor(endpoint).proxy)
    }

    @Test
    fun endpoints_get_independent_clients_so_routes_never_cross_contaminate() {
        val direct = DanmakuEndpoint("A", "https://a.example", id = "a")
        val proxied = DanmakuEndpoint("B", "https://b.example", id = "b", routing = proxyRouting(host = "p.example", port = 9000))
        val directClient = clients.clientFor(direct)
        val proxiedClient = clients.clientFor(proxied)
        assertNotSame(directClient, proxiedClient)
        assertNull(directClient.proxy)
        assertEquals(Proxy.Type.HTTP, proxiedClient.proxy!!.type())
        // Re-resolving one endpoint never disturbs the other's cached client.
        assertEquals(directClient, clients.clientFor(direct))
    }

    @Test
    fun endpoint_proxy_credentials_are_redacted_from_diagnostics() {
        val config = ServerProxyConfig(ProxyKind.Http, "proxy.example", 8080, enabled = true, hasCredentials = true)
        val diagnostic = ProxyDiagnostics.describe(config)
        assertTrue(diagnostic.contains("proxy.example"))
        assertTrue(diagnostic.contains(ProxyDiagnostics.REDACTED))
        assertFalse(diagnostic.contains("supersecret"))
    }
}
