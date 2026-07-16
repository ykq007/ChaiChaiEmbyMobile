package dev.chaichai.mobile.platform.server

import android.content.Context
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.PlaybackPreferences
import dev.chaichai.mobile.core.contracts.SubtitleAppearance
import dev.chaichai.mobile.core.contracts.SubtitleColorPreset
import dev.chaichai.mobile.core.contracts.SubtitleEdgeStyle
import dev.chaichai.mobile.core.contracts.SubtitlePosition
import dev.chaichai.mobile.core.contracts.VideoScaleMode

/**
 * Persists playback personalization as plain SharedPreferences key-value pairs (not sensitive,
 * unlike [KeystoreSessionVault]'s encrypted session record, so no encryption is needed here).
 * Speed is keyed by server+user ([HomeScope]) so it applies across all media for that account;
 * subtitle delay is keyed by server+item ([MediaIdentity]) so it never leaks across media.
 *
 * DOCUMENTED SCOPE for subtitle appearance (#33): every [SubtitleAppearance] field is stored keyed by
 * server+user ([HomeScope]), same as speed — a USER preference, not media-scoped (see the doc comment
 * on [SubtitleAppearance] for the rationale). Migration: a pre-#33 install has no `_scale` key for a
 * scope, so [subtitleAppearanceFor] returns [SubtitleAppearance.Default] verbatim with no data loss and
 * no partial/corrupt record.
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

    override fun subtitleAppearanceFor(scope: HomeScope): SubtitleAppearance {
        val prefix = appearanceKeyPrefix(scope)
        if (!preferences.contains("${prefix}_scale")) return SubtitleAppearance.Default
        val default = SubtitleAppearance.Default
        return SubtitleAppearance(
            textScale = preferences.getFloat("${prefix}_scale", default.textScale),
            position = preferences.getString("${prefix}_position", null)
                ?.let { runCatching { SubtitlePosition.valueOf(it) }.getOrNull() } ?: default.position,
            colorPreset = preferences.getString("${prefix}_color", null)
                ?.let { runCatching { SubtitleColorPreset.valueOf(it) }.getOrNull() } ?: default.colorPreset,
            edgeStyle = preferences.getString("${prefix}_edge", null)
                ?.let { runCatching { SubtitleEdgeStyle.valueOf(it) }.getOrNull() } ?: default.edgeStyle,
            windowOpacity = preferences.getFloat("${prefix}_opacity", default.windowOpacity),
        )
    }

    override fun setSubtitleAppearance(scope: HomeScope, appearance: SubtitleAppearance) {
        val prefix = appearanceKeyPrefix(scope)
        preferences.edit()
            .putFloat("${prefix}_scale", appearance.textScale)
            .putString("${prefix}_position", appearance.position.name)
            .putString("${prefix}_color", appearance.colorPreset.name)
            .putString("${prefix}_edge", appearance.edgeStyle.name)
            .putFloat("${prefix}_opacity", appearance.windowOpacity)
            .apply()
    }

    /**
     * DOCUMENTED SCOPE (#35): server+user, exactly like [speedFor]/[subtitleAppearanceFor] — a USER
     * preference. Migration: a pre-#35 install has no `scale_mode_` key for a scope, so this returns
     * [VideoScaleMode.Fit] verbatim with no data loss and no partial/corrupt record. A stored value that
     * no longer parses to a valid enum constant (defensive against a future removed mode) also falls
     * back to [VideoScaleMode.Fit] rather than throwing.
     */
    override fun videoScaleModeFor(scope: HomeScope): VideoScaleMode =
        preferences.getString(scaleModeKey(scope), null)
            ?.let { runCatching { VideoScaleMode.valueOf(it) }.getOrNull() } ?: VideoScaleMode.Fit

    override fun setVideoScaleMode(scope: HomeScope, mode: VideoScaleMode) {
        preferences.edit().putString(scaleModeKey(scope), mode.name).apply()
    }

    /**
     * Diagnostics opt-in (#35) is device-wide, NOT server/user-scoped — it's a privacy decision about
     * this installation, unrelated to which account is active. Absent record migrates to `false`
     * (off by default, never silently on for a pre-#35 install).
     */
    override fun diagnosticsEnabled(): Boolean = preferences.getBoolean(DIAGNOSTICS_ENABLED_KEY, false)

    override fun setDiagnosticsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(DIAGNOSTICS_ENABLED_KEY, enabled).apply()
    }

    private fun speedKey(scope: HomeScope) = "speed_${scope.serverId}|${scope.userId}"
    private fun subtitleDelayKey(identity: MediaIdentity) = "subtitle_delay_${identity.serverId}|${identity.itemId}"
    private fun appearanceKeyPrefix(scope: HomeScope) = "subtitle_appearance_${scope.serverId}|${scope.userId}"
    private fun scaleModeKey(scope: HomeScope) = "scale_mode_${scope.serverId}|${scope.userId}"

    companion object {
        internal const val PREFERENCES_NAME = "playback_preferences"
        private const val DIAGNOSTICS_ENABLED_KEY = "diagnostics_enabled"
    }
}
