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
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaMarker
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.core.contracts.TrackDelivery
import dev.chaichai.mobile.core.contracts.TrackQualifier
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection

sealed interface PlaybackStart {
    data object Beginning : PlaybackStart
    data class Resume(val positionTicks: Long) : PlaybackStart
}

data class ScopedPlaybackRequest(
    val scope: HomeScope,
    val identity: MediaIdentity,
    val start: PlaybackStart,
    val trackSelection: PlaybackTrackSelection? = null,
    val sessionReference: PlaybackSessionReference? = null,
) {
    init { require(scope.serverId == identity.serverId) { "Playback scope and media identity must share a server" } }
    val serverId: String get() = scope.serverId
    val userId: String get() = scope.userId
    val itemId: String get() = identity.itemId
}

data class DirectPlayCapability(val container: String, val videoCodec: String, val audioCodec: String)
data class TranscodeCapability(val container: String, val videoCodec: String, val audioCodec: String)
data class PlaybackCapabilities(
    val maxStreamingBitrate: Int,
    val maxAudioChannels: Int,
    val directPlayProfiles: List<DirectPlayCapability>,
    val transcodeProfiles: List<TranscodeCapability>,
    val subtitleProfiles: List<SubtitleCapability> = emptyList(),
)

data class PlaybackSessionReference(
    val mediaSourceId: String,
    val playSessionId: String,
) {
    init {
        require(mediaSourceId.isNotBlank()) { "Media source identity is required" }
        require(playSessionId.isNotBlank()) { "Play session identity is required" }
    }
}
data class SubtitleCapability(val format: String, val method: String)

enum class PlaybackMethod { DirectPlay, Remux, Transcode }

data class AuthoritativePlaybackPlan(
    val request: ScopedPlaybackRequest,
    val sessionReference: PlaybackSessionReference,
    val method: PlaybackMethod,
    val url: HttpUrl,
    val headers: Map<String, String>,
    val runtimeTicks: Long,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val audioTracks: List<PlaybackTrack> = emptyList(),
    val subtitleTracks: List<PlaybackTrack> = emptyList(),
    /**
     * Trustworthy intro/outro markers for this negotiated item (issue #34), if the server provided
     * any. Rides on the plan rather than a separate lookup so it can never be mistaken for another
     * item's markers: it only ever exists for the identity this exact plan was negotiated for, and is
     * replaced/discarded the moment [PlaybackCoordinator] negotiates a different plan (track change,
     * new request, exit). [PlaybackCoordinatorImpl] validates each marker against [runtimeTicks] (see
     * `MediaMarker.isValid`) before it can ever be surfaced as a `SkipTarget`.
     */
    val markers: List<MediaMarker> = emptyList(),
) {
    val mediaSourceId: String get() = sessionReference.mediaSourceId
    val playSessionId: String get() = sessionReference.playSessionId
}

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
            mediaSourceId = request.sessionReference?.mediaSourceId,
            playSessionId = request.sessionReference?.playSessionId,
            audioStreamIndex = request.trackSelection?.audioStreamIndex,
            subtitleStreamIndex = request.trackSelection?.let { it.subtitleStreamIndex ?: SUBTITLES_OFF_INDEX },
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
                            sessionReference = PlaybackSessionReference(selected.source.id, playSessionId),
                            method = selected.method,
                            url = selected.url,
                            headers = selected.source.requiredHttpHeaders,
                            runtimeTicks = selected.source.runtimeTicks ?: 0,
                            audioStreamIndex = selected.source.defaultAudioStreamIndex,
                            subtitleStreamIndex = selected.source.defaultSubtitleStreamIndex,
                            audioTracks = selected.source.tracks(
                                PlaybackTrackType.Audio,
                                selected.source.defaultAudioStreamIndex,
                            ),
                            subtitleTracks = selected.source.tracks(
                                PlaybackTrackType.Subtitle,
                                selected.source.defaultSubtitleStreamIndex,
                            ),
                            // Real marker fetch (Emby MediaSegments) is deferred to #35: an extra
                            // network round trip here is left for a follow-up so it can be added with
                            // its own retry/caching story rather than one more unconditional call on
                            // every negotiation. `markers` defaults to empty, which is non-breaking and
                            // simply keeps the Skip control hidden until that wiring lands.
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
        val resolvedAuthority = ServerAuthority(
            resolved.scheme,
            resolved.host.lowercase(),
            resolved.port,
        )
        if (resolvedAuthority != address.authority) return null
        return Candidate(this, selection.first, resolved, selection.first.ordinal)
    }

    private fun PlaybackMediaSourceDto.tracks(
        type: PlaybackTrackType,
        currentIndex: Int?,
    ): List<PlaybackTrack> = mediaStreams.mapNotNull { stream ->
        val index = stream.index ?: return@mapNotNull null
        val streamType = when (stream.type?.lowercase()) {
            "audio" -> PlaybackTrackType.Audio
            "subtitle" -> PlaybackTrackType.Subtitle
            else -> return@mapNotNull null
        }
        if (streamType != type) return@mapNotNull null
        PlaybackTrack(
            index = index,
            type = streamType,
            language = stream.language?.takeIf(String::isNotBlank),
            codec = stream.codec?.takeIf(String::isNotBlank),
            title = stream.title?.takeIf(String::isNotBlank),
            delivery = when (stream.deliveryMethod?.lowercase()) {
                "external" -> TrackDelivery.External
                "encode", "hls" -> TrackDelivery.BurnIn
                else -> TrackDelivery.Embedded
            },
            isDefault = stream.isDefault,
            isCurrent = index == currentIndex,
            qualifiers = buildList {
                if (stream.isHearingImpaired) add(TrackQualifier.HearingImpaired)
                if (stream.isCommentary) add(TrackQualifier.Commentary)
                if (stream.isVisuallyImpaired) add(TrackQualifier.VisuallyImpaired)
            },
        )
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
        const val SUBTITLES_OFF_INDEX = -1
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
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
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
    @SerialName("MediaStreams") val mediaStreams: List<PlaybackMediaStreamDto> = emptyList(),
)

@Serializable
private data class PlaybackMediaStreamDto(
    @SerialName("Index") val index: Int? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsHearingImpaired") val isHearingImpaired: Boolean = false,
    @SerialName("IsCommentary") val isCommentary: Boolean = false,
    @SerialName("IsVisuallyImpaired") val isVisuallyImpaired: Boolean = false,
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
