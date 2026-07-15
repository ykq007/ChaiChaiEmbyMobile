package dev.chaichai.mobile.platform.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlaybackCapabilitiesTest {
    @Test
    fun `empty decoder inventory never invents direct or transcode support`() {
        val capabilities = androidPlaybackCapabilities(emptySet())

        assertTrue(capabilities.directPlayProfiles.isEmpty())
        assertTrue(capabilities.transcodeProfiles.isEmpty())
    }
}
