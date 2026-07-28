package dev.chaichai.mobile.platform.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the live subtitle timing offset used by every text decoder created for the active player.
 *
 * Media3 1.8 has no public player-level subtitle delay command. Its legacy text-renderer seam does,
 * however, expose decoded subtitle timestamps. We keep the default decoders and shift only their
 * timeline contract, so all formats Media3 already supports remain Media3-owned.
 */
@UnstableApi
internal class OffsetSubtitleDecoderFactory(
    private val delegate: SubtitleDecoderFactory = SubtitleDecoderFactory.DEFAULT,
) : SubtitleDecoderFactory {
    private val offsetUs = AtomicLong(0L)

    fun setDelayMillis(delayMillis: Long) {
        offsetUs.set(delayMillis.saturatingMillisToUs())
    }

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        OffsetSubtitleDecoder(delegate.createDecoder(format), offsetUs::get)
}

/**
 * Shifts the decoder's view of playback time backwards by the requested offset and shifts decoded
 * cue timestamps forwards by the same amount. This symmetric transform supports both positive
 * (later) and negative (earlier) subtitle delay without changing media position or playback speed.
 */
@UnstableApi
internal class OffsetSubtitleDecoder(
    private val delegate: SubtitleDecoder,
    private val offsetUs: () -> Long,
) : SubtitleDecoder {
    override fun getName(): String = "${delegate.name}+timing-offset"

    override fun setPositionUs(positionUs: Long) {
        delegate.setPositionUs(positionUs.unshiftedBy(offsetUs()))
    }

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        delegate.setOutputStartTimeUs(outputStartTimeUs.unshiftedBy(offsetUs()))
    }

    override fun dequeueInputBuffer(): SubtitleInputBuffer? = delegate.dequeueInputBuffer()

    override fun queueInputBuffer(inputBuffer: SubtitleInputBuffer) {
        delegate.queueInputBuffer(inputBuffer)
    }

    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? {
        val output = delegate.dequeueOutputBuffer() ?: return null
        return OffsetSubtitleOutputBuffer(output, offsetUs())
    }

    override fun flush() = delegate.flush()

    override fun release() = delegate.release()
}

/** A timestamp-shifted view of one Media3-owned output buffer. Releasing it releases the delegate. */
@UnstableApi
internal class OffsetSubtitleOutputBuffer(
    private val delegate: SubtitleOutputBuffer,
    private val offsetUs: Long,
) : SubtitleOutputBuffer() {
    init {
        timeUs = delegate.timeUs.shiftedBy(offsetUs)
        skippedOutputBufferCount = delegate.skippedOutputBufferCount
        shouldBeSkipped = delegate.shouldBeSkipped
        copyFlagsFrom(delegate)
    }

    override fun getEventTimeCount(): Int = delegate.eventTimeCount

    override fun getEventTime(index: Int): Long = delegate.getEventTime(index).shiftedBy(offsetUs)

    override fun getNextEventTimeIndex(timeUs: Long): Int =
        delegate.getNextEventTimeIndex(timeUs.unshiftedBy(offsetUs))

    override fun getCues(timeUs: Long) = delegate.getCues(timeUs.unshiftedBy(offsetUs))

    override fun release() = delegate.release()

    private fun copyFlagsFrom(source: SubtitleOutputBuffer) {
        if (source.isFirstSample) addFlag(C.BUFFER_FLAG_FIRST_SAMPLE)
        if (source.isEndOfStream) addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        if (source.isKeyFrame) addFlag(C.BUFFER_FLAG_KEY_FRAME)
        if (source.isLastSample) addFlag(C.BUFFER_FLAG_LAST_SAMPLE)
        if (source.hasSupplementalData()) addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        if (source.notDependedOn()) addFlag(C.BUFFER_FLAG_NOT_DEPENDED_ON)
    }
}

/** True only when Media3 has selected a text track that the offset decoder can actually decode. */
@UnstableApi
internal fun Tracks.hasSelectedDelayableSubtitle(factory: SubtitleDecoderFactory): Boolean =
    groups.any { group ->
        group.type == C.TRACK_TYPE_TEXT &&
            (0 until group.length).any { index ->
                group.isTrackSelected(index) && factory.supportsFormat(group.getTrackFormat(index))
            }
    }

/**
 * Replaces only the text renderer. Video, audio, metadata, and MediaSession behavior remain owned by
 * [DefaultRenderersFactory]. Legacy decoding is intentionally isolated here because Media3's default
 * extraction-time cue path has no timestamp-offset extension point in 1.8.
 */
@UnstableApi
internal class OffsetSubtitleRenderersFactory(
    context: Context,
    private val decoderFactory: SubtitleDecoderFactory,
) : DefaultRenderersFactory(context) {
    @Suppress("DEPRECATION")
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out += TextRenderer(output, outputLooper, decoderFactory).apply {
            experimentalSetLegacyDecodingEnabled(true)
        }
    }
}

private const val MICROS_PER_MILLISECOND = 1_000L

private fun Long.saturatingMillisToUs(): Long = when {
    this > Long.MAX_VALUE / MICROS_PER_MILLISECOND -> Long.MAX_VALUE
    this < Long.MIN_VALUE / MICROS_PER_MILLISECOND -> Long.MIN_VALUE
    else -> this * MICROS_PER_MILLISECOND
}

private fun Long.shiftedBy(offsetUs: Long): Long {
    if (this == C.TIME_UNSET || this == Long.MAX_VALUE || this == Long.MIN_VALUE) return this
    return when {
        offsetUs > 0 && this > Long.MAX_VALUE - offsetUs -> Long.MAX_VALUE
        offsetUs < 0 && this < Long.MIN_VALUE - offsetUs -> Long.MIN_VALUE
        else -> this + offsetUs
    }
}

private fun Long.unshiftedBy(offsetUs: Long): Long = when (offsetUs) {
    Long.MIN_VALUE -> Long.MAX_VALUE
    else -> shiftedBy(-offsetUs)
}
