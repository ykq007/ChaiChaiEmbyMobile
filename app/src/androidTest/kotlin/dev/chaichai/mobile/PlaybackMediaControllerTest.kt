package dev.chaichai.mobile

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.chaichai.mobile.platform.playback.PlaybackSessionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlaybackMediaControllerTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun media_controller_drives_transport_system_volume_and_a_visible_notification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        var originalVolume: Int? = null
        val host = ActivityScenario.launch(MainActivity::class.java)
        try {
            MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "audio/wav")
                        .body(Buffer().write(silentWav()))
                    .build(),
            )
            val token = SessionToken(context, ComponentName(context, PlaybackSessionService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            val controller = future.get(10, TimeUnit.SECONDS)
            try {
                onInstrumentationMain {
                    controller.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId("authoritative-movie")
                            .setUri(server.url("/stream").toString())
                            .build(),
                    )
                    controller.prepare()
                    controller.play()
                }
                waitForInstrumentationState { onInstrumentationMain { controller.playWhenReady } }
                waitForInstrumentationState { onInstrumentationMain { controller.playbackState == Player.STATE_READY } }
                val positionBeforeRecreation = onInstrumentationMain { controller.currentPosition }
                val connectedTokenBefore = onInstrumentationMain { controller.connectedToken }
                val activityBefore = AtomicReference<MainActivity>()
                host.onActivity {
                    activityBefore.set(it)
                    it.recreate()
                }
                waitForInstrumentationState {
                    var recreated = false
                    host.onActivity { recreated = it !== activityBefore.get() }
                    recreated
                }
                assertEquals(connectedTokenBefore, onInstrumentationMain { controller.connectedToken })
                assertEquals("authoritative-movie", onInstrumentationMain { controller.currentMediaItem?.mediaId })
                assertTrue(onInstrumentationMain { controller.currentPosition >= positionBeforeRecreation })
                assertTrue(onInstrumentationMain { controller.playWhenReady })
                assertEquals(1, server.requestCount)

                val audio = context.getSystemService(AudioManager::class.java)
                originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                takeAudioFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                waitForInstrumentationState {
                    onInstrumentationMain {
                        controller.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    }
                }
                releaseAudioFocus()
                waitForInstrumentationState {
                    onInstrumentationMain {
                        controller.playWhenReady &&
                            controller.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    }
                }

                takeAudioFocus(AudioManager.AUDIOFOCUS_GAIN)
                waitForInstrumentationState { onInstrumentationMain { !controller.playWhenReady } }
                releaseAudioFocus()
                Thread.sleep(250)
                assertFalse(onInstrumentationMain { controller.playWhenReady })

                onInstrumentationMain { controller.seekTo(321) }

                assertFalse(onInstrumentationMain { controller.playWhenReady })
                assertEquals(321, onInstrumentationMain { controller.currentPosition })

                val oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val requestedVolume = if (oldVolume < maxVolume) oldVolume + 1 else (oldVolume - 1).coerceAtLeast(0)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, requestedVolume, 0)
                waitForInstrumentationState { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == requestedVolume }

                onInstrumentationMain { controller.play() }
                val notifications = context.getSystemService(NotificationManager::class.java)
                assertTrue(notifications.notificationChannels.isNotEmpty())
                if (notificationGranted) {
                    waitForInstrumentationState { notifications.activeNotifications.isNotEmpty() }
                    assertTrue(notifications.activeNotifications.any { it.notification.actions?.isNotEmpty() == true })
                }
            } finally {
                onInstrumentationMain {
                    controller.stop()
                    controller.release()
                }
            }
            }
        } finally {
            releaseAudioFocus()
            originalVolume?.let {
                context.getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            }
            host.close()
        }
    }

    private fun takeAudioFocus(gain: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        testContext.startActivity(
            Intent().setComponent(
                ComponentName(testContext.packageName, AudioFocusCompetitorActivity::class.java.name),
            ).putExtra(AudioFocusCompetitorActivity.EXTRA_GAIN, gain)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun releaseAudioFocus() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        testContext.sendBroadcast(
            Intent().setComponent(
                ComponentName(testContext.packageName, AudioFocusReleaseReceiver::class.java.name),
            ),
        )
    }

}
