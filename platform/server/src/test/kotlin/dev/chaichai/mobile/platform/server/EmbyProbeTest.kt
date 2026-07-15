package dev.chaichai.mobile.platform.server

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbyProbeTest {
    @Test
    fun redirects_are_bounded_and_confirmed_before_any_secret_is_sent() = runTest {
        MockWebServer().use { first ->
            MockWebServer().use { final ->
                first.start()
                final.start()
                final.enqueue(
                    MockResponse.Builder()
                        .body("""{"Id":"server-1","ServerName":"Cinema","Version":"4.9.5.0"}""")
                        .build(),
                )
                first.enqueue(
                    MockResponse.Builder()
                        .code(302)
                        .addHeader("Location", final.url("/emby/").toString())
                        .build(),
                )
                val initial = valid(first.url("/start/").toString())

                val result = EmbyProbe().probe(initial)

                val success = result as ProbeResult.Success
                assertEquals(final.url("/emby").toString().removeSuffix("/"), success.finalAddress.value)
                assertEquals(1, success.redirectCount)
                assertEquals(Compatibility.Supported, success.server.compatibility)
                assertNull(first.takeRequest().headers["Authorization"])
                assertNull(final.takeRequest().headers["Authorization"])
            }
        }
    }

    @Test
    fun redirect_chain_has_a_hard_limit() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(6) { index ->
                server.enqueue(
                    MockResponse.Builder()
                        .code(302)
                        .addHeader("Location", server.url("/hop-${index + 1}"))
                        .build(),
                )
            }

            val result = EmbyProbe(maxRedirects = 5).probe(valid(server.url("/hop-0").toString()))

            assertEquals(ProbeFailure.TooManyRedirects, (result as ProbeResult.Failure).reason)
        }
    }

    @Test
    fun supported_4_8_line_is_recognized() = runTest {
        assertEquals(Compatibility.Supported, probeVersion("4.8.11.0").server.compatibility)
    }

    @Test
    fun older_server_is_best_effort_until_a_required_contract_is_proven_incompatible() = runTest {
        assertEquals(Compatibility.BestEffort, probeVersion("3.5.2.0").server.compatibility)
    }

    private suspend fun probeVersion(version: String): ProbeResult.Success {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"Id":"server-1","ServerName":"Cinema","Version":"$version"}""")
                    .build(),
            )
            return EmbyProbe().probe(valid(server.url("/emby").toString())) as ProbeResult.Success
        }
    }

    private fun valid(value: String): ServerAddress =
        (ServerAddress.parse(value) as AddressValidation.Valid).address
}
