package dev.chaichai.mobile.feature.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.CommentsDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.DanmakuPosition
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuVisibleComment

/** Wall-clock seconds a scroll comment takes to cross; mirrors the platform track's default. */
private const val SCROLL_WINDOW_SECONDS = 8.0
private const val TICKS_PER_SECOND = 10_000_000L
private val LANE_HEIGHT = 30.dp

/**
 * Renders the currently-visible danmaku comments as a Compose overlay. It is drawn BEHIND the
 * controls (the caller stacks it first) and is inset from [WindowInsets.safeDrawing] plus the given
 * [modifier] safe pane, so it stays clear of controls, insets, folds and hinges. Motion is
 * interpolated per frame from the position-derived snapshot and scales with playback speed; a paused
 * snapshot simply freezes.
 */
@Composable
internal fun DanmakuOverlay(state: DanmakuState, modifier: Modifier = Modifier) {
    val active = state as? DanmakuState.Active ?: return
    val snapshot = active.overlay
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("danmaku-overlay"),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val laneHeightPx = with(density) { LANE_HEIGHT.toPx() }
        val bottomPx = with(density) { maxHeight.toPx() }
        val scrollWindowTicks = (SCROLL_WINDOW_SECONDS * snapshot.speed * TICKS_PER_SECOND).toLong().coerceAtLeast(1L)

        var frameNanos by remember(snapshot) { mutableStateOf(0L) }
        val baseNanos = remember(snapshot) { System.nanoTime() }
        LaunchedEffect(snapshot) {
            while (true) {
                frameNanos = withFrameNanos { it }
                if (snapshot.isPaused) break
            }
        }
        val elapsedSeconds = if (snapshot.isPaused) 0.0 else ((frameNanos - baseNanos).coerceAtLeast(0L)) / 1_000_000_000.0
        val livePositionTicks = snapshot.positionTicks + (elapsedSeconds * snapshot.speed * TICKS_PER_SECOND).toLong()

        // Reserve a band away from the very top (header) and very bottom (timeline) controls.
        val topBandPx = laneHeightPx
        for (visible in snapshot.visible) {
            val xFraction = liveXFraction(visible, livePositionTicks, scrollWindowTicks)
                ?: continue
            val x = (xFraction * widthPx).toInt()
            val y = when (visible.comment.position) {
                DanmakuPosition.Scroll, DanmakuPosition.Top ->
                    (topBandPx + visible.lane * laneHeightPx).toInt()
                DanmakuPosition.Bottom ->
                    (bottomPx - laneHeightPx * 2 - visible.lane * laneHeightPx).coerceAtLeast(topBandPx).toInt()
            }
            DanmakuCommentLabel(visible, Modifier.offset { IntOffset(x, y) })
        }
    }
}

/** X as a fraction of width (1f = right edge, 0f = left). Null means the comment has left the screen. */
private fun liveXFraction(
    visible: DanmakuVisibleComment,
    livePositionTicks: Long,
    scrollWindowTicks: Long,
): Float? {
    if (visible.comment.position != DanmakuPosition.Scroll) {
        // Pinned comments hold centre-ish; only fade out is handled by the snapshot lifetime.
        return 0.35f
    }
    val progress = (livePositionTicks - visible.comment.timeTicks).toFloat() / scrollWindowTicks
    if (progress < 0f || progress > 1f) return null
    return 1f - progress
}

@Composable
private fun DanmakuCommentLabel(visible: DanmakuVisibleComment, modifier: Modifier) {
    Text(
        text = visible.comment.text,
        color = Color(visible.comment.colorArgb),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

/**
 * The single minimal danmaku entry affordance for the controls (full configuration is a later
 * capability). Toggles danmaku on/off and exposes a stable [testTag].
 */
@Composable
internal fun DanmakuToggleButton(state: DanmakuState, onSetEnabled: (Boolean) -> Unit) {
    val enabled = state !is DanmakuState.Disabled
    IconButton(
        onClick = { onSetEnabled(!enabled) },
        modifier = Modifier
            .testTag("danmaku-toggle")
            .semantics { contentDescription = if (enabled) "Turn danmaku off" else "Turn danmaku on" },
    ) {
        Icon(
            imageVector = if (enabled) Icons.Default.Comment else Icons.Default.CommentsDisabled,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A contained, actionable status line for danmaku. Never surfaced as a playback failure. */
@Composable
internal fun DanmakuStatusBadge(state: DanmakuState, modifier: Modifier = Modifier) {
    val message = when (state) {
        is DanmakuState.Disabled -> null
        is DanmakuState.Matching -> "Matching danmaku…"
        is DanmakuState.Active -> "Danmaku on · ${state.endpointName}"
        is DanmakuState.Unavailable -> state.message
    } ?: return
    Surface(
        modifier = modifier.testTag("danmaku-status"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
