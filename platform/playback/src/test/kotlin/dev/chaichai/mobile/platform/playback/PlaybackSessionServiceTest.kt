package dev.chaichai.mobile.platform.playback

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
