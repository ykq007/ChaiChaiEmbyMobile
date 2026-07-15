package dev.chaichai.mobile.platform.server

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class EmbyPlaybackGatewayTest {
    @Test
    fun `negotiation sends scoped resume and truthful capabilities then uses the authoritative direct plan`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{
              "PlaySessionId":"play-session",
              "MediaSources":[{
                "Id":"source-2","Name":"4K","RunTimeTicks":72000000000,
                "SupportsDirectPlay":true,"DirectStreamUrl":"/emby/Videos/movie/stream.mkv?api_key=opaque",
                "RequiredHttpHeaders":{"X-Playback-Key":"required"}
              }]
            }"""))
            val gateway = gateway(server)

            val result = gateway.negotiate(
                ScopedPlaybackRequest("server", "user", "movie", PlaybackStart.Resume(900_000_000)),
                PlaybackCapabilities(
                    maxStreamingBitrate = 18_000_000,
                    maxAudioChannels = 6,
                    directPlayProfiles = listOf(DirectPlayCapability("mkv", "h264", "aac")),
                    transcodeProfiles = listOf(TranscodeCapability("hls", "h264", "aac")),
                    subtitleProfiles = listOf(SubtitleCapability("vtt", "External")),
                ),
            )

            val plan = (result as PlaybackNegotiationResult.Ready).plan
            assertEquals(PlaybackMethod.DirectPlay, plan.method)
            assertEquals("source-2", plan.mediaSourceId)
            assertEquals("play-session", plan.playSessionId)
            assertEquals(72000000000, plan.runtimeTicks)
            assertTrue(plan.url.toString().contains("api_key=opaque"))
            assertEquals("required", plan.headers["X-Playback-Key"])
            val request = server.takeRequest()
            assertEquals("/emby/Items/movie/PlaybackInfo", request.url.encodedPath)
            assertEquals("user", request.url.queryParameter("UserId"))
            assertEquals("token-secret", request.headers["X-Emby-Token"])
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("\"StartTimeTicks\":900000000"))
            assertTrue(body.contains("\"MaxStreamingBitrate\":18000000"))
            assertTrue(body.contains("\"MaxAudioChannels\":6"))
            assertTrue(body.contains("\"Container\":\"mkv\""))
            assertTrue(body.contains("\"Format\":\"vtt\""))
        }
    }

    @Test
    fun `multiple sources choose compatible delivery without inventing a url`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"PlaySessionId":"p","MediaSources":[
              {"Id":"broken","SupportsDirectPlay":true},
              {"Id":"transcode","SupportsTranscoding":true,"TranscodingUrl":"/emby/Videos/m/master.m3u8","RunTimeTicks":99},
              {"Id":"remux","SupportsDirectStream":true,"DirectStreamUrl":"/emby/Videos/m/stream.mp4","RunTimeTicks":99}
            ]}"""))

            val plan = (gateway(server).negotiate(beginning(), capabilities()) as PlaybackNegotiationResult.Ready).plan

            assertEquals("remux", plan.mediaSourceId)
            assertEquals(PlaybackMethod.Remux, plan.method)
            assertEquals("/emby/Videos/m/stream.mp4", plan.url.encodedPath)
        }
    }

    @Test
    fun `transcode and all distinct negotiation failures are preserved`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"PlaySessionId":"p","MediaSources":[{"Id":"t","SupportsTranscoding":true,"TranscodingUrl":"/emby/Videos/m/master.m3u8"}]}"""))
            server.enqueue(json("""{"ErrorCode":"NoCompatibleStream"}"""))
            server.enqueue(json("""{"ErrorCode":"TranscodingNotAllowed"}"""))
            server.enqueue(json("""{"PlaySessionId":"p","MediaSources":[]}"""))
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(MockResponse.Builder().code(503).build())
            val gateway = gateway(server)

            assertEquals(PlaybackMethod.Transcode, (gateway.negotiate(beginning(), capabilities()) as PlaybackNegotiationResult.Ready).plan.method)
            assertEquals(PlaybackFailure.UnsupportedMedia, failed(gateway.negotiate(beginning(), capabilities())))
            assertEquals(PlaybackFailure.TranscodingRefused, failed(gateway.negotiate(beginning(), capabilities())))
            assertEquals(PlaybackFailure.SourceUnavailable, failed(gateway.negotiate(beginning(), capabilities())))
            assertEquals(PlaybackFailure.AuthorizationExpired, failed(gateway.negotiate(beginning(), capabilities())))
            assertEquals(PlaybackFailure.Network, failed(gateway.negotiate(beginning(), capabilities())))
        }
    }

    @Test
    fun `session reports carry one authoritative identity and tick set`() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse.Builder().code(204).build()) }
            val gateway = gateway(server)
            val plan = AuthoritativePlaybackPlan(
                beginning(), "source", "session", PlaybackMethod.Remux,
                server.url("/emby/video"), mapOf("private" to "header"), 7_200_000_000, 1, null,
            )

            assertTrue(gateway.report(PlaybackReport(plan, PlaybackReportKind.Playing, 0, false)))
            assertTrue(gateway.report(PlaybackReport(plan, PlaybackReportKind.Progress, 300_000_000, true, event = PlaybackProgressEvent.Seek)))
            assertTrue(gateway.report(PlaybackReport(plan, PlaybackReportKind.Stopped, 310_000_000, true)))

            val requests = List(3) { server.takeRequest() }
            assertEquals(
                listOf("/emby/Sessions/Playing", "/emby/Sessions/Playing/Progress", "/emby/Sessions/Playing/Stopped"),
                requests.map { it.url.encodedPath },
            )
            assertTrue(requests[1].body!!.utf8().contains("\"PositionTicks\":300000000"))
            assertTrue(requests[1].body!!.utf8().contains("\"EventName\":\"Seek\""))
            assertTrue(requests.all { it.body!!.utf8().contains("\"MediaSourceId\":\"source\"") })
            assertFalse(requests.any { it.body!!.utf8().contains("private") })
        }
    }

    private fun gateway(server: MockWebServer) = EmbyPlaybackGateway(
        FakeVault(
            StoredSession(
                valid(server.url("/emby").toString()), "server", "user", "Ada",
                AccessToken.fromRaw("token-secret"), null, "Test Emby",
            ),
        ),
        deviceId = "device",
    )

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
    private fun beginning() = ScopedPlaybackRequest("server", "user", "movie", PlaybackStart.Beginning)
    private fun capabilities() = PlaybackCapabilities(
        18_000_000, 6, listOf(DirectPlayCapability("mp4", "h264", "aac")),
        listOf(TranscodeCapability("hls", "h264", "aac")),
    )
    private fun failed(result: PlaybackNegotiationResult) = (result as PlaybackNegotiationResult.Failed).reason
    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private class FakeVault(private val session: StoredSession?) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) = Unit
        override fun clear() = Unit
    }
}
