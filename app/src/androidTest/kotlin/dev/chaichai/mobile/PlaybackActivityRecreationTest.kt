package dev.chaichai.mobile

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.ExternalSubtitleActivation
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.core.contracts.SubtitleAppearance
import dev.chaichai.mobile.core.contracts.SubtitleColorPreset
import dev.chaichai.mobile.core.contracts.SubtitlePosition
import dev.chaichai.mobile.core.contracts.VideoScaleMode
import dev.chaichai.mobile.platform.playback.PlaybackCoordinatorImpl
import dev.chaichai.mobile.platform.playback.PlaybackEngine
import dev.chaichai.mobile.platform.playback.PlaybackEngineEvent
import dev.chaichai.mobile.platform.playback.PlaybackEngineSnapshot
import dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan
import dev.chaichai.mobile.platform.server.DirectPlayCapability
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.PlaybackGateway
import dev.chaichai.mobile.platform.server.PlaybackMethod
import dev.chaichai.mobile.platform.server.PlaybackNegotiationResult
import dev.chaichai.mobile.platform.server.PlaybackReport
import dev.chaichai.mobile.platform.server.PlaybackSessionReference
import dev.chaichai.mobile.platform.server.ScopedPlaybackRequest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackActivityRecreationTest {
    private lateinit var scope: CoroutineScope
    private lateinit var gateway: RecordingGateway
    private lateinit var engine: RecordingEngine
    private lateinit var coordinator: PlaybackCoordinatorImpl

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        gateway = RecordingGateway()
        engine = RecordingEngine()
        coordinator = PlaybackCoordinatorImpl(
            scope,
            gateway,
            engine,
            PlaybackCapabilities(18_000_000, 6, listOf(DirectPlayCapability("mp4", "h264", "aac")), emptyList()),
            scheduleTimelineUpdates = false,
        )
        PlaybackRecreationActivity.boundaries = AppBoundaries(
            gateway = object : EmbyGateway {
                override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
            },
            playback = coordinator,
            clock = AppClock { Instant.EPOCH },
            connectivity = object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
        )
    }

    @After
    fun tearDown() {
        coordinator.close()
        scope.cancel()
    }

    @Test
    fun real_activity_recreation_keeps_the_authoritative_session_without_prepare_restart() {
        PlaybackRecreationActivity.playbackEndedCount.set(0)
        val originalAutoRotation = shellOutput("settings get system accelerometer_rotation").trim()
        val originalUserRotation = shellOutput("settings get system user_rotation").trim()
        try {
            ActivityScenario.launch(PlaybackRecreationActivity::class.java).use { scenario ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    coordinator.submit(
                        MediaPlaybackRequest.Resume(
                            MediaIdentity("server", "movie"),
                            600_000_000,
                            HomeScope("server", "user"),
                            "Arrival",
                        ),
                    )
                }
                waitForActive()
                val rotationCreationCount = PlaybackRecreationActivity.creationCount.get()
                shell("settings put system accelerometer_rotation 0")
                shell("settings put system user_rotation ${if (originalUserRotation == "1") 0 else 1}")
                waitUntil { PlaybackRecreationActivity.creationCount.get() > rotationCreationCount }
                assertEquals(1, gateway.negotiationCount)
                assertEquals(1, engine.prepareCount)

                val creationCount = PlaybackRecreationActivity.creationCount.get()
                scenario.onActivity { it.recreate() }
                waitUntil { PlaybackRecreationActivity.creationCount.get() > creationCount }

                val active = coordinator.state.value as PlaybackState.Active
                assertEquals(MediaIdentity("server", "movie"), active.identity)
                assertEquals(600_000_000, active.positionTicks)
                assertFalse(active.isPaused)
                assertEquals(1, gateway.negotiationCount)
                assertEquals(1, engine.prepareCount)
                assertEquals(0, PlaybackRecreationActivity.playbackEndedCount.get())
                assertEquals(
                    PlaybackSessionReference("source", "session"),
                    gateway.reports.single().plan.sessionReference,
                )
                assertEquals(PlaybackMethod.DirectPlay, gateway.reports.single().plan.method)
            }
        } finally {
            shell("settings put system accelerometer_rotation $originalAutoRotation")
            shell("settings put system user_rotation $originalUserRotation")
        }
    }

    /**
     * Subtitle Expansion (#33 AC3): both the subtitle appearance AND the selected provider subtitle
     * (#32) survive an Activity recreation (rotation/fold/backgrounding proxy, same harness as the
     * authoritative-session test above) without a re-negotiation or extra prepare — the coordinator
     * itself is retained outside the recreated Activity, exactly like the authoritative playback plan.
     */
    @Test
    fun subtitle_appearance_and_provider_subtitle_survive_activity_recreation() {
        PlaybackRecreationActivity.playbackEndedCount.set(0)
        ActivityScenario.launch(PlaybackRecreationActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                coordinator.submit(
                    MediaPlaybackRequest.Resume(
                        MediaIdentity("server", "movie"),
                        600_000_000,
                        HomeScope("server", "user"),
                        "Arrival",
                    ),
                )
            }
            waitForActive()

            val appearance = SubtitleAppearance(
                textScale = 1.5f,
                position = SubtitlePosition.Upper,
                colorPreset = SubtitleColorPreset.YellowOnBlack,
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                coordinator.setSubtitleAppearance(appearance)
                coordinator.addExternalSubtitle(
                    ExternalSubtitleActivation(
                        track = PlaybackTrack(index = 4, type = PlaybackTrackType.Subtitle, language = "eng"),
                        localRef = "file:///cache/subtitles/arrival-en.srt",
                        mimeType = "application/x-subrip",
                    ),
                )
            }
            waitUntil {
                (coordinator.state.value as? PlaybackState.Active)?.subtitleTracks?.any {
                    it.delivery == dev.chaichai.mobile.core.contracts.TrackDelivery.External && it.isCurrent
                } == true
            }

            val creationCount = PlaybackRecreationActivity.creationCount.get()
            scenario.onActivity { it.recreate() }
            waitUntil { PlaybackRecreationActivity.creationCount.get() > creationCount }

            val active = coordinator.state.value as PlaybackState.Active
            assertEquals(appearance, active.subtitleAppearance)
            assertTrue(active.subtitleAppearanceSupported)
            assertTrue(
                active.subtitleTracks.any {
                    it.delivery == dev.chaichai.mobile.core.contracts.TrackDelivery.External && it.isCurrent
                },
            )
            assertEquals(1, engine.prepareCount)
        }
    }

    /**
     * Playback Polish (#35 AC1/AC5): a scale mode change applies live and survives an Activity
     * recreation with no re-negotiation and no extra `prepare` — the coordinator's in-memory state
     * (retained outside the recreated Activity, same harness as the tests above) is what carries it
     * through, exactly like subtitle appearance.
     */
    @Test
    fun scale_mode_survives_activity_recreation_without_prepare_restart() {
        PlaybackRecreationActivity.playbackEndedCount.set(0)
        ActivityScenario.launch(PlaybackRecreationActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                coordinator.submit(
                    MediaPlaybackRequest.Resume(
                        MediaIdentity("server", "movie"),
                        600_000_000,
                        HomeScope("server", "user"),
                        "Arrival",
                    ),
                )
            }
            waitForActive()

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                coordinator.setVideoScaleMode(VideoScaleMode.Zoom)
            }
            waitUntil { (coordinator.state.value as? PlaybackState.Active)?.videoScaleMode == VideoScaleMode.Zoom }

            val creationCount = PlaybackRecreationActivity.creationCount.get()
            scenario.onActivity { it.recreate() }
            waitUntil { PlaybackRecreationActivity.creationCount.get() > creationCount }

            val active = coordinator.state.value as PlaybackState.Active
            assertEquals(VideoScaleMode.Zoom, active.videoScaleMode)
            assertTrue(active.videoScaleModeSupported)
            assertEquals(listOf(VideoScaleMode.Fit, VideoScaleMode.Zoom), active.supportedScaleModes)
            assertEquals(1, engine.prepareCount)
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    private fun shellOutput(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun waitForActive() {
        val deadline = System.currentTimeMillis() + 5_000
        while (coordinator.state.value !is PlaybackState.Active && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        check(coordinator.state.value is PlaybackState.Active) { "Playback did not become active" }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        check(condition()) { "Activity did not recreate" }
    }

    private class RecordingGateway : PlaybackGateway {
        var negotiationCount = 0
        val reports = mutableListOf<PlaybackReport>()
        override suspend fun negotiate(
            request: ScopedPlaybackRequest,
            capabilities: PlaybackCapabilities,
        ): PlaybackNegotiationResult {
            negotiationCount++
            return PlaybackNegotiationResult.Ready(
                AuthoritativePlaybackPlan(
                    request,
                    PlaybackSessionReference("source", "session"),
                    PlaybackMethod.DirectPlay,
                    "https://example.test/video.mp4".toHttpUrl(),
                    emptyMap(),
                    7_200_000_000,
                    null,
                    null,
                ),
            )
        }
        override suspend fun report(event: PlaybackReport): Boolean = true.also { reports += event }
    }

    private class RecordingEngine : PlaybackEngine {
        private val mutableEvents = MutableSharedFlow<PlaybackEngineEvent>(replay = 1)
        override val events = mutableEvents
        override var positionTicks = 0L
        override var isPaused = true
        override val snapshot: PlaybackEngineSnapshot get() = PlaybackEngineSnapshot(positionTicks, isPaused)
        var prepareCount = 0
        override val subtitleAppearanceSupported: Boolean get() = true
        override val videoScaleModeSupported: Boolean get() = true
        override val supportedScaleModes: List<VideoScaleMode> get() = listOf(VideoScaleMode.Fit, VideoScaleMode.Zoom)
        val appliedAppearances = mutableListOf<SubtitleAppearance>()
        val appliedExternalSubtitles = mutableListOf<String>()
        val appliedScaleModes = mutableListOf<VideoScaleMode>()
        override suspend fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long, startPaused: Boolean) {
            prepareCount++
            positionTicks = startPositionTicks
            isPaused = startPaused
            mutableEvents.emit(PlaybackEngineEvent.Ready)
        }
        override suspend fun acknowledgePlayingReported() = Unit
        override suspend fun playPause() { isPaused = !isPaused }
        override suspend fun seekTo(positionTicks: Long) { this.positionTicks = positionTicks }
        override suspend fun setSubtitleAppearance(appearance: SubtitleAppearance) { appliedAppearances += appearance }
        override suspend fun setVideoScaleMode(mode: VideoScaleMode) { appliedScaleModes += mode }
        override suspend fun applyExternalSubtitle(localRef: String, mimeType: String, language: String?) {
            appliedExternalSubtitles += localRef
        }
        override suspend fun stop() { mutableEvents.emit(PlaybackEngineEvent.Stopped(positionTicks, isPaused)) }
    }
}
