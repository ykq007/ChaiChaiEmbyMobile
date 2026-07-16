package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuComment
import dev.chaichai.mobile.core.contracts.DanmakuPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuCommentTrackTest {

    private val tps = DanmakuCommentTrack.TICKS_PER_SECOND

    @Test
    fun only_comments_within_the_window_are_visible_at_a_position() {
        val track = DanmakuCommentTrack(
            listOf(
                DanmakuComment(0, "past"),
                DanmakuComment(5 * tps, "now"),
                DanmakuComment(60 * tps, "future"),
            ),
        )
        // At t=10s with an 8s scroll window: "past" (t=0) already left (10s old), "now" (t=5s, 5s old)
        // is on screen, "future" (t=60s) not yet.
        val visible = track.snapshotAt(10 * tps, isPaused = false, speed = 1.0f).visible.map { it.comment.text }
        assertEquals(listOf("now"), visible)
    }

    @Test
    fun pause_freezes_the_snapshot_to_the_same_position() {
        val track = DanmakuCommentTrack(listOf(DanmakuComment(0, "a")))
        val playing = track.snapshotAt(2 * tps, isPaused = false, speed = 1.0f)
        val paused = track.snapshotAt(2 * tps, isPaused = true, speed = 1.0f)
        assertEquals(playing.visible.map { it.comment.text }, paused.visible.map { it.comment.text })
        assertEquals(playing.visible.first().progress, paused.visible.first().progress, 0.0001f)
        assertTrue(paused.isPaused)
    }

    @Test
    fun seek_backwards_recovers_earlier_comments() {
        val track = DanmakuCommentTrack(listOf(DanmakuComment(0, "a"), DanmakuComment(100 * tps, "b")))
        val far = track.snapshotAt(200 * tps, isPaused = false, speed = 1.0f)
        assertTrue(far.visible.isEmpty())
        val rewound = track.snapshotAt(1 * tps, isPaused = false, speed = 1.0f)
        assertEquals(listOf("a"), rewound.visible.map { it.comment.text })
    }

    @Test
    fun higher_speed_widens_the_media_time_window() {
        // A comment at t=0 seen at t=12s: outside the 8s window at 1x, still inside at 2x (16s window).
        val track = DanmakuCommentTrack(listOf(DanmakuComment(0, "a")))
        assertTrue(track.snapshotAt(12 * tps, isPaused = false, speed = 1.0f).visible.isEmpty())
        assertFalse(track.snapshotAt(12 * tps, isPaused = false, speed = 2.0f).visible.isEmpty())
    }

    @Test
    fun density_is_bounded_by_lane_capacity() {
        // 100 comments all at the same instant: only MAX_LANES can be shown concurrently.
        val comments = (0 until 100).map { DanmakuComment(0, "c$it") }
        val snapshot = DanmakuCommentTrack(comments).snapshotAt(0, isPaused = false, speed = 1.0f)
        assertTrue(snapshot.visible.size <= DanmakuCommentTrack.MAX_LANES)
        assertTrue(snapshot.laneCount <= DanmakuCommentTrack.MAX_LANES)
    }

    @Test
    fun total_visible_never_exceeds_the_cap() {
        // Densely spaced comments streaming across the window stay under the overall cap.
        val comments = (0 until 5_000).map { DanmakuComment(it * (tps / 100), "c$it") }
        val snapshot = DanmakuCommentTrack(comments).snapshotAt(20 * tps, isPaused = false, speed = 1.0f)
        assertTrue(snapshot.visible.size <= DanmakuCommentTrack.MAX_VISIBLE)
    }

    @Test
    fun lane_scale_shrinks_the_effective_budget_but_never_below_one() {
        // 100 comments at the same instant: a small laneScale (tuning's screenFraction) still caps
        // density, just to a smaller budget, and never drops below a single lane/comment.
        val comments = (0 until 100).map { DanmakuComment(0, "c$it") }
        val track = DanmakuCommentTrack(comments)
        val shrunk = track.snapshotAt(0, isPaused = false, speed = 1.0f, laneScale = 0.3f)
        assertTrue(shrunk.visible.size in 1..DanmakuCommentTrack.MAX_LANES)
        assertTrue(shrunk.visible.size < track.snapshotAt(0, isPaused = false, speed = 1.0f).visible.size)
        val tiny = track.snapshotAt(0, isPaused = false, speed = 1.0f, laneScale = 0f)
        assertEquals(1, tiny.visible.size)
    }

    @Test
    fun pinned_comments_are_placed_without_horizontal_lanes() {
        val track = DanmakuCommentTrack(
            listOf(DanmakuComment(0, "top", position = DanmakuPosition.Top)),
        )
        val visible = track.snapshotAt(1 * tps, isPaused = false, speed = 1.0f).visible
        assertEquals(1, visible.size)
        assertEquals(DanmakuPosition.Top, visible.first().comment.position)
    }
}
