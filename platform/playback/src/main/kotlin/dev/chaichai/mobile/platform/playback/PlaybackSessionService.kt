package dev.chaichai.mobile.platform.playback

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi
import dev.chaichai.mobile.platform.server.AuthoritativePlaybackPlan
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

/** The only owner of the Media3 player and session. Feature and app code only see PlaybackCoordinator. */
class PlaybackSessionService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            PlaybackServiceOwner.updateSnapshot(
                player.currentPosition * TICKS_PER_MILLISECOND,
                !player.playWhenReady,
            )
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
                if (playbackState == Player.STATE_ENDED) PlaybackServiceOwner.publish(PlaybackEngineEvent.Completed)
            }
            override fun onPlayerError(error: PlaybackException) {
                PlaybackServiceOwner.publish(PlaybackEngineEvent.FatalError)
            }
        })
        PlaybackServiceOwner.attach(this)
        snapshotHandler.post(snapshotRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    @UnstableApi
    internal fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long) {
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(plan.headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val source = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(
            MediaItem.Builder().setUri(plan.url.toString()).setMediaId(plan.request.itemId).build(),
        )
        player.setMediaSource(source, startPositionTicks / TICKS_PER_MILLISECOND)
        player.prepare()
        player.play()
    }

    internal fun playPause() { if (player.playWhenReady) player.pause() else player.play() }
    internal fun seekTo(positionTicks: Long) { player.seekTo(positionTicks / TICKS_PER_MILLISECOND) }
    internal fun positionTicks(): Long = player.currentPosition * TICKS_PER_MILLISECOND
    internal fun isPaused(): Boolean = !player.playWhenReady
    internal fun stopPlayback() { player.stop(); stopSelf() }
    internal fun playerForSurface(): Player = player

    override fun onDestroy() {
        snapshotHandler.removeCallbacks(snapshotRunnable)
        PlaybackServiceOwner.detach(this)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private companion object {
        const val TICKS_PER_MILLISECOND = 10_000L
        const val SNAPSHOT_INTERVAL_MILLIS = 250L
    }
}

@UnstableApi
@Composable
fun Media3VideoSurface(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                player = PlaybackServiceOwner.serviceOrNull()?.playerForSurface()
            }
        },
        update = { it.player = PlaybackServiceOwner.serviceOrNull()?.playerForSurface() },
        modifier = modifier,
    )
}

internal object PlaybackServiceOwner {
    private val current = AtomicReference<PlaybackSessionService?>()
    @Volatile private var ready = CompletableDeferred<PlaybackSessionService>()
    private val mutableEvents = MutableSharedFlow<PlaybackEngineEvent>(extraBufferCapacity = 4)
    @Volatile private var snapshotPositionTicks = 0L
    @Volatile private var snapshotPaused = true
    val events: SharedFlow<PlaybackEngineEvent> = mutableEvents

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
        snapshotPositionTicks = positionTicks
        snapshotPaused = paused
    }
    fun positionTicks() = snapshotPositionTicks
    fun isPaused() = snapshotPaused
}

class Media3ServicePlaybackEngine(private val context: Context) : PlaybackEngine {
    override val positionTicks: Long get() = PlaybackServiceOwner.positionTicks()
    override val isPaused: Boolean get() = PlaybackServiceOwner.isPaused()
    override val events: SharedFlow<PlaybackEngineEvent> = PlaybackServiceOwner.events

    @UnstableApi
    override suspend fun prepare(plan: AuthoritativePlaybackPlan, startPositionTicks: Long) {
        context.startForegroundService(Intent(context, PlaybackSessionService::class.java))
        withContext(Dispatchers.Main.immediate) {
            PlaybackServiceOwner.awaitService().apply {
                prepare(plan, startPositionTicks)
                PlaybackServiceOwner.updateSnapshot(positionTicks(), isPaused())
            }
        }
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
    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        PlaybackServiceOwner.serviceOrNull()?.stopPlayback()
        PlaybackServiceOwner.updateSnapshot(positionTicks, true)
    }
}
