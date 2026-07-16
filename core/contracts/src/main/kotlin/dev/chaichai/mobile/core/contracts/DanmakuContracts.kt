package dev.chaichai.mobile.core.contracts

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow boundary for the Danmaku capability: optional, time-synchronized comments rendered over
 * video from independently configured compatible endpoints. Danmaku stays independent of media
 * playback — every endpoint, matching, decoding or rendering failure maps to
 * [DanmakuState.Unavailable] and NEVER interrupts the video.
 *
 * Lifecycle: the playback surface calls [attach] when a media item becomes active, feeds
 * position/pause/speed through [onPlayback], and [detach]es when playback ends. Danmaku is disabled
 * by default; [setEnabled] toggles it (enabling triggers automatic matching for the attached media).
 */
interface DanmakuController {
    val state: StateFlow<DanmakuState>

    /** Enable or disable danmaku. Disabled by default; enabling attempts matching for attached media. */
    fun setEnabled(enabled: Boolean)

    /** Bind the currently playing media so matching can run when enabled. */
    fun attach(identity: MediaIdentity, scope: HomeScope, title: String, runtimeTicks: Long)

    /** Report the current playback position so the visible-comment snapshot can be recomputed. */
    fun onPlayback(positionTicks: Long, isPaused: Boolean, speed: Float)

    /** Release the current media track and stop rendering. */
    fun detach()
}

/** Where a comment sits on screen. Scroll comments travel right-to-left; Top/Bottom are pinned. */
enum class DanmakuPosition { Scroll, Top, Bottom }

/** A single time-anchored comment. [timeTicks] uses the same 10,000,000-ticks-per-second media clock. */
data class DanmakuComment(
    val timeTicks: Long,
    val text: String,
    val position: DanmakuPosition = DanmakuPosition.Scroll,
    val laneHint: Int? = null,
    val colorArgb: Int = DEFAULT_COLOR_ARGB,
) {
    companion object {
        const val DEFAULT_COLOR_ARGB: Int = -0x1 // opaque white
    }
}

/**
 * A comment currently on screen, with the lane it occupies and its [progress] through its display
 * window (0f = just appeared / right edge for scroll, 1f = about to leave / left edge).
 */
data class DanmakuVisibleComment(
    val comment: DanmakuComment,
    val lane: Int,
    val progress: Float,
)

/**
 * The density-bounded set of comments visible at a given position. Derived purely from position, so
 * pause simply freezes it and seek jumps it. [speed] is echoed so renderers can size motion.
 */
data class DanmakuOverlaySnapshot(
    val positionTicks: Long = 0L,
    val isPaused: Boolean = false,
    val speed: Float = 1.0f,
    val laneCount: Int = 0,
    val visible: List<DanmakuVisibleComment> = emptyList(),
) {
    companion object {
        val Empty = DanmakuOverlaySnapshot()
    }
}

/** Why danmaku could not be shown. Every value carries an actionable, human-readable message in state. */
enum class DanmakuUnavailableReason {
    NoEndpointConfigured,
    NoMatch,
    EndpointUnreachable,
    DecodeFailed,
    RenderFailed,
}

sealed interface DanmakuState {
    /** Off (the default) — no matching attempted. */
    data object Disabled : DanmakuState

    /** Enabled and attempting to match the attached media against configured endpoints. */
    data class Matching(val identity: MediaIdentity) : DanmakuState

    /** Matched and rendering. [overlay] is the currently visible, density-bounded comment set. */
    data class Active(
        val endpointName: String,
        val matchedTitle: String,
        val totalComments: Int,
        val overlay: DanmakuOverlaySnapshot = DanmakuOverlaySnapshot.Empty,
    ) : DanmakuState

    /** A contained, actionable failure. Media keeps playing. */
    data class Unavailable(
        val reason: DanmakuUnavailableReason,
        val message: String,
    ) : DanmakuState
}
