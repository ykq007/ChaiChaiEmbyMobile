package dev.chaichai.mobile

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.platform.playback.PlaybackSessionService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNotificationPermissionTest {
    @Test
    fun active_playback_survives_denial_recreation_process_like_restore_and_grant() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val permissionWasGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        check(!permissionWasGranted) {
            "Permission-flow instrumentation requires the clean-install denied state"
        }
        val preferences = context.getSharedPreferences(MainActivity.PERMISSION_PREFERENCES, 0)
        val requestWasPersisted = preferences.getBoolean(MainActivity.NOTIFICATION_PERMISSION_REQUESTED, false)
        val device = UiDevice.getInstance(instrumentation)
        val denyButton = By.res("com.android.permissioncontroller", "permission_deny_button")
        var scenario: ActivityScenario<MainActivity>? = null
        var controller: MediaController? = null
        try {
            preferences.edit().clear().commit()
            shell("pm clear-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")
            val permissionPlaybackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
            MainActivity.boundariesOverrideForTesting = boundariesWithPlaybackState(permissionPlaybackState)
            scenario = ActivityScenario.launch(MainActivity::class.java)

            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse.Builder()
                        .addHeader("Content-Type", "audio/wav")
                        .body(Buffer().write(silentWav()))
                        .build(),
                )
                controller = MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackSessionService::class.java)),
                ).buildAsync().get(10, TimeUnit.SECONDS)
                onMain {
                    controller!!.setMediaItem(MediaItem.fromUri(server.url("/stream").toString()))
                    controller!!.prepare()
                    controller!!.play()
                }
                waitUntil { onMain { controller!!.playbackState == Player.STATE_READY && controller!!.playWhenReady } }

                permissionPlaybackState.value = activePlaybackState()
                assertTrue(device.wait(Until.hasObject(denyButton), 5_000))
                device.findObject(denyButton).click()
                device.waitForIdle()
                assertTrue(onMain { controller!!.playWhenReady })
                assertTrue(preferences.getBoolean(MainActivity.NOTIFICATION_PERMISSION_REQUESTED, false))

                scenario!!.recreate()
                assertFalse(device.wait(Until.hasObject(denyButton), 1_000))
                assertTrue(onMain { controller!!.playWhenReady })

                scenario!!.close()
                MainActivity.boundariesOverrideForTesting = boundariesWithPlaybackState(
                    MutableStateFlow(activePlaybackState()),
                )
                scenario = ActivityScenario.launch(MainActivity::class.java)
                assertFalse(device.wait(Until.hasObject(denyButton), 1_000))
                assertTrue(onMain { controller!!.playWhenReady })

                shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
                val notifications = context.getSystemService(NotificationManager::class.java)
                waitUntil { notifications.activeNotifications.isNotEmpty() }
                assertTrue(notifications.notificationChannels.isNotEmpty())
                assertTrue(notifications.activeNotifications.any { it.notification.actions?.isNotEmpty() == true })
                assertTrue(onMain { controller!!.playWhenReady })
            }
        } finally {
            scenario?.close()
            controller?.let { mediaController ->
                onMain {
                    mediaController.stop()
                    mediaController.release()
                }
            }
            MainActivity.boundariesOverrideForTesting = null
            preferences.edit()
                .putBoolean(MainActivity.NOTIFICATION_PERMISSION_REQUESTED, requestWasPersisted)
                .commit()
            shell("pm clear-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")
            if (!permissionWasGranted) schedulePermissionRestoreAfterInstrumentation(packageName)
        }
    }

    private fun boundariesWithPlaybackState(state: MutableStateFlow<PlaybackState>): AppBoundaries {
        val playback = object : NoOpPlaybackCoordinator() {
            override val state = state
            override val isPlaying = MutableStateFlow(true)
        }
        return AppBoundaries(
            gateway = object : EmbyGateway {
                override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
            },
            playback = playback,
            clock = AppClock { Instant.EPOCH },
            connectivity = object : ConnectivityMonitor {
                override val isOnline = MutableStateFlow(true)
            },
        )
    }

    private fun activePlaybackState() = PlaybackState.Active(
        MediaIdentity("server", "movie"),
        "Arrival",
        600_000_000,
        7_200_000_000,
        false,
    )

    private fun schedulePermissionRestoreAfterInstrumentation(packageName: String) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        shell(
            "sh -c '(while dumpsys activity activities | grep -q mRunningInstrumentation; " +
                "do sleep 1; done; pm revoke $packageName $permission) >/dev/null 2>&1 &'",
        )
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        check(condition()) { "Timed out waiting for permission playback state" }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun silentWav(): ByteArray {
        val sampleRate = 8_000
        val dataSize = sampleRate * 2 * 60
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(dataSize)
            put(ByteArray(dataSize))
        }.array()
    }
}
