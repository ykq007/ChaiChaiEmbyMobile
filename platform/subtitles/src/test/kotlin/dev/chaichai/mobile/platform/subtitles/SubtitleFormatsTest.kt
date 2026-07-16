package dev.chaichai.mobile.platform.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFormatsTest {

    @Test
    fun supported_formats_map_to_player_mime_types() {
        assertEquals("application/x-subrip", SubtitleFormats.mimeTypeOf("srt"))
        assertEquals("text/vtt", SubtitleFormats.mimeTypeOf("VTT"))
        assertEquals("text/x-ssa", SubtitleFormats.mimeTypeOf(".ass"))
        assertTrue(SubtitleFormats.isSupported("ssa"))
    }

    @Test
    fun unsupported_formats_are_rejected_as_incompatible() {
        assertFalse(SubtitleFormats.isSupported("pgs"))
        assertNull(SubtitleFormats.mimeTypeOf("pgs"))
    }

    @Test
    fun file_extension_is_normalized() {
        assertEquals("srt", SubtitleFormats.fileExtensionOf("subrip"))
        assertEquals("vtt", SubtitleFormats.fileExtensionOf("WebVTT"))
        assertEquals("ass", SubtitleFormats.fileExtensionOf("ass"))
    }
}
