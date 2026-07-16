package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import dev.chaichai.mobile.platform.proxy.ProxyRoute
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLException

/**
 * Provider traffic is ISOLATED and never inherits the Emby Certificate Bypass (AC5).
 *
 * Structural guarantee: [SubtitleProviderHttpClients] — the ONLY path that builds a provider's OkHttp
 * client — takes no `bypassAuthority` and has no reference to any certificate-trust exception. It lives
 * in `:platform:subtitles`, which does NOT depend on `:platform:server`, so the Emby Certificate Bypass
 * code is not even on this module's classpath (asserted below). Proxy routing is applied per the
 * configured policy via the shared `:platform:proxy` primitive.
 */
class SubtitleProviderIsolationTest {

    @Test
    fun the_emby_certificate_bypass_class_is_not_on_this_modules_classpath() {
        val present = runCatching {
            Class.forName("dev.chaichai.mobile.platform.server.AuthorityScopedHttpClients")
        }.isSuccess
        assertFalse("platform:server must not be on the subtitles module classpath", present)
    }

    @Test
    fun provider_client_rejects_an_untrusted_certificate() {
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()

        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory())
            server.enqueue(MockResponse.Builder().code(200).body("""{"results":[]}""").build())
            server.start()

            val client = OkHttpSubtitleProviderClient()
            val url = server.url("/").newBuilder().host("127.0.0.1").build()
            val provider = SubtitleProvider("u", "Untrusted", url.toString())

            val error = runCatching {
                runBlocking { client.search(provider, SubtitleProviderQuery("x"), null) }
            }.exceptionOrNull()

            assertTrue("Expected a TLS failure, got: $error", generateSequence(error) { it.cause }.any { it is SSLException })
        }
    }

    @Test
    fun routing_is_applied_per_configured_policy() {
        val clients = SubtitleProviderHttpClients()
        val direct = SubtitleProvider("a", "A", "https://a.example")
        assertEquals(ProxyRoute.Direct, clients.routeFor(direct))

        val proxied = SubtitleProvider(
            "b", "B", "https://b.example",
            routing = SubtitleProviderRouting.Proxy(
                ServerProxyConfig(ProxyKind.Http, "p.example", 8080, enabled = true),
            ),
        )
        val route = clients.routeFor(proxied)
        assertTrue(route is ProxyRoute.Through)
        assertEquals("p.example", (route as ProxyRoute.Through).host)

        // LAN bypass keeps a private target off the proxy.
        val lan = SubtitleProvider(
            "c", "C", "http://192.168.1.5:9000",
            routing = SubtitleProviderRouting.Proxy(
                ServerProxyConfig(ProxyKind.Http, "p.example", 8080, enabled = true, lanBypass = true),
            ),
        )
        assertEquals(ProxyRoute.Direct, clients.routeFor(lan))
    }
}
