package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * A broken proxy on one endpoint stays isolated from other endpoints (AC3): each endpoint routes
 * through its own client, so endpoint A failing through a dead proxy never prevents endpoint B (direct)
 * from loading, and the two run concurrently without cross-contamination.
 */
class DanmakuEndpointIsolationTest {

    /** A port that is bound then immediately released, so connecting to it is refused promptly. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun a_dead_proxy_on_one_endpoint_does_not_stop_another_endpoint() = runBlocking {
        MockWebServer().use { good ->
            good.enqueue(
                MockResponse.Builder().code(200)
                    .body("""{"candidates":[{"mediaId":"m1","title":"Match"}]}""").build(),
            )
            good.start()

            val client = OkHttpDanmakuEndpointClient()
            val brokenEndpoint = DanmakuEndpoint(
                name = "Broken",
                baseUrl = "https://danmaku.invalid",
                id = "broken",
                routing = DanmakuEndpointRouting.Proxy(
                    ServerProxyConfig(ProxyKind.Http, "127.0.0.1", deadPort(), enabled = true),
                ),
            )
            val directEndpoint = DanmakuEndpoint("Direct", good.url("/").toString(), id = "direct")
            val query = DanmakuMatchQuery("Match", 1_000_000)

            val brokenResult = async(Dispatchers.IO) { runCatching { client.match(brokenEndpoint, query) } }
            val directResult = async(Dispatchers.IO) { runCatching { client.match(directEndpoint, query) } }

            assertTrue("broken proxy endpoint must fail", brokenResult.await().isFailure)
            val candidates = directResult.await().getOrNull()
            assertNotNull("direct endpoint must still load", candidates)
            assertEquals("m1", candidates!!.first().mediaId)
        }
    }
}
