package dev.chaichai.mobile.platform.server

import android.content.Context
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.PlaybackPreferences

/**
 * Persists playback personalization as plain SharedPreferences key-value pairs (not sensitive,
 * unlike [KeystoreSessionVault]'s encrypted session record, so no encryption is needed here).
 * Speed is keyed by server+user ([HomeScope]) so it applies across all media for that account;
 * subtitle delay is keyed by server+item ([MediaIdentity]) so it never leaks across media.
 */
class SharedPreferencesPlaybackPreferences(context: Context) : PlaybackPreferences {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun speedFor(scope: HomeScope): Float =
        preferences.getFloat(speedKey(scope), 1.0f)

    override fun setSpeed(scope: HomeScope, speed: Float) {
        preferences.edit().putFloat(speedKey(scope), speed).apply()
    }

    override fun subtitleDelayFor(identity: MediaIdentity): Long =
        preferences.getLong(subtitleDelayKey(identity), 0L)

    override fun setSubtitleDelay(identity: MediaIdentity, delayMillis: Long) {
        preferences.edit().putLong(subtitleDelayKey(identity), delayMillis).apply()
    }

    private fun speedKey(scope: HomeScope) = "speed_${scope.serverId}|${scope.userId}"
    private fun subtitleDelayKey(identity: MediaIdentity) = "subtitle_delay_${identity.serverId}|${identity.itemId}"

    companion object {
        internal const val PREFERENCES_NAME = "playback_preferences"
    }
}
