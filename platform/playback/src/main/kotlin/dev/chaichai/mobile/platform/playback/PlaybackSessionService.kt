package dev.chaichai.mobile.platform.playback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.chaichai.mobile.core.contracts.SubtitleAppearance
import dev.chaichai.mobile.core.contracts.SubtitleColorPreset
import dev.chaichai.mobile.core.contracts.SubtitleEdgeStyle
import dev.chaichai.mobile.core.contracts.SubtitlePositionBounds
import dev.chaichai.mobile.core.contracts.VideoScaleMode
import dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

/** The only owner of the Media3 player and session. Feature and app code only see PlaybackCoordinator. */
class PlaybackSessionService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private var snapshotCount = 0
    private var stoppedPublished = false
    private var reportControlEvents = false
    private var startedActivityCount = 0
    private var currentPlan: AuthoritativePlaybackPlan? = null
    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { startedActivityCount++ }
        override fun onActivityStopped(activity: Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0) {
                publishControlProgress(
                    dev.chaichai.mobile.platform.server.PlaybackProgressEvent.TimeUpdate,
                    player.currentPosition * TICKS_PER_MILLISECOND,
                    player.isPausedForReporting(),
                )
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            PlaybackServiceOwner.updateSnapshot(
                player.currentPosition * TICKS_PER_MILLISECOND,
                player.isPausedForReporting(),
            )
            snapshotCount++
            if (snapshotCount % SNAPSHOTS_PER_PROGRESS == 0 && player.mediaItemCount > 0) {
                PlaybackServiceOwner.publish(
                    PlaybackEngineEvent.Progress(
                        dev.chaichai.mobile.platform.server.PlaybackProgressEvent.TimeUpdate,
                        player.currentPosition * TICKS_PER_MILLISECOND,
                        player.isPausedForReporting(),
                    ),
                )
            }
            snapshotHandler.postDelayed(this, SNAPSHOT_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
        mediaSession = MediaSession.Builder(this, player).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) PlaybackServiceOwner.publish(PlaybackEngineEvent.Ready)
                if (playbackState == Player.STATE_ENDED) PlaybackServiceOwner.publish(PlaybackEngineEvent.Completed)
            }
            override fun onPlayerError(error: PlaybackException) {
                PlaybackServiceOwner.publish(PlaybackEngineEvent.FatalError)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                publishReportedPlaybackState()
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                publishReportedPlaybackState()
            }
            private fun publishReportedPlaybackState() {
                val isPaused = player.isPausedForReporting()
                publishControlProgress(
                    if (isPaused) dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Pause
                    else dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Unpause,
                    player.currentPosition * TICKS_PER_MILLISECOND,
                    isPaused,
                )
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    publishControlProgress(
                        dev.chaichai.mobile.platform.server.PlaybackProgressEvent.Seek,
                        newPosition.positionMs * TICKS_PER_MILLISECOND,
                        player.isPausedForReporting(),
                    )
                }
            }
        })
        PlaybackServiceOwner.attach(this)
        application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        snapshotHandler.post(snapshotRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    @UnstableApi
    internal fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long, startPaused: Boolean) {
        stoppedPublished = false
        reportControlEvents = false
        currentPlan = plan
        val redirectRejectingClient = playbackHttpClient()
        val dataSourceFactory = OkHttpDataSource.Factory(redirectRejectingClient)
            .setDefaultRequestProperties(plan.headers)
        val source = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(
            MediaItem.Builder().setUri(plan.url.toString()).setMediaId(plan.request.itemId).build(),
        )
        player.playWhenReady = !startPaused
        player.setMediaSource(source, startPositionTicks / TICKS_PER_MILLISECOND)
        player.prepare()
    }

    /**
     * Side-load [localRef] as a subtitle onto the CURRENT media, preserving position and playing state.
     * Re-prepares the same media source with the subtitle attached at the current position with the
     * same playWhenReady, so nothing renegotiates and playback does not restart from zero.
     */
    @UnstableApi
    internal fun applyExternalSubtitle(localRef: String, mimeType: String, language: String?) {
        val plan = currentPlan ?: return
        val resumePositionMs = player.currentPosition
        val wasPlaying = player.playWhenReady
        val dataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient())
            .setDefaultRequestProperties(plan.headers)
        val subtitle = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(localRef))
            .setMimeType(mimeType)
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val source = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(
            MediaItem.Builder()
                .setUri(plan.url.toString())
                .setMediaId(plan.request.itemId)
                .setSubtitleConfigurations(listOf(subtitle))
                .build(),
        )
        player.setMediaSource(source, resumePositionMs)
        player.playWhenReady = wasPlaying
        player.prepare()
    }

    internal fun acknowledgePlayingReported() { reportControlEvents = true }

    internal fun publishControlProgress(
        event: dev.chaichai.mobile.platform.server.PlaybackProgressEvent,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        if (!reportControlEvents) return
        PlaybackServiceOwner.publish(PlaybackEngineEvent.Progress(event, positionTicks, isPaused))
    }

    internal fun playPause() { if (player.playWhenReady) player.pause() else player.play() }
    internal fun seekTo(positionTicks: Long) { player.seekTo(positionTicks / TICKS_PER_MILLISECOND) }
    internal fun setSpeed(speed: Float) { player.playbackParameters = PlaybackParameters(speed) }
    internal fun positionTicks(): Long = player.currentPosition * TICKS_PER_MILLISECOND
    internal fun isPaused(): Boolean = player.isPausedForReporting()
    internal fun stopPlayback() {
        publishStoppedOnce()
        player.stop()
        stopSelf()
    }
    internal fun playerForSurface(): Player = player

    override fun onDestroy() {
        snapshotHandler.removeCallbacks(snapshotRunnable)
        application.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
        publishStoppedOnce()
        PlaybackServiceOwner.detach(this)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private companion object {
        const val TICKS_PER_MILLISECOND = 10_000L
        const val SNAPSHOT_INTERVAL_MILLIS = 250L
        const val SNAPSHOTS_PER_PROGRESS = 40
    }

    private fun publishStoppedOnce() {
        if (stoppedPublished) return
        stoppedPublished = true
        PlaybackServiceOwner.publish(
            PlaybackEngineEvent.Stopped(
                player.currentPosition * TICKS_PER_MILLISECOND,
                player.isPausedForReporting(),
            ),
        )
    }
}

