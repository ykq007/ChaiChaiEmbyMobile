package dev.chaichai.mobile.platform.danmaku

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLException

/**
 * Certificate Bypass NEVER transfers to a danmaku endpoint or proxy (AC4).
 *
 * Structural guarantee: [DanmakuEndpointHttpClients] — the ONLY path that builds a danmaku endpoint's
 * OkHttp client — takes no `bypassAuthority` and has no reference to any certificate-trust exception.
 * It lives in `:platform:danmaku`, which does not depend on `:platform:server`, so the Emby
 * Certificate Bypass code is not even on this module's classpath.
 *
 * Behavioural proof (below): a danmaku endpoint served over HTTPS with an UNTRUSTED (self-signed)
 * certificate fails the TLS handshake. If any Certificate Bypass could leak in, the handshake would
 * succeed; it must not. The default danmaku client uses the system-default trust store, exactly as an
 * endpoint on the same host as a Certificate-Bypass'd Emby server would.
 */
class DanmakuCertificateBypassNonTransferTest {

    @Test
    fun danmaku_endpoint_client_rejects_an_untrusted_certificate() {
        // SAN covers the exact host we connect to, so the ONLY reason the handshake can fail is that
        // the certificate is untrusted (self-signed) — proving trust is the system default, not bypass.
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()

        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory())
            server.enqueue(MockResponse.Builder().code(200).body("""{"candidates":[]}""").build())
            server.start()

            // A default danmaku endpoint client (system-default TLS trust — no bypass).
            val client = OkHttpDanmakuEndpointClient()
            val url = server.url("/").newBuilder().host("127.0.0.1").build()
            val endpoint = DanmakuEndpoint("Untrusted", url.toString(), id = "u")

            val error = runCatching {
                runBlocking { client.match(endpoint, DanmakuMatchQuery("x", 1)) }
            }.exceptionOrNull()

            assertTrue("Expected a TLS failure, got: $error", generateSequence(error) { it.cause }.any { it is SSLException })
        }
    }
}
