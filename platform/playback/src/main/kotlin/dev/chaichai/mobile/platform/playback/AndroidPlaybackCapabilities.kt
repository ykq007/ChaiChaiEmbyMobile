package dev.chaichai.mobile.platform.playback

import android.media.MediaCodecList
import dev.chaichai.mobile.platform.server.DirectPlayCapability
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.TranscodeCapability
import dev.chaichai.mobile.platform.server.SubtitleCapability

fun androidPlaybackCapabilities(): PlaybackCapabilities {
    val decoders = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.asList() }.map(String::lowercase).toSet()
    }.getOrDefault(setOf("video/avc", "audio/mp4a-latm"))
    val audio = when {
        "audio/mp4a-latm" in decoders -> "aac"
        "audio/opus" in decoders -> "opus"
        else -> "mp3"
    }
    val video = buildList {
        if ("video/avc" in decoders) add("h264")
        if ("video/hevc" in decoders) add("hevc")
        if ("video/x-vnd.on2.vp9" in decoders) add("vp9")
        if ("video/av01" in decoders) add("av1")
    }.ifEmpty { listOf("h264") }
    val direct = video.flatMap { codec ->
        listOf("mp4", "mkv", "webm").map { container -> DirectPlayCapability(container, codec, audio) }
    }
    val transcode = if ("h264" in video) listOf(TranscodeCapability("ts", "h264", audio)) else emptyList()
    return PlaybackCapabilities(
        maxStreamingBitrate = 20_000_000,
        maxAudioChannels = 2,
        directPlayProfiles = direct,
        transcodeProfiles = transcode,
        subtitleProfiles = listOf(
            SubtitleCapability("srt", "External"),
            SubtitleCapability("vtt", "External"),
        ),
    )
}