private fun Player.isPausedForReporting(): Boolean =
    !playWhenReady || playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE

internal fun playbackHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * Applies [appearance] to this [PlayerView]'s built-in `SubtitleView` LIVE (size, color/edge/opacity
 * via [CaptionStyleCompat], and vertical position via a bottom-padding fraction bounded by
 * [SubtitlePositionBounds] so it can never land text on an unsafe inset) — no player restart.
 */
@UnstableApi
private fun PlayerView.applySubtitleAppearance(appearance: SubtitleAppearance) {
    subtitleView?.apply {
        setStyle(appearance.toCaptionStyle())
        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * appearance.textScale)
        setBottomPaddingFraction(SubtitlePositionBounds.bottomPaddingFraction(appearance.position))
    }
}

@UnstableApi
private fun SubtitleAppearance.toCaptionStyle(): CaptionStyleCompat {
    val edgeType = when (edgeStyle) {
        SubtitleEdgeStyle.None -> CaptionStyleCompat.EDGE_TYPE_NONE
        SubtitleEdgeStyle.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdgeStyle.DropShadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    }
    return CaptionStyleCompat(
        colorPreset.foregroundArgb.toInt(),
        withAlpha(colorPreset.backgroundArgb, windowOpacity),
        android.graphics.Color.TRANSPARENT,
        edgeType,
        android.graphics.Color.BLACK,
        null,
    )
}

private fun withAlpha(argb: Long, opacity: Float): Int {
    val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (argb.toInt() and 0x00FFFFFF)
}

@UnstableApi
@Composable
fun Media3VideoSurface(modifier: Modifier = Modifier) {
    val appearance by PlaybackServiceOwner.appearance.collectAsState()
    val scaleMode by PlaybackServiceOwner.scaleMode.collectAsState()
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                player = PlaybackServiceOwner.serviceOrNull()?.playerForSurface()
                applySubtitleAppearance(appearance)
                resizeMode = scaleMode.toResizeMode()
            }
        },
        update = {
            it.player = PlaybackServiceOwner.serviceOrNull()?.playerForSurface()
            it.applySubtitleAppearance(appearance)
            it.resizeMode = scaleMode.toResizeMode()
        },
        modifier = modifier,
    )
}

/**
 * Maps directly onto Media3's `AspectRatioFrameLayout` resize modes, all appliable to the existing
 * [PlayerView] in place — switching modes never restarts the player (Playback Polish, #35 AC1).
 */
