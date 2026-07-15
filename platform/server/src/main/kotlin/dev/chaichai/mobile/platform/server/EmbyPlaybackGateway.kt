package dev.chaichai.mobile.platform.server

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface PlaybackStart {
    data object Beginning : PlaybackStart
    data class Resume(val positionTicks: Long) : PlaybackStart
}

data class ScopedPlaybackRequest(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val start: PlaybackStart,
)

data class DirectPlayCapability(val container: String, val videoCodec: String, val audioCodec: String)
data class TranscodeCapability(val container: String, val videoCodec: String, val audioCodec: String)
data class PlaybackCapabilities(
    val maxStreamingBitrate: Int,
    val maxAudioChannels: Int,
    val directPlayProfiles: List<DirectPlayCapability>,
    val transcodeProfiles: List<TranscodeCapability>,
    val subtitleProfiles: List<SubtitleCapability> = emptyList(),
)
data class SubtitleCapability(val format: String, val method: String)

enum class PlaybackMethod { DirectPlay, Remux, Transcode }

data class AuthoritativePlaybackPlan(
    val request: ScopedPlaybackRequest,
    val mediaSourceId: String,
    val playSessionId: String,
    val method: PlaybackMethod,
    val url: HttpUrl,
    val headers: Map<String, String>,
    val runtimeTicks: Long,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
)

enum class PlaybackFailure {
    UnsupportedMedia,
    TranscodingRefused,
    SourceUnavailable,
    AuthorizationExpired,
    Network,
}

sealed interface PlaybackNegotiationResult {
    data class Ready(val plan: AuthoritativePlaybackPlan) : PlaybackNegotiationResult
    data class Failed(val reason: PlaybackFailure) : PlaybackNegotiationResult
}

enum class PlaybackReportKind { Playing, Progress, Stopped }

data class PlaybackReport(
    val plan: AuthoritativePlaybackPlan,
    val kind: PlaybackReportKind,
    val positionTicks: Long,
    val isPaused: Boolean,
    val isMuted: Boolean = false,
    val isSeekable: Boolean = true,
    val event: PlaybackProgressEvent? = null,
)
enum class PlaybackProgressEvent { TimeUpdate, Pause, Unpause, Seek }

interface PlaybackGateway {
    suspend fun negotiate(request: ScopedPlaybackRequest, capabilities: PlaybackCapabilities): PlaybackNegotiationResult
    suspend fun report(event: PlaybackReport): Boolean
}

