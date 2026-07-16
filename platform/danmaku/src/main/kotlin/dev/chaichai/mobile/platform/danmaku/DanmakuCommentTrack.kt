package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuComment
import dev.chaichai.mobile.core.contracts.DanmakuOverlaySnapshot
import dev.chaichai.mobile.core.contracts.DanmakuPosition
import dev.chaichai.mobile.core.contracts.DanmakuVisibleComment

/**
 * A time-indexed, immutable comment track. [snapshotAt] derives the density-bounded set of comments
 * visible at a given media position — the single source of truth for synchronization:
 *
 *  - Position-derived: pause simply stops advancing position (snapshot freezes); seek jumps it.
 *  - Speed-aware: a comment stays on screen for a constant wall-clock duration, so its media-time
 *    window scales with [speed] (at 2x, media covers the window twice as fast).
 *  - Resource-bounded: at most [maxLanes] concurrent lanes and [maxVisible] total comments; extra
 *    candidates are dropped, capping CPU/memory/on-screen density regardless of source size.
 */
class DanmakuCommentTrack(
    comments: List<DanmakuComment>,
    private val scrollWindowSeconds: Double = SCROLL_WINDOW_SECONDS,
    private val pinnedWindowSeconds: Double = PINNED_WINDOW_SECONDS,
    private val maxLanes: Int = MAX_LANES,
    private val maxVisible: Int = MAX_VISIBLE,
) {
    /** Sorted ascending by time so [snapshotAt] can stop scanning once it passes the position. */
    private val ordered: List<DanmakuComment> = comments.sortedBy { it.timeTicks }

    val size: Int get() = ordered.size

    /**
     * [laneScale] (0f, 1f] shrinks the effective lane/visibility budget — e.g. the tuning
     * screen-fraction control — while resource limits (at most [maxLanes] lanes, [maxVisible]
     * comments) are still always enforced.
     */
    fun snapshotAt(
        positionTicks: Long,
        isPaused: Boolean,
        speed: Float,
        laneScale: Float = 1f,
    ): DanmakuOverlaySnapshot {
        val safeSpeed = if (speed <= 0f) 1.0f else speed
        val scrollWindow = windowTicks(scrollWindowSeconds, safeSpeed)
        val pinnedWindow = windowTicks(pinnedWindowSeconds, safeSpeed)
        val effectiveLanes = (maxLanes * laneScale.coerceIn(0f, 1f)).toInt().coerceIn(1, maxLanes)
        val effectiveVisible = (maxVisible * laneScale.coerceIn(0f, 1f)).toInt().coerceIn(1, maxVisible)

        // scrollLaneFreeAt[lane] = earliest spawn time a new scroll comment may reuse that lane.
        val scrollLaneFreeAt = LongArray(effectiveLanes) { Long.MIN_VALUE }
        var pinnedLane = 0
        val visible = ArrayList<DanmakuVisibleComment>(effectiveVisible)

        for (comment in ordered) {
            if (comment.timeTicks > positionTicks) break // future comment; all later are later still.
            val window = if (comment.position == DanmakuPosition.Scroll) scrollWindow else pinnedWindow
            if (positionTicks - comment.timeTicks > window) continue // already left the screen.

            val progress = ((positionTicks - comment.timeTicks).toFloat() / window).coerceIn(0f, 1f)
            val lane: Int = when (comment.position) {
                DanmakuPosition.Scroll -> {
                    val chosen = comment.laneHint?.let { it % effectiveLanes }
                        ?.takeIf { scrollLaneFreeAt[it] <= comment.timeTicks }
                        ?: (0 until effectiveLanes).firstOrNull { scrollLaneFreeAt[it] <= comment.timeTicks }
                    if (chosen == null) continue // all lanes busy -> drop to bound density.
                    // Reserve the lane until this comment has cleared enough for a follower.
                    scrollLaneFreeAt[chosen] = comment.timeTicks + window / LANE_REUSE_DIVISOR
                    chosen
                }
                DanmakuPosition.Top, DanmakuPosition.Bottom -> {
                    val chosen = pinnedLane % effectiveLanes
                    pinnedLane++
                    chosen
                }
            }
            visible.add(DanmakuVisibleComment(comment, lane, progress))
            if (visible.size >= effectiveVisible) break
        }

        val laneCount = visible.maxOfOrNull { it.lane + 1 } ?: 0
        return DanmakuOverlaySnapshot(
            positionTicks = positionTicks,
            isPaused = isPaused,
            speed = safeSpeed,
            laneCount = laneCount,
            visible = visible,
        )
    }

    private fun windowTicks(windowSeconds: Double, speed: Float): Long =
        (windowSeconds * speed * TICKS_PER_SECOND).toLong().coerceAtLeast(1L)

    companion object {
        const val TICKS_PER_SECOND = 10_000_000L
        const val SCROLL_WINDOW_SECONDS = 8.0
        const val PINNED_WINDOW_SECONDS = 4.0
        const val MAX_LANES = 12
        const val MAX_VISIBLE = 60
        private const val LANE_REUSE_DIVISOR = 3
    }
}