@UnstableApi
private fun VideoScaleMode.toResizeMode(): Int = when (this) {
    VideoScaleMode.Fit -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScaleMode.Fill -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    VideoScaleMode.Zoom -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

internal object PlaybackServiceOwner {
    private val current = AtomicReference<PlaybackSessionService?>()
    @Volatile private var ready = CompletableDeferred<PlaybackSessionService>()
    private val mutableEvents = MutableSharedFlow<PlaybackEngineEvent>(extraBufferCapacity = 4)
    private val snapshot = AtomicReference(PlaybackEngineSnapshot(0L, true))
    private val mutableAppearance = MutableStateFlow(SubtitleAppearance.Default)
    private val mutableScaleMode = MutableStateFlow(VideoScaleMode.Fit)
    val events: SharedFlow<PlaybackEngineEvent> = mutableEvents
    val appearance: StateFlow<SubtitleAppearance> = mutableAppearance
    val scaleMode: StateFlow<VideoScaleMode> = mutableScaleMode

    fun attach(service: PlaybackSessionService) {
        current.set(service)
        ready.complete(service)
    }

    fun detach(service: PlaybackSessionService) {
        current.compareAndSet(service, null)
        if (current.get() == null) ready = CompletableDeferred()
    }

    fun serviceOrNull() = current.get()
    suspend fun awaitService() = ready.await()
    fun publish(event: PlaybackEngineEvent) { mutableEvents.tryEmit(event) }
    fun updateSnapshot(positionTicks: Long, paused: Boolean) {
        snapshot.set(PlaybackEngineSnapshot(positionTicks, paused))
    }
    fun snapshot(): PlaybackEngineSnapshot = snapshot.get()
    fun updateAppearance(appearance: SubtitleAppearance) { mutableAppearance.value = appearance }
    fun updateScaleMode(mode: VideoScaleMode) { mutableScaleMode.value = mode }
}

class Media3ServicePlaybackEngine(private val context: Context) : PlaybackEngine {
    override val snapshot: PlaybackEngineSnapshot get() = PlaybackServiceOwner.snapshot()
    override val events: SharedFlow<PlaybackEngineEvent> = PlaybackServiceOwner.events
    // ExoPlayer natively supports PlaybackParameters(speed) applied in place, without restart.
    override val speedControlSupported: Boolean get() = true
    // Media3 has no native per-track subtitle timing offset API; faking support would violate
    // AC1/AC4's "correctness over faking support" guidance, so the control stays hidden instead.
    override val subtitleDelaySupported: Boolean get() = false
    // Media3's PlayerView owns a SubtitleView with native CaptionStyleCompat + fractional text size +
    // bottom padding fraction support, all appliable in place without restarting the player (#33).
    override val subtitleAppearanceSupported: Boolean get() = true
    // AspectRatioFrameLayout's resize modes (Fit/Fill/Zoom) are all natively applied to the existing
    // PlayerView surface with no restart, so every VideoScaleMode is reliably supported (#35).
    override val videoScaleModeSupported: Boolean get() = true
    override val supportedScaleModes: List<VideoScaleMode> get() =
        listOf(VideoScaleMode.Fit, VideoScaleMode.Fill, VideoScaleMode.Zoom)

    @UnstableApi
    override suspend fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long, startPaused: Boolean) {
        context.startForegroundService(Intent(context, PlaybackSessionService::class.java))
        withContext(Dispatchers.Main.immediate) {
            PlaybackServiceOwner.awaitService().apply {
                prepare(plan, startPositionTicks, startPaused)
                PlaybackServiceOwner.updateSnapshot(positionTicks(), isPaused())
            }
        }
    }

    override suspend fun acknowledgePlayingReported() = withContext(Dispatchers.Main.immediate) {
        PlaybackServiceOwner.serviceOrNull()?.acknowledgePlayingReported()
        Unit
    }

    override suspend fun playPause() = withContext(Dispatchers.Main.immediate) {
        PlaybackServiceOwner.serviceOrNull()?.apply {
            playPause()
            PlaybackServiceOwner.updateSnapshot(positionTicks(), isPaused())
        }
        Unit
    }
    override suspend fun seekTo(positionTicks: Long) = withContext(Dispatchers.Main.immediate) {
        PlaybackServiceOwner.serviceOrNull()?.apply {
            seekTo(positionTicks)
            PlaybackServiceOwner.updateSnapshot(positionTicks(), isPaused())
        }
        Unit
    }
    override suspend fun setSpeed(speed: Float) = withContext(Dispatchers.Main.immediate) {
        PlaybackServiceOwner.serviceOrNull()?.setSpeed(speed)
        Unit
    }
    override suspend fun setSubtitleAppearance(appearance: SubtitleAppearance) = withContext(Dispatchers.Main.immediate) {
        PlaybackServiceOwner.updateAppearance(appearance)
    }
    override suspend fun setVideoScaleMode(mode: VideoScaleMode) = withContext(Dispatchers.Main.immediate) {
        PlaybackServiceOwner.updateScaleMode(mode)
    }
    @UnstableApi
    override suspend fun applyExternalSubtitle(localRef: String, mimeType: String, language: String?) =
        withContext(Dispatchers.Main.immediate) {
            PlaybackServiceOwner.serviceOrNull()?.apply {
                applyExternalSubtitle(localRef, mimeType, language)
                PlaybackServiceOwner.updateSnapshot(positionTicks(), isPaused())
            }
            Unit
        }
    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        PlaybackServiceOwner.serviceOrNull()?.stopPlayback()
        PlaybackServiceOwner.updateSnapshot(positionTicks, true)
    }
}