class EmbyPlaybackGateway(
    private val vault: SessionVault,
    private val clients: AuthorityScopedHttpClients = AuthorityScopedHttpClients(),
    private val deviceId: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackGateway {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun negotiate(
        request: ScopedPlaybackRequest,
        capabilities: PlaybackCapabilities,
    ): PlaybackNegotiationResult = withContext(ioDispatcher) {
        val session = vault.restore()?.takeIf {
            it.serverId == request.serverId && it.userId == request.userId
        } ?: return@withContext PlaybackNegotiationResult.Failed(PlaybackFailure.AuthorizationExpired)
        val body = PlaybackInfoRequestDto(
            startTimeTicks = (request.start as? PlaybackStart.Resume)?.positionTicks ?: 0,
            maxStreamingBitrate = capabilities.maxStreamingBitrate,
            maxAudioChannels = capabilities.maxAudioChannels,
            deviceProfile = DeviceProfileDto(
                directPlayProfiles = capabilities.directPlayProfiles.map {
                    DirectPlayProfileDto(it.container, it.audioCodec, it.videoCodec)
                },
                transcodingProfiles = capabilities.transcodeProfiles.map {
                    TranscodingProfileDto(it.container, it.audioCodec, it.videoCodec)
                },
                subtitleProfiles = capabilities.subtitleProfiles.map { SubtitleProfileDto(it.format, it.method) },
            ),
        )
        val httpRequest = authenticatedRequest(session)
            .url(
                session.address.apiUrl("Items/${request.itemId}/PlaybackInfo").toString().toHttpUrl().newBuilder()
                    .addQueryParameter("UserId", request.userId)
                    .build(),
            )
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(httpRequest).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        return@withContext PlaybackNegotiationResult.Failed(PlaybackFailure.AuthorizationExpired)
                    }
                    if (!response.isSuccessful) {
                        return@withContext PlaybackNegotiationResult.Failed(PlaybackFailure.Network)
                    }
                    val payload = json.decodeFromString<PlaybackInfoResponseDto>(response.body.string())
                    payload.errorCode?.let {
                        return@withContext PlaybackNegotiationResult.Failed(it.toFailure())
                    }
                    val selected = payload.mediaSources.mapNotNull { source ->
                        source.toCandidate(session.address)
                    }.minByOrNull { it.rank }
                        ?: return@withContext PlaybackNegotiationResult.Failed(PlaybackFailure.SourceUnavailable)
                    val playSessionId = payload.playSessionId?.takeIf(String::isNotBlank)
                        ?: return@withContext PlaybackNegotiationResult.Failed(PlaybackFailure.SourceUnavailable)
                    if (!vault.restore().matches(session)) {
                        return@withContext PlaybackNegotiationResult.Failed(PlaybackFailure.AuthorizationExpired)
                    }
                    PlaybackNegotiationResult.Ready(
                        AuthoritativePlaybackPlan(
                            request = request,
                            mediaSourceId = selected.source.id,
                            playSessionId = playSessionId,
                            method = selected.method,
                            url = selected.url,
                            headers = selected.source.requiredHttpHeaders,
                            runtimeTicks = selected.source.runtimeTicks ?: 0,
                            audioStreamIndex = selected.source.defaultAudioStreamIndex,
                            subtitleStreamIndex = selected.source.defaultSubtitleStreamIndex,
                        ),
                    )
                }
        } catch (_: IOException) {
            PlaybackNegotiationResult.Failed(PlaybackFailure.Network)
        } catch (_: Exception) {
            PlaybackNegotiationResult.Failed(PlaybackFailure.SourceUnavailable)
        }
    }

    override suspend fun report(event: PlaybackReport): Boolean = withContext(ioDispatcher) {
        val session = vault.restore()?.takeIf {
            it.serverId == event.plan.request.serverId && it.userId == event.plan.request.userId
        } ?: return@withContext false
        val route = when (event.kind) {
            PlaybackReportKind.Playing -> "Sessions/Playing"
            PlaybackReportKind.Progress -> "Sessions/Playing/Progress"
            PlaybackReportKind.Stopped -> "Sessions/Playing/Stopped"
        }
        val body = PlaybackReportDto(
            itemId = event.plan.request.itemId,
            mediaSourceId = event.plan.mediaSourceId,
            playSessionId = event.plan.playSessionId,
            positionTicks = event.positionTicks,
            runtimeTicks = event.plan.runtimeTicks,
            isPaused = event.isPaused,
            isMuted = event.isMuted,
            canSeek = event.isSeekable,
            playMethod = event.plan.method.apiName,
            eventName = event.event?.name,
        )
        try {
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(
                    authenticatedRequest(session).url(session.address.apiUrl(route).toString())
                        .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE)).build(),
                ).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun authenticatedRequest(session: StoredSession) = Request.Builder()
        .header("X-Emby-Token", session.accessToken.encoded())
        .header("X-Emby-Authorization", embyAuthorization(deviceId, session.userId))

    private fun StoredSession?.matches(expected: StoredSession): Boolean = this != null &&
        serverId == expected.serverId && userId == expected.userId && accessToken.encoded() == expected.accessToken.encoded()

    private fun PlaybackMediaSourceDto.toCandidate(address: ServerAddress): Candidate? {
        val selection = when {
            supportsDirectPlay && !directStreamUrl.isNullOrBlank() -> PlaybackMethod.DirectPlay to directStreamUrl
            supportsDirectStream && !directStreamUrl.isNullOrBlank() -> PlaybackMethod.Remux to directStreamUrl
            supportsTranscoding && !transcodingUrl.isNullOrBlank() -> PlaybackMethod.Transcode to transcodingUrl
            else -> return null
        }
        val resolved = address.apiUrl("").toString().toHttpUrl().resolve(selection.second!!) ?: return null
        return Candidate(this, selection.first, resolved, selection.first.ordinal)
    }

    private data class Candidate(
        val source: PlaybackMediaSourceDto,
        val method: PlaybackMethod,
        val url: HttpUrl,
        val rank: Int,
    )

    private val PlaybackMethod.apiName: String
        get() = when (this) {
            PlaybackMethod.DirectPlay -> "DirectPlay"
            PlaybackMethod.Remux -> "DirectStream"
            PlaybackMethod.Transcode -> "Transcode"
        }

    private fun String.toFailure() = when (lowercase()) {
        "no compatible stream", "nocompatiblestream" -> PlaybackFailure.UnsupportedMedia
        "transcoding not allowed", "transcodingnotallowed", "cannotconvert" -> PlaybackFailure.TranscodingRefused
        "not found", "mediasourceunavailable" -> PlaybackFailure.SourceUnavailable
        else -> PlaybackFailure.UnsupportedMedia
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class PlaybackInfoRequestDto(
    @SerialName("StartTimeTicks") val startTimeTicks: Long,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int,
    @SerialName("MaxAudioChannels") val maxAudioChannels: Int,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = true,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfileDto,
)

@Serializable
private data class DeviceProfileDto(
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfileDto>,
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfileDto>,
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfileDto>,
)

@Serializable
private data class SubtitleProfileDto(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String,
)

@Serializable
private data class DirectPlayProfileDto(
    @SerialName("Container") val container: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("Type") val type: String = "Video",
)

@Serializable
private data class TranscodingProfileDto(
    @SerialName("Container") val container: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("Type") val type: String = "Video",
    @SerialName("Protocol") val protocol: String = "hls",
)

@Serializable
private data class PlaybackInfoResponseDto(
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("MediaSources") val mediaSources: List<PlaybackMediaSourceDto> = emptyList(),
)

@Serializable
private data class PlaybackMediaSourceDto(
    @SerialName("Id") val id: String,
    @SerialName("RunTimeTicks") val runtimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("RequiredHttpHeaders") val requiredHttpHeaders: Map<String, String> = emptyMap(),
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
)

@Serializable
private data class PlaybackReportDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PlaySessionId") val playSessionId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("RunTimeTicks") val runtimeTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("IsMuted") val isMuted: Boolean,
    @SerialName("CanSeek") val canSeek: Boolean,
    @SerialName("PlayMethod") val playMethod: String,
    @SerialName("EventName") val eventName: String? = null,
    @SerialName("PlaybackRate") val playbackRate: Double = 1.0,
    @SerialName("RepeatMode") val repeatMode: String = "RepeatNone",
)
