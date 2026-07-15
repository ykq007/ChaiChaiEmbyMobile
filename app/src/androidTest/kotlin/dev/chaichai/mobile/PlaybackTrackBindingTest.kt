package dev.chaichai.mobile

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.platform.playback.PlaybackCoordinatorImpl
import dev.chaichai.mobile.platform.playback.PlaybackEngine
import dev.chaichai.mobile.platform.playback.PlaybackEngineSnapshot
import dev.chaichai.mobile.platform.server.AuthenticationResult
import dev.chaichai.mobile.platform.server.DirectPlayCapability
import dev.chaichai.mobile.platform.server.EmbyAuthenticator
import dev.chaichai.mobile.platform.server.EmbyPlaybackGateway
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.ServerAddress
import dev.chaichai.mobile.platform.server.SessionVault
import dev.chaichai.mobile.platform.server.StoredSession
import dev.chaichai.mobile.platform.server.AddressValidation
import dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackTrackBindingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun real_gateway_and_coordinator_apply_track_change_through_the_app_without_losing_viewing_state() {
        bindingFlow(FailureMode.None)
    }

    @Test
    fun media_preparation_failure_restores_the_working_plan_through_the_app() {
        bindingFlow(FailureMode.Prepare)
    }

    @Test
    fun gateway_failure_keeps_the_working_plan_through_the_app() {
        bindingFlow(FailureMode.Gateway)
    }

    private fun bindingFlow(failureMode: FailureMode) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = DeterministicEngine()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json(authenticatedSession()))
            server.enqueue(json(playbackInfo(audioIndex = 1)))
            server.enqueue(noContent())
            server.enqueue(noContent())
            server.enqueue(
                if (failureMode == FailureMode.Gateway) {
                    MockResponse.Builder().code(503).body("replacement unavailable").build()
                } else {
                    json(playbackInfo(audioIndex = 2))
                },
            )
            if (failureMode == FailureMode.None) server.enqueue(noContent())
            val address = (ServerAddress.parse(server.url("/emby").toString()) as AddressValidation.Valid).address
            val authenticated = runBlocking {
                EmbyAuthenticator().authenticate(address, "server", "Ada", "secret", "binding-device")
            } as AuthenticationResult.Success
            val vault = FixedVault(
                StoredSession(
                    address, "server", "user", "Ada", authenticated.session.accessToken, null, "Cinema",
                ),
            )
            val coordinator = PlaybackCoordinatorImpl(
                scope,
                EmbyPlaybackGateway(vault, deviceId = "binding-device"),
                engine,
                PlaybackCapabilities(
                    18_000_000,
                    6,
                    listOf(DirectPlayCapability("mp4", "h264", "aac")),
                    emptyList(),
                ),
                scheduleTimelineUpdates = false,
            )
            compose.setContent {
                ChaiChaiTheme(reducedMotion = true) {
                    MobileApp(boundaries(coordinator), separatingHinge = null)
                }
            }
            compose.onNodeWithText("Search").performClick()

            coordinator.submit(
                MediaPlaybackRequest.Resume(
                    MediaIdentity("server", "movie"),
                    600_000_000,
                    HomeScope("server", "user"),
                    "Arrival",
                ),
            )
            compose.waitUntil(5_000) { coordinator.state.value is PlaybackState.Active }
            coordinator.playPause()
            compose.waitUntil(5_000) { (coordinator.state.value as? PlaybackState.Active)?.isPaused == true }
            if (failureMode == FailureMode.Prepare) engine.failNextPrepare = true

            compose.onNodeWithContentDescription("Tracks").performClick()
            compose.onNodeWithText("Japanese · AAC").performClick()

            if (failureMode != FailureMode.None) {
                compose.waitUntil(5_000) {
                    (coordinator.state.value as? PlaybackState.Active)?.trackChangeError != null
                }
                val active = coordinator.state.value as PlaybackState.Active
                assertTrue(active.audioTracks.single { it.index == 1 }.isCurrent)
                assertEquals(
                    if (failureMode == FailureMode.Prepare) listOf(1, 2, 1) else listOf(1),
                    engine.preparedAudioIndices,
                )
            } else {
                compose.waitUntil(5_000) {
                    (coordinator.state.value as? PlaybackState.Active)
                        ?.audioTracks?.any { it.index == 2 && it.isCurrent } == true
                }
                assertEquals(listOf(1, 2), engine.preparedAudioIndices)
            }
            val active = coordinator.state.value as PlaybackState.Active
            assertEquals(MediaIdentity("server", "movie"), active.identity)
            assertEquals(600_000_000, active.positionTicks)
            assertTrue(active.isPaused)
            coordinator.close()
        }
        scope.cancel()
    }

    private enum class FailureMode { None, Gateway, Prepare }

    private fun boundaries(coordinator: PlaybackCoordinatorImpl) = AppBoundaries(
        gateway = object : EmbyGateway {
            override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
        },
        playback = coordinator,
        clock = AppClock { Instant.EPOCH },
        connectivity = object : ConnectivityMonitor {
            override val isOnline = MutableStateFlow(true)
        },
    )

    private fun authenticatedSession() =
        """{"AccessToken":"token","User":{"Id":"user","Name":"Ada","Policy":{"EnableMediaPlayback":true}}}"""

    private fun playbackInfo(audioIndex: Int) = """{
      "PlaySessionId":"session-$audioIndex","MediaSources":[{
        "Id":"source","RunTimeTicks":72000000000,"SupportsDirectPlay":true,
        "DirectStreamUrl":"/emby/video.mp4","DefaultAudioStreamIndex":$audioIndex,
        "DefaultSubtitleStreamIndex":4,"MediaStreams":[
          {"Index":1,"Type":"Audio","Language":"eng","Codec":"aac","IsDefault":true},
          {"Index":2,"Type":"Audio","Language":"jpn","Codec":"aac"},
          {"Index":4,"Type":"Subtitle","Language":"eng","Codec":"srt","DeliveryMethod":"External"}
        ]
      }]
    }"""

    private fun json(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private class FixedVault(private val session: StoredSession) : SessionVault {
        override fun restore() = session
        override fun save(session: StoredSession) = Unit
        override fun clear() = Unit
    }

    private class DeterministicEngine : PlaybackEngine {
        override var positionTicks = 0L
        override var isPaused = false
        override val snapshot: PlaybackEngineSnapshot
            get() = PlaybackEngineSnapshot(positionTicks, isPaused)
        var failNextPrepare = false
        val preparedAudioIndices = mutableListOf<Int?>()

        override suspend fun prepare(
            plan: AuthoritativePlaybackPlan,
            startPositionTicks: Long,
            startPaused: Boolean,
        ) {
            preparedAudioIndices += plan.audioStreamIndex
            if (failNextPrepare) {
                failNextPrepare = false
                throw IllegalStateException("decoder rejected replacement")
            }
            positionTicks = startPositionTicks
            isPaused = startPaused
        }

        override suspend fun acknowledgePlayingReported() = Unit
        override suspend fun playPause() { isPaused = !isPaused }
        override suspend fun seekTo(positionTicks: Long) { this.positionTicks = positionTicks }
        override suspend fun stop() = Unit
    }
}
