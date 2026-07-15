package dev.chaichai.mobile.platform.server

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CertificateBypassTest {
    @Test
    fun certificate_bypass_is_disabled_by_default_and_enabled_for_one_confirmed_authority() = runTest {
        tlsServer().use { server ->
            server.start()
            server.enqueue(publicInfo())
            server.enqueue(publicInfo())
            val address = valid(server.url("/emby").toString())

            assertTrue(EmbyProbe().probe(address) is ProbeResult.Failure)
            val bypassed = EmbyProbe().probe(address, address.authority)

            assertEquals(address, (bypassed as ProbeResult.Success).finalAddress)
        }
    }

    @Test
    fun certificate_bypass_does_not_transfer_to_a_redirected_authority() = runTest {
        tlsServer().use { first ->
            tlsServer().use { redirected ->
                first.start()
                redirected.start()
                first.enqueue(
                    MockResponse.Builder().code(302).addHeader("Location", redirected.url("/emby")).build(),
                )
                redirected.enqueue(publicInfo())
                val firstAddress = valid(first.url("/emby").toString())

                val result = EmbyProbe().probe(firstAddress, firstAddress.authority)

                assertTrue(result is ProbeResult.Failure)
            }
        }
    }

    private fun tlsServer(): MockWebServer {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        return MockWebServer().apply { useHttps(certificates.sslSocketFactory()) }
    }

    private fun publicInfo() = MockResponse.Builder()
        .body("""{"Id":"server","ServerName":"Cinema","Version":"4.9.5.0"}""")
        .build()

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
}
