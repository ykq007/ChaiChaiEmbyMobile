package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuPosition
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OkHttpDanmakuEndpointClientTest {

    private val client = OkHttpDanmakuEndpointClient()

    @Test
    fun parses_match_candidates_from_the_narrow_json_contract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"candidates":[{"mediaId":"m1","title":"The Movie","runtimeTicks":72000000000,"season":1,"episode":2}]}""",
                ).build(),
            )
            server.start()
            val endpoint = DanmakuEndpoint("main", server.url("/").toString())
            val candidates = client.match(endpoint, DanmakuMatchQuery("The Movie", 72_000_000_000L))
            assertEquals(1, candidates.size)
            assertEquals("m1", candidates.first().mediaId)
            assertEquals(2, candidates.first().episode)
        }
    }

    @Test
    fun parses_comments_and_converts_time_and_position() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"comments":[{"timeMs":1500,"text":"hi","color":-65536,"position":"top"},{"timeMs":0,"text":"go"}]}""",
                ).build(),
            )
            server.start()
            val endpoint = DanmakuEndpoint("main", server.url("/").toString())
            val comments = client.fetchComments(endpoint, "m1")
            assertEquals(2, comments.size)
            assertEquals(15_000_000L, comments.first().timeTicks) // 1500ms -> ticks
            assertEquals(DanmakuPosition.Top, comments.first().position)
            assertEquals(DanmakuPosition.Scroll, comments[1].position)
        }
    }

    @Test
    fun malformed_body_throws_so_the_controller_can_contain_it() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("not json").build())
            server.start()
            val endpoint = DanmakuEndpoint("main", server.url("/").toString())
            assertThrows(Exception::class.java) {
                runBlocking { client.match(endpoint, DanmakuMatchQuery("x", 1)) }
            }
        }
    }

    @Test
    fun http_error_throws() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).build())
            server.start()
            val endpoint = DanmakuEndpoint("main", server.url("/").toString())
            assertThrows(Exception::class.java) {
                runBlocking { client.fetchComments(endpoint, "m1") }
            }
        }
    }
}
