package dev.chaichai.mobile.platform.server

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MarkerKind
import dev.chaichai.mobile.core.contracts.TrackDelivery
import dev.chaichai.mobile.core.contracts.TrackQualifier
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection

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
                ScopedPlaybackRequest(HomeScope("server", "user"), MediaIdentity("server", "movie"), PlaybackStart.Resume(900_000_000)),
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
    fun `authoritative tracks expose embedded external burn in and missing stream metadata`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{
              "PlaySessionId":"p","MediaSources":[{
                "Id":"source","SupportsDirectPlay":true,"DirectStreamUrl":"/emby/video",
                "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":4,
                "MediaStreams":[
                  {"Index":1,"Type":"Audio","Language":"eng","Codec":"aac","IsDefault":true,"Title":"Stereo"},
                  {"Index":2,"Type":"Audio","Language":"jpn","Codec":"aac","IsCommentary":true},
                  {"Index":4,"Type":"Subtitle","Language":"eng","Codec":"ass","DeliveryMethod":"Embed","IsHearingImpaired":true},
                  {"Index":5,"Type":"Subtitle","Language":"spa","Codec":"srt","DeliveryMethod":"External"},
                  {"Index":6,"Type":"Subtitle","Codec":"pgs","DeliveryMethod":"Encode"},
                  {"Type":"Subtitle","Language":"fra","Codec":"srt"}
                ]
              }]
            }"""))

            val plan = (gateway(server).negotiate(beginning(), capabilities()) as PlaybackNegotiationResult.Ready).plan

            assertEquals(listOf(1, 2), plan.audioTracks.map { it.index })
            assertEquals(listOf(4, 5, 6), plan.subtitleTracks.map { it.index })
            assertEquals(listOf(TrackDelivery.Embedded, TrackDelivery.External, TrackDelivery.BurnIn), plan.subtitleTracks.map { it.delivery })
            assertTrue(plan.audioTracks.first().isCurrent)
            assertTrue(plan.audioTracks.first().isDefault)
            assertEquals(listOf(TrackQualifier.Commentary), plan.audioTracks[1].qualifiers)
            assertEquals(listOf(TrackQualifier.HearingImpaired), plan.subtitleTracks.first().qualifiers)
        }
    }

    @Test
    fun `track renegotiation preserves source session position and supports subtitle off`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{
              "PlaySessionId":"continued-session","MediaSources":[{
                "Id":"source","SupportsTranscoding":true,"TranscodingUrl":"/emby/master.m3u8",
                "DefaultAudioStreamIndex":2,"MediaStreams":[
                  {"Index":2,"Type":"Audio","Language":"jpn","Codec":"aac"},
                  {"Index":4,"Type":"Subtitle","Language":"eng","Codec":"ass","DeliveryMethod":"Encode"}
                ]
              }]
            }"""))
            val request = beginning().copy(
                start = PlaybackStart.Resume(1_234_000_000),
                trackSelection = PlaybackTrackSelection.subtitleOff(audioStreamIndex = null),
                sessionReference = PlaybackSessionReference("source", "original-session"),
            )

            val plan = (gateway(server).negotiate(request, capabilities()) as PlaybackNegotiationResult.Ready).plan

            assertEquals(null, plan.subtitleStreamIndex)
            assertEquals("continued-session", plan.playSessionId)
            val body = server.takeRequest().body!!.utf8()
            assertTrue(body.contains("\"StartTimeTicks\":1234000000"))
            assertTrue(body.contains("\"MediaSourceId\":\"source\""))
            assertTrue(body.contains("\"PlaySessionId\":\"original-session\""))
            assertTrue(body.contains("\"SubtitleStreamIndex\":-1"))
        }
    }

    @Test
    fun `renegotiation exposes server fallback stream indices as authoritative`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{
              "PlaySessionId":"continued","MediaSources":[{
                "Id":"source","SupportsDirectPlay":true,"DirectStreamUrl":"/emby/video",
                "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":5,
                "MediaStreams":[
                  {"Index":1,"Type":"Audio"},{"Index":2,"Type":"Audio"},
                  {"Index":4,"Type":"Subtitle"},{"Index":5,"Type":"Subtitle"}
                ]
              }]
            }"""))
            val request = beginning().copy(
                trackSelection = PlaybackTrackSelection(audioStreamIndex = 2, subtitleStreamIndex = 4),
                sessionReference = PlaybackSessionReference("source", "original"),
            )

            val plan = (gateway(server).negotiate(request, capabilities()) as PlaybackNegotiationResult.Ready).plan

            assertEquals(1, plan.audioStreamIndex)
            assertEquals(5, plan.subtitleStreamIndex)
            assertTrue(plan.audioTracks.single { it.index == 1 }.isCurrent)
            assertTrue(plan.subtitleTracks.single { it.index == 5 }.isCurrent)
        }
    }

    @Test
    fun `foreign playback authority is rejected before required headers can leak`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"PlaySessionId":"p","MediaSources":[{
              "Id":"foreign","SupportsDirectPlay":true,
              "DirectStreamUrl":"https://attacker.example/video",
              "RequiredHttpHeaders":{"X-Emby-Token":"must-not-leak"}
            }]}"""))

            val result = gateway(server).negotiate(beginning(), capabilities())

            assertEquals(PlaybackFailure.SourceUnavailable, failed(result))
            assertEquals(1, server.requestCount)
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
    fun `real intro and outro segments populate a validated authoritative plan`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(playbackInfo(runtimeTicks = 1_000))
            server.enqueue(json("""{"Items":[
              {"Type":"Intro","StartTicks":0,"EndTicks":100},
              {"Type":"Outro","StartTicks":900,"EndTicks":1000}
            ]}"""))

            val plan = (networkGateway(server).negotiate(beginning(), capabilities()) as PlaybackNegotiationResult.Ready).plan

            assertEquals(listOf(MarkerKind.Intro, MarkerKind.Outro), plan.markers.map { it.kind })
            assertEquals(listOf(100L, 1_000L), plan.markers.map { it.endTicks })
            val playbackRequest = server.takeRequest()
            val markerRequest = server.takeRequest()
            assertEquals("/emby/Items/movie/PlaybackInfo", playbackRequest.url.encodedPath)
            assertEquals("/emby/Items/movie/MediaSegments", markerRequest.url.encodedPath)
            assertEquals("Intro,Outro", markerRequest.url.queryParameter("IncludeSegmentTypes"))
        }
    }

    @Test
    fun `absent and malformed segments are silent and invalid runtime windows are rejected`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(playbackInfo(runtimeTicks = 1_000))
            server.enqueue(json("""{"Items":[
              {"Type":"Chapter","StartTicks":0,"EndTicks":50},
              {"Type":"Intro","StartTicks":200,"EndTicks":100},
              {"Type":"Outro","StartTicks":900,"EndTicks":1200},
              {"Type":"Intro","StartTicks":null,"EndTicks":100}
            ]}"""))

            val plan = (networkGateway(server).negotiate(beginning(), capabilities()) as PlaybackNegotiationResult.Ready).plan

            assertTrue(plan.markers.isEmpty())
        }
    }

    @Test
    fun `marker fetch retries once caches success and never breaks playback negotiation`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(playbackInfo(runtimeTicks = 1_000))
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(json("""{"Items":[{"Type":"Intro","StartTicks":0,"EndTicks":100}]}"""))
            server.enqueue(playbackInfo(runtimeTicks = 1_000))
            val gateway = networkGateway(server)

            val first = (gateway.negotiate(beginning(), capabilities()) as PlaybackNegotiationResult.Ready).plan
            val second = (gateway.negotiate(beginning(), capabilities()) as PlaybackNegotiationResult.Ready).plan

            assertEquals(1, first.markers.size)
            assertEquals(first.markers, second.markers)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `marker fetch exhaustion returns a playable plan without markers`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(playbackInfo(runtimeTicks = 1_000))
            server.enqueue(MockResponse.Builder().code(500).build())
            server.enqueue(MockResponse.Builder().code(500).build())

            val result = networkGateway(server).negotiate(beginning(), capabilities())

            assertTrue(result is PlaybackNegotiationResult.Ready)
            assertTrue((result as PlaybackNegotiationResult.Ready).plan.markers.isEmpty())
        }
    }

    @Test
    fun `session reports carry one authoritative identity and tick set`() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse.Builder().code(204).build()) }
            val gateway = gateway(server)
            val plan = AuthoritativePlaybackPlan(
                beginning(), PlaybackSessionReference("source", "session"), PlaybackMethod.Remux,
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
        vault(server),
        deviceId = "device",
        markerLoader = MediaSegmentLoader { _, _ -> emptyList() },
    )

    private fun networkGateway(server: MockWebServer) = EmbyPlaybackGateway(
        vault(server),
        deviceId = "device",
    )

    private fun vault(server: MockWebServer) = FakeVault(
        StoredSession(
            valid(server.url("/emby").toString()), "server", "user", "Ada",
            AccessToken.fromRaw("token-secret"), null, "Test Emby",
        ),
    )

    private fun playbackInfo(runtimeTicks: Long) = json("""{
      "PlaySessionId":"p","MediaSources":[{
        "Id":"source","RunTimeTicks":$runtimeTicks,"SupportsDirectPlay":true,
        "DirectStreamUrl":"/emby/video"
      }]
    }""")

    private fun valid(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address
    private fun beginning() = ScopedPlaybackRequest(
        HomeScope("server", "user"), MediaIdentity("server", "movie"), PlaybackStart.Beginning,
    )
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
