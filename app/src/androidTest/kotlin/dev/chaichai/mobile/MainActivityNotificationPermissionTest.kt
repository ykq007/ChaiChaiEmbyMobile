package dev.chaichai.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNotificationPermissionTest {
    @Test
    fun notification_permission_state_is_durable_without_reprompting_or_disabling_playback() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val wasGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val preferences = context.getSharedPreferences(MainActivity.PERMISSION_PREFERENCES, 0)
        val wasRequested = preferences.getBoolean(MainActivity.NOTIFICATION_PERMISSION_REQUESTED, false)
        try {
            preferences.edit().clear().commit()
            if (wasGranted) {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.requestPlaybackNotificationPermission()
                        assertFalse(activity.hasRequestedPlaybackNotificationPermission())
                    }
                }
            } else {
                shell(
                    "pm set-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed",
                )
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.requestPlaybackNotificationPermission() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertTrue(activity.hasRequestedPlaybackNotificationPermission())
                        assertTrue(
                            activity.getSharedPreferences(MainActivity.PERMISSION_PREFERENCES, 0)
                                .getBoolean(MainActivity.NOTIFICATION_PERMISSION_REQUESTED, false),
                        )
                        activity.recreate()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertTrue(it.hasRequestedPlaybackNotificationPermission()) }
                }
            }
        } finally {
            preferences.edit()
                .putBoolean(MainActivity.NOTIFICATION_PERMISSION_REQUESTED, wasRequested)
                .commit()
            shell("pm clear-permission-flags $packageName ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed")
            // The grant itself is never mutated, so cleanup cannot terminate the instrumented app process.
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }
}
