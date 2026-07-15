package dev.chaichai.mobile.platform.playback

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request

@RunWith(RobolectricTestRunner::class)
class PlaybackSessionServiceTest {
    @Test
    fun `one media session service owns and releases the active media3 player`() {
        val controller = Robolectric.buildService(PlaybackSessionService::class.java).create()
        val service = controller.get()

        assertSame(service, PlaybackServiceOwner.serviceOrNull())

        controller.destroy()
        assertNull(PlaybackServiceOwner.serviceOrNull())
    }

    @Test
    fun `engine state follows service snapshots without wall clock position invention`() {
        val engine = Media3ServicePlaybackEngine(org.robolectric.RuntimeEnvironment.getApplication())
        PlaybackServiceOwner.updateSnapshot(420_000_000, true)

        assertEquals(420_000_000, engine.positionTicks)
        assertEquals(true, engine.isPaused)

        PlaybackServiceOwner.updateSnapshot(510_000_000, false)
        assertEquals(510_000_000, engine.positionTicks)
        assertEquals(false, engine.isPaused)
    }

    @Test
    fun `snapshot read cannot mix fields from different player observations`() {
        PlaybackServiceOwner.updateSnapshot(10, true)
        val observed = PlaybackServiceOwner.snapshot()
        PlaybackServiceOwner.updateSnapshot(20, false)

        assertEquals(PlaybackEngineSnapshot(10, true), observed)
        assertEquals(PlaybackEngineSnapshot(20, false), PlaybackServiceOwner.snapshot())
    }

    @Test
    fun `play pause follows play intent even when media is buffering`() {
        val controller = Robolectric.buildService(PlaybackSessionService::class.java).create()
        val service = controller.get()

        service.playPause()
        assertEquals(false, service.isPaused())
        service.playPause()
        assertEquals(true, service.isPaused())

        controller.destroy()
    }

    @Test
    fun `service exposes conventional media audio volume and system transport behavior`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(PlaybackSessionService::class.java).create()
        val service = controller.get()
        val player = service.playerForSurface()
        try {
            assertEquals(C.USAGE_MEDIA, player.audioAttributes.usage)
            assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, player.audioAttributes.contentType)
            assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, player.deviceInfo.playbackType)

            player.play()
            context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(false, player.playWhenReady)
        } finally {
            controller.destroy()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `service reports app backgrounding without stopping playback`() = runTest {
        val progress = mutableListOf<PlaybackEngineEvent.Progress>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PlaybackServiceOwner.events.collect { if (it is PlaybackEngineEvent.Progress) progress += it }
        }
        val serviceController = Robolectric.buildService(PlaybackSessionService::class.java).create()
        val service = serviceController.get()
        service.acknowledgePlayingReported()
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume()

        activity.pause().stop()
        runCurrent()

        assertEquals(
            listOf(dev.chaichai.mobile.platform.server.PlaybackProgressEvent.TimeUpdate),
            progress.map { it.event },
        )
        assertSame(service, PlaybackServiceOwner.serviceOrNull())
        activity.destroy()
        collector.cancel()
        serviceController.destroy()
    }

    @Test
    fun `playback client rejects redirects before authenticated headers can cross authority`() {
        MockWebServer().use { origin ->
            MockWebServer().use { foreign ->
                origin.start()
                foreign.start()
                origin.enqueue(
                    MockResponse.Builder().code(302)
                        .addHeader("Location", foreign.url("/stolen"))
                        .build(),
                )

                playbackHttpClient().newCall(
                    Request.Builder().url(origin.url("/video"))
                        .header("X-Playback-Key", "secret").build(),
                ).execute().close()

                assertEquals(1, origin.requestCount)
                assertEquals(0, foreign.requestCount)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `service stop and destroy publish exactly one terminal event`() = runTest {
        val stopped = mutableListOf<PlaybackEngineEvent.Stopped>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PlaybackServiceOwner.events.collect { if (it is PlaybackEngineEvent.Stopped) stopped += it }
        }
        val controller = Robolectric.buildService(PlaybackSessionService::class.java).create()
        val service = controller.get()

        service.stopPlayback()
        service.stopPlayback()
        controller.destroy()
        runCurrent()

        assertEquals(1, stopped.size)
        collector.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `seek is suppressed until playing has been acknowledged`() = runTest {
        val progress = mutableListOf<PlaybackEngineEvent.Progress>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PlaybackServiceOwner.events.collect { if (it is PlaybackEngineEvent.Progress) progress += it }
        }
        val controller = Robolectric.buildService(PlaybackSessionService::class.java).create()
        val service = controller.get()

        service.publishControlProgress(dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Seek, 10, false)
        service.acknowledgePlayingReported()
        service.publishControlProgress(dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Seek, 20, false)
        runCurrent()

        assertEquals(listOf(20L), progress.map { it.positionTicks })
        collector.cancel()
        controller.destroy()
    }
}
