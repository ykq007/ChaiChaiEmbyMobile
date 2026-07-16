package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.DiagnosticsReport
import dev.chaichai.mobile.core.contracts.PlaybackDiagnostics
import dev.chaichai.mobile.core.contracts.PlaybackFailureKind
import dev.chaichai.mobile.core.contracts.PlaybackPreferences
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.platform.server.PlaybackCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Assembles the opt-in [PlaybackDiagnostics] report for support from ONLY: negotiated device/server
 * capability flags ([PlaybackCapabilities] — bitrate ceiling, audio channel ceiling, direct-play and
 * transcode profile COUNTS, never the profiles' full contents since those can embed provider-specific
 * detail), the active engine's scale-mode capability, and the KIND (never message) of the most recent
 * [PlaybackState.Failed]. It NEVER reads [PlaybackState.Active.title]/`identity`/`subtitleTracks`/any
 * URL, so a leak of one of those is structurally impossible here rather than merely filtered — see the
 * redaction unit test in `PlaybackDiagnosticsImplTest` for the negative-content proof this doubles as
 * privacy-gate source-side evidence for (issue #35 AC3/AC4/AC6).
 *
 * OFF by default; the opt-in is persisted via [preferences] (device-wide, not server/user-scoped — see
 * the doc comment on `PlaybackPreferences.diagnosticsEnabled`) so it survives recreation.
 */
class PlaybackDiagnosticsImpl(
    scope: CoroutineScope,
    state: StateFlow<PlaybackState>,
    private val capabilities: PlaybackCapabilities,
    private val engine: PlaybackEngine,
    private val preferences: PlaybackPreferences,
) : PlaybackDiagnostics {
    private val mutableEnabled = MutableStateFlow(preferences.diagnosticsEnabled())
    override val enabled: StateFlow<Boolean> = mutableEnabled
    @Volatile private var lastFailureKind: PlaybackFailureKind? = null

    init {
        scope.launch {
            state.collect { snapshot ->
                if (snapshot is PlaybackState.Failed) lastFailureKind = snapshot.reason
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
        preferences.setDiagnosticsEnabled(enabled)
    }

    override fun snapshot(): DiagnosticsReport {
        val text = buildString {
            appendLine("Playback diagnostics (redacted — no tokens, URLs, subtitle text, or titles)")
            appendLine("Negotiated max bitrate: ${capabilities.maxStreamingBitrate} kbps")
            appendLine("Negotiated max audio channels: ${capabilities.maxAudioChannels}")
            appendLine("Direct-play profiles available: ${capabilities.directPlayProfiles.size}")
            appendLine("Transcode profiles available: ${capabilities.transcodeProfiles.size}")
            appendLine("Scale-mode control supported: ${engine.videoScaleModeSupported}")
            appendLine("Supported scale modes: ${engine.supportedScaleModes.joinToString { it.name }.ifEmpty { "none" }}")
            append("Last playback failure: ${lastFailureKind?.name ?: "none"}")
        }
        return DiagnosticsReport(text)
    }
}
