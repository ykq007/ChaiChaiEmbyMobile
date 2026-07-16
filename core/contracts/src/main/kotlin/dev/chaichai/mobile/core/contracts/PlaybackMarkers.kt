package dev.chaichai.mobile.core.contracts

/** Which trustworthy skip window a [MediaMarker] describes. */
enum class MarkerKind { Intro, Outro }

/**
 * A server-provided, trustworthy intro/outro marker for ONE media item (issue #34), expressed in
 * ticks within that item's own runtime. Android-free so it can ride on `AuthoritativePlaybackPlan`
 * (platform:server) and be validated in a plain unit test.
 *
 * There is deliberately NO [MediaIdentity] field here: a [MediaMarker] only ever exists as part of the
 * specific [AuthoritativePlaybackPlan] that was negotiated for one identity, so it can never be
 * mistaken for a marker belonging to a different item — the moment that plan is replaced (a track
 * change, a new [MediaPlaybackRequest], exit), its markers go with it. See
 * `PlaybackCoordinatorImpl.publishActive`, which always reads markers off the CURRENTLY active plan.
 */
data class MediaMarker(
    val kind: MarkerKind,
    val startTicks: Long,
    val endTicks: Long,
) {
    private val isWellFormed: Boolean get() = startTicks in 0..endTicks && endTicks > startTicks

    /**
     * A marker is trustworthy for a given [runtimeTicks] only when well-formed, non-zero-length, and
     * fully contained within `[0, runtimeTicks]`. Zero-length, out-of-range, or malformed markers are
     * rejected before they can ever be surfaced as a [SkipTarget] (issue #34 AC1/AC4).
     */
    fun isValid(runtimeTicks: Long): Boolean = isWellFormed && runtimeTicks > 0 && endTicks <= runtimeTicks
}

/**
 * A currently-offered skip action, surfaced on [PlaybackState.Active.skipTargets] — only present when
 * a [MediaMarker] validated for the active identity/runtime AND the current playback position falls
 * inside its offer window (see [SkipTargets.current]). Activating it calls
 * `PlaybackCoordinator.skip`, which reuses the existing seek path (no renegotiation).
 */
data class SkipTarget(
    val kind: MarkerKind,
    val label: String,
    val seekToTicks: Long,
)

/**
 * Pure function deciding which [SkipTarget] (if any) is currently offered, given the active plan's
 * validated markers, its runtime, and the live playback position. Kept Android-free and side-effect
 * free so it is independently unit-testable (issue #34 AC1/AC4) and reused unchanged by
 * `PlaybackCoordinatorImpl` every time position/markers change.
 */
object SkipTargets {
    fun current(markers: List<MediaMarker>, runtimeTicks: Long, positionTicks: Long): List<SkipTarget> {
        val valid = markers.filter { it.isValid(runtimeTicks) }
        val intro = valid.firstOrNull { it.kind == MarkerKind.Intro && positionTicks < it.endTicks }
        if (intro != null) return listOf(SkipTarget(MarkerKind.Intro, "Skip intro", intro.endTicks))
        val outro = valid.firstOrNull {
            it.kind == MarkerKind.Outro && positionTicks >= it.startTicks && positionTicks < it.endTicks
        }
        if (outro != null) return listOf(SkipTarget(MarkerKind.Outro, "Skip outro", outro.endTicks))
        return emptyList()
    }
}
