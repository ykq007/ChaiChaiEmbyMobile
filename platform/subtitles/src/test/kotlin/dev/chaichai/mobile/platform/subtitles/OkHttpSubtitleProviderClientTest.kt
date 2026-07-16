package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpSubtitleProviderClientTest {

    private val client = OkHttpSubtitleProviderClient()

    private fun provider(url: String) = SubtitleProvider(id = "p1", name = "OpenSubs", baseUrl = url)

    @Test
    fun search_returns_candidates_with_language_metadata_and_provenance() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"results":[
                        {"id":"a1","language":"en","languageLabel":"English","releaseName":"Arrival.2016.1080p",
                         "matchInfo":"exact","format":"srt","downloadUrl":"/download/a1","matchScore":0.98,"hearingImpaired":false},
                        {"id":"a2","language":"es","format":"ass","downloadUrl":"/download/a2"}
                    ]}""",
                ).build(),
            )
            server.start()
            val candidates = client.search(
                provider(server.url("/").toString()),
                SubtitleProviderQuery("Arrival", language = "en", runtimeTicks = 72_000_000_000L),
                account = null,
            )
            assertEquals(2, candidates.size)
            val first = candidates.first()
            assertEquals("p1:a1", first.id)
            assertEquals("en", first.language)
            assertEquals("English", first.languageLabel)
            assertEquals("Arrival.2016.1080p", first.releaseName)
            assertEquals("OpenSubs", first.providerName) // provenance
            assertEquals("p1", first.providerId)
            assertEquals(0.98, first.matchScore!!, 0.001)
            assertEquals("ass", candidates[1].format)
        }
    }

    @Test
    fun search_sends_account_credentials_as_basic_auth_to_the_provider() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"results":[]}""").build())
            server.start()
            client.search(
                provider(server.url("/").toString()),
                SubtitleProviderQuery("x"),
                account = ProxyCredentials("user", "key"),
            )
            val recorded = server.takeRequest()
            assertTrue(recorded.headers["Authorization"]?.startsWith("Basic ") == true)
        }
    }

    @Test
    fun malformed_body_throws_so_the_coordinator_can_contain_it() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("not json").build())
            server.start()
            assertThrows(Exception::class.java) {
                runBlocking { client.search(provider(server.url("/").toString()), SubtitleProviderQuery("x"), null) }
            }
        }
    }

    @Test
    fun auth_rejection_throws_a_distinct_auth_exception() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(401).build())
            server.start()
            assertThrows(SubtitleProviderAuthException::class.java) {
                runBlocking { client.search(provider(server.url("/").toString()), SubtitleProviderQuery("x"), null) }
            }
        }
    }

    @Test
    fun download_returns_the_subtitle_bytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("1\n00:00:01,000 --> 00:00:02,000\nHello\n").build(),
            )
            server.start()
            val candidate = dev.chaichai.mobile.core.contracts.SubtitleCandidate(
                id = "p1:a1", providerId = "p1", providerName = "OpenSubs",
                language = "en", format = "srt", downloadHint = "/download/a1",
            )
            val bytes = client.download(provider(server.url("/").toString()), candidate, null)
            assertTrue(String(bytes).contains("Hello"))
        }
    }

    @Test
    fun download_http_error_throws() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).build())
            server.start()
            val candidate = dev.chaichai.mobile.core.contracts.SubtitleCandidate(
                id = "p1:a1", providerId = "p1", providerName = "OpenSubs",
                language = "en", format = "srt", downloadHint = "/download/a1",
            )
            assertThrows(Exception::class.java) {
                runBlocking { client.download(provider(server.url("/").toString()), candidate, null) }
            }
        }
    }
}
