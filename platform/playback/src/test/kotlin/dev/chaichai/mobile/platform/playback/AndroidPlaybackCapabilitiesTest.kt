package dev.chaichai.mobile.platform.playback

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidPlaybackCapabilitiesTest {
    @Test
    fun `empty decoder inventory never invents direct or transcode support`() {
        val capabilities = androidPlaybackCapabilities(emptySet())

        assertTrue(capabilities.directPlayProfiles.isEmpty())
        assertTrue(capabilities.transcodeProfiles.isEmpty())
    }

    @Test
    fun `container codec combinations stay conservative and truthful`() {
        val capabilities = androidPlaybackCapabilities(setOf("video/avc", "video/x-vnd.on2.vp9", "audio/mp4a-latm"))

        assertTrue(capabilities.directPlayProfiles.any { it.container == "mp4" && it.videoCodec == "h264" })
        assertFalse(capabilities.directPlayProfiles.any { it.container == "webm" && it.videoCodec == "h264" })
        assertFalse(capabilities.directPlayProfiles.any { it.container == "webm" && it.audioCodec == "aac" })
    }
}
