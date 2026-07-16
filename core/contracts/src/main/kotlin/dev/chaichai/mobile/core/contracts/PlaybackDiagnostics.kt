package dev.chaichai.mobile.core.contracts

import kotlinx.coroutines.flow.StateFlow

/**
 * Opt-in playback diagnostics (Playback Polish, #35). OFF by default; [setEnabled] is how a person
 * turns it on after being shown the scope explanation in `feature:settings`, and the choice is
 * persisted (see `PlaybackPreferences.diagnosticsEnabled`/`setDiagnosticsEnabled`) so it survives
 * process death and recreation.
 *
 * [snapshot] never touches the network and never blocks — it renders whatever capability/failure
 * context the assembling `platform:*` implementation already holds into a plain, REDACTED,
 * human-readable [DiagnosticsReport]. "Redacted" is a hard contract, enforced by the assembling
 * implementation (see `PlaybackDiagnosticsImpl` in platform:playback), never by this interface: the
 * report must never contain a server token/secret, a full media/stream URL, subtitle cue text, a
 * library/item title, proxy credentials, or certificate-bypass state — only capability flags
 * (decoder/container support, scale-mode support, negotiated bitrate ceiling) and the KIND of the most
 * recent playback failure (an enum name, never a message that could embed identifying detail).
 */
interface PlaybackDiagnostics {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
    fun snapshot(): DiagnosticsReport
}

/**
 * A redacted, shareable (e.g. copy-to-clipboard) diagnostics summary. Deliberately a single plain-text
 * field: there is no structured sub-field a caller could accidentally forward unredacted, because
 * everything unsafe is filtered out before this type is ever constructed.
 */
data class DiagnosticsReport(val text: String)
