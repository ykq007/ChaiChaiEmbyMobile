package dev.chaichai.mobile.platform.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class SubtitleTimingOffsetTest {

    @Test
    fun `factory converts milliseconds to microseconds`() {
        val delegate = RecordingSubtitleDecoder(output("converted", eventTimeUs = 1_000_000L))
        val delegateFactory = object : SubtitleDecoderFactory {
            override fun supportsFormat(format: Format): Boolean = true
            override fun createDecoder(format: Format): SubtitleDecoder = delegate
        }
        val factory = OffsetSubtitleDecoderFactory(delegateFactory)
        factory.setDelayMillis(250L)
        val decoder = factory.createDecoder(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())

        decoder.setPositionUs(1_250_000L)
        val shifted = requireNotNull(decoder.dequeueOutputBuffer())

        assertEquals(1_000_000L, delegate.lastPositionUs)
        assertEquals(1_250_000L, shifted.getEventTime(0))
    }

    @Test
    fun `positive delay shifts cue event and decoder position later`() {
        val delegate = RecordingSubtitleDecoder(output("hello", eventTimeUs = 1_000_000L))
        val decoder = OffsetSubtitleDecoder(delegate) { 250_000L }

        decoder.setPositionUs(1_250_000L)
        val shifted = requireNotNull(decoder.dequeueOutputBuffer())

        assertEquals(1_000_000L, delegate.lastPositionUs)
        assertEquals(1_250_000L, shifted.getEventTime(0))
        assertTrue(shifted.getCues(1_249_999L).isEmpty())
        assertEquals("hello", shifted.getCues(1_250_000L).single().text.toString())
    }

    @Test
    fun `negative delay exposes a future cue earlier`() {
        val delegate = RecordingSubtitleDecoder(output("early", eventTimeUs = 1_000_000L))
        val decoder = OffsetSubtitleDecoder(delegate) { -300_000L }

        decoder.setPositionUs(700_000L)
        val shifted = requireNotNull(decoder.dequeueOutputBuffer())

        assertEquals(1_000_000L, delegate.lastPositionUs)
        assertEquals(700_000L, shifted.getEventTime(0))
        assertEquals("early", shifted.getCues(700_000L).single().text.toString())
    }

    @Test
    fun `newly dequeued buffers use the latest live offset`() {
        val first = output("first", eventTimeUs = 1_000_000L)
        val second = output("second", eventTimeUs = 2_000_000L)
        val delegate = RecordingSubtitleDecoder(first, second)
        var offsetUs = 250_000L
        val decoder = OffsetSubtitleDecoder(delegate) { offsetUs }

        assertEquals(1_250_000L, requireNotNull(decoder.dequeueOutputBuffer()).getEventTime(0))
        offsetUs = -500_000L
        assertEquals(1_500_000L, requireNotNull(decoder.dequeueOutputBuffer()).getEventTime(0))
    }

    @Test
    fun `shifted output preserves end of stream and releases the delegate buffer`() {
        val delegate = output("", eventTimeUs = 0L).apply { addFlag(C.BUFFER_FLAG_END_OF_STREAM) }
        val shifted = OffsetSubtitleOutputBuffer(delegate, 250_000L)

        assertTrue(shifted.isEndOfStream)
        shifted.release()
        assertTrue(delegate.released)
    }

    @Test
    fun `capability requires a selected supported text track`() {
        val supported = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()
        val unsupported = Format.Builder().setSampleMimeType("application/x-unknown-subtitle").build()
        val factory = object : SubtitleDecoderFactory {
            override fun supportsFormat(format: Format): Boolean = format.sampleMimeType == MimeTypes.TEXT_VTT
            override fun createDecoder(format: Format): SubtitleDecoder = error("not needed")
        }

        assertTrue(tracks(supported, selected = true).hasSelectedDelayableSubtitle(factory))
        assertFalse(tracks(supported, selected = false).hasSelectedDelayableSubtitle(factory))
        assertFalse(tracks(unsupported, selected = true).hasSelectedDelayableSubtitle(factory))
        assertFalse(Tracks.EMPTY.hasSelectedDelayableSubtitle(factory))
    }

    private fun tracks(format: Format, selected: Boolean): Tracks {
        val group = TrackGroup("subtitles", format)
        return Tracks(
            listOf(
                Tracks.Group(
                    group,
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(selected),
                ),
            ),
        )
    }

    private fun output(text: String, eventTimeUs: Long): TestSubtitleOutputBuffer =
        TestSubtitleOutputBuffer(SingleCueSubtitle(text, eventTimeUs))
}

@UnstableApi
private class RecordingSubtitleDecoder(vararg outputs: SubtitleOutputBuffer) : SubtitleDecoder {
    private val queuedOutputs = ArrayDeque(outputs.toList())
    var lastPositionUs: Long? = null
    var lastOutputStartTimeUs: Long? = null
    var released = false

    override fun getName(): String = "recording"
    override fun setPositionUs(positionUs: Long) { lastPositionUs = positionUs }
    override fun setOutputStartTimeUs(outputStartTimeUs: Long) { lastOutputStartTimeUs = outputStartTimeUs }
    override fun dequeueInputBuffer(): SubtitleInputBuffer? = null
    override fun queueInputBuffer(inputBuffer: SubtitleInputBuffer) = Unit
    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? = queuedOutputs.pollFirst()
    override fun flush() = Unit
    override fun release() { released = true }
}

@UnstableApi
private class TestSubtitleOutputBuffer(subtitle: Subtitle) : SubtitleOutputBuffer() {
    var released = false
    init { setContent(0L, subtitle, 0L) }
    override fun release() { released = true }
}

@UnstableApi
private class SingleCueSubtitle(text: String, private val eventTimeUs: Long) : Subtitle {
    private val cues = listOf(Cue.Builder().setText(text).build())
    override fun getNextEventTimeIndex(timeUs: Long): Int = if (timeUs < eventTimeUs) 0 else C.INDEX_UNSET
    override fun getEventTimeCount(): Int = 1
    override fun getEventTime(index: Int): Long = eventTimeUs
    override fun getCues(timeUs: Long): List<Cue> = if (timeUs >= eventTimeUs) cues else emptyList()
}
