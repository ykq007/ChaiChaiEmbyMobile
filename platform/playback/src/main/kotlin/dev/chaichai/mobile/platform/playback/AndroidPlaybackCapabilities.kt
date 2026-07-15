package dev.chaichai.mobile.platform.playback

import android.media.MediaCodecList
import dev.chaichai.mobile.platform.server.DirectPlayCapability
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import dev.chaichai.mobile.platform.server.TranscodeCapability
import dev.chaichai.mobile.platform.server.SubtitleCapability

fun androidPlaybackCapabilities(supportedDecoderTypes: Set<String>? = null): PlaybackCapabilities {
    val decoders = supportedDecoderTypes?.map(String::lowercase)?.toSet() ?: runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.asList() }.map(String::lowercase).toSet()
    }.getOrDefault(emptySet())
    val audio = when {
        "audio/mp4a-latm" in decoders -> "aac"
        "audio/opus" in decoders -> "opus"
        "audio/mpeg" in decoders -> "mp3"
        else -> null
    }
    val video = buildList {
        if ("video/avc" in decoders) add("h264")
        if ("video/hevc" in decoders) add("hevc")
        if ("video/x-vnd.on2.vp9" in decoders) add("vp9")
        if ("video/av01" in decoders) add("av1")
    }
    val direct = if (audio == null) emptyList() else video.flatMap { codec ->
        val containers = when (codec) {
            "h264", "hevc" -> if (audio == "aac") listOf("mp4", "mkv") else listOf("mkv")
            "vp9", "av1" -> if (audio == "opus") listOf("webm", "mkv") else listOf("mkv")
            else -> emptyList()
        }
        containers.map { container -> DirectPlayCapability(container, codec, audio) }
    }
    val transcode = if (audio == "aac" && "h264" in video) {
        listOf(TranscodeCapability("ts", "h264", "aac"))
    } else emptyList()
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
