package dev.chaichai.mobile.platform.playback

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

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
}
