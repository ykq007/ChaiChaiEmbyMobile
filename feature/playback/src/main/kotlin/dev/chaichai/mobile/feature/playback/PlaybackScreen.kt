package dev.chaichai.mobile.feature.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackState
import androidx.activity.compose.BackHandler

@Composable
fun PlaybackHost(
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    onToggleOrientation: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
) {
    val state by coordinator.state.collectAsState()
    BackHandler(enabled = state !is PlaybackState.Idle && state !is PlaybackState.Exited) { coordinator.exit() }
    when (val snapshot = state) {
        PlaybackState.Idle, is PlaybackState.Exited -> Unit
        is PlaybackState.Negotiating -> PlaybackLoading(snapshot.title, coordinator::exit, modifier)
        is PlaybackState.Active -> PlaybackControls(snapshot, coordinator, onToggleOrientation, onToggleFullscreen, modifier)
        is PlaybackState.Failed -> PlaybackFailure(snapshot, coordinator, modifier)
    }
}

@Composable
private fun PlaybackLoading(title: String, onBack: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        BackButton(onBack, Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(if (title.isBlank()) "Preparing playback" else "Preparing $title", color = Color.White)
        }
    }
}

@Composable
private fun PlaybackControls(
    state: PlaybackState.Active,
    coordinator: PlaybackCoordinator,
    onToggleOrientation: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier.fillMaxSize().clickable(
            onClickLabel = if (state.controlsVisible) "Hide playback controls" else "Show playback controls",
            onClick = coordinator::toggleControls,
        ).testTag("playback-screen"),
    ) {
        if (!state.controlsVisible) return@Box
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BackButton(coordinator::exit)
                Text(state.title.ifBlank { "Now playing" }, color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleOrientation, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.ScreenRotation, "Change orientation", tint = Color.White)
                }
                IconButton(onClick = onToggleFullscreen, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                }
            }
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton({ coordinator.seekBy(-10 * TICKS_PER_SECOND) }, Modifier.heightIn(min = 64.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Rewind 10 seconds", tint = Color.White)
                }
                IconButton(coordinator::playPause, Modifier.heightIn(min = 64.dp)) {
                    Icon(if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, if (state.isPaused) "Play" else "Pause", tint = Color.White)
                }
                IconButton({ coordinator.seekBy(30 * TICKS_PER_SECOND) }, Modifier.heightIn(min = 64.dp)) {
                    Icon(Icons.Default.SkipNext, "Forward 30 seconds", tint = Color.White)
                }
            }
            Column {
                Slider(
                    value = state.positionTicks.toFloat(),
                    onValueChange = { coordinator.seekTo(it.toLong()) },
                    valueRange = 0f..state.runtimeTicks.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Playback position ${formatTicks(state.positionTicks)} of ${formatTicks(state.runtimeTicks)}"
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTicks(state.positionTicks), color = Color.White)
                    Text("−${formatTicks((state.runtimeTicks - state.positionTicks).coerceAtLeast(0))}", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PlaybackFailure(state: PlaybackState.Failed, coordinator: PlaybackCoordinator, modifier: Modifier) {
    val (title, guidance) = when (state.reason) {
        dev.chaichai.mobile.core.contracts.PlaybackFailureKind.UnsupportedMedia -> "Unsupported media" to "This media has no compatible stream."
        dev.chaichai.mobile.core.contracts.PlaybackFailureKind.TranscodingRefused -> "Transcoding refused" to "The server did not allow the required conversion."
        dev.chaichai.mobile.core.contracts.PlaybackFailureKind.SourceUnavailable -> "Source unavailable" to "The selected media source is no longer available."
        dev.chaichai.mobile.core.contracts.PlaybackFailureKind.AuthorizationExpired -> "Sign-in expired" to "Return and sign in again."
        dev.chaichai.mobile.core.contracts.PlaybackFailureKind.Network -> "Network unavailable" to "Check the connection and try again."
    }
    Column(
        modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text(guidance, color = Color.White, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = if (state.reason.canRetry) coordinator::retry else coordinator::exit) {
            Text(if (state.reason.canRetry) "Retry" else "Back")
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to details", tint = Color.White)
    }
}

private fun formatTicks(ticks: Long): String {
    val seconds = ticks / TICKS_PER_SECOND
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val TICKS_PER_SECOND = 10_000_000L
