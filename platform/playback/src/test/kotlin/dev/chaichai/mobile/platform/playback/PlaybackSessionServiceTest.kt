package dev.chaichai.mobile.platform.playback

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
}
