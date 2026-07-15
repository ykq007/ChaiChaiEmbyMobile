package dev.chaichai.mobile.feature.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.core.contracts.TrackDelivery
import dev.chaichai.mobile.core.contracts.TrackQualifier
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chaichai.mobile.platform.adaptive.PlaybackSafePane
import dev.chaichai.mobile.platform.adaptive.PlaybackTracksLayout
import dev.chaichai.mobile.platform.adaptive.PlaybackTracksPresentation
import dev.chaichai.mobile.platform.adaptive.PlaybackWindowLayout
import dev.chaichai.mobile.platform.adaptive.PlaybackSystemBars
import java.util.Locale

@Composable
fun PlaybackHost(
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    onToggleOrientation: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    tracksLayout: PlaybackTracksLayout = PlaybackTracksLayout(
        PlaybackTracksPresentation.ModalBottom,
        PlaybackSafePane.WholeWindow,
    ),
    windowLayout: PlaybackWindowLayout = PlaybackWindowLayout(
        PlaybackSafePane.WholeWindow,
        PlaybackSystemBars.Visible,
    ),
    keepControlsVisible: Boolean = false,
) {
    val state by coordinator.state.collectAsState()
    val playbackKey = (state as? PlaybackState.Active)?.identity?.let { "${it.serverId}:${it.itemId}" } ?: "none"
    var showTracks by rememberSaveable(playbackKey) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is PlaybackState.Exited || state is PlaybackState.Failed) onPlaybackEnded()
    }
    DisposableEffect(Unit) { onDispose(onPlaybackEnded) }
    PredictiveBackHandler(enabled = state !is PlaybackState.Idle && state !is PlaybackState.Exited) { progress ->
        progress.collect { }
        if (showTracks) showTracks = false else coordinator.exit()
    }
    when (val snapshot = state) {
        PlaybackState.Idle, is PlaybackState.Exited -> Unit
        is PlaybackState.Negotiating -> PlaybackLoading(
            snapshot.title, coordinator::exit, windowLayout, modifier,
        )
        is PlaybackState.Active -> PlaybackControls(
            snapshot, coordinator, onToggleOrientation, onToggleFullscreen, tracksLayout,
            windowLayout, keepControlsVisible, showTracks, { showTracks = it }, modifier,
        )
        is PlaybackState.Failed -> PlaybackFailure(snapshot, coordinator, modifier)
    }
}

@Composable
private fun PlaybackLoading(
    title: String,
    onBack: () -> Unit,
    windowLayout: PlaybackWindowLayout,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(safePane(windowLayout.safePane).windowInsetsPadding(WindowInsets.safeDrawing)) {
            BackButton(onBack, Modifier.align(Alignment.TopStart))
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(if (title.isBlank()) "Preparing playback" else "Preparing $title", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    state: PlaybackState.Active,
    coordinator: PlaybackCoordinator,
    onToggleOrientation: () -> Unit,
    onToggleFullscreen: () -> Unit,
    tracksLayout: PlaybackTracksLayout,
    windowLayout: PlaybackWindowLayout,
    keepControlsVisible: Boolean,
    showTracks: Boolean,
    onShowTracksChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    LaunchedEffect(keepControlsVisible, state.controlsVisible) {
        if (keepControlsVisible && !state.controlsVisible) coordinator.toggleControls()
    }
    Box(
        modifier.fillMaxSize().then(
            if (showTracks || keepControlsVisible) Modifier else Modifier.clickable(
                onClickLabel = if (state.controlsVisible) "Hide playback controls" else "Show playback controls",
                onClick = coordinator::toggleControls,
            ),
        ).testTag("playback-screen"),
    ) {
        if (!state.controlsVisible) return@Box
        Column(
            safePane(windowLayout.safePane)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .testTag("playback-controls")
                .semantics { isTraversalGroup = true }
                .then(
                if (showTracks) Modifier.clearAndSetSemantics { } else Modifier,
            ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.fillMaxWidth().testTag("playback-header").semantics { traversalIndex = 0f }) {
                PlaybackHeader(
                    state.title,
                    coordinator::exit,
                    onToggleOrientation,
                    onToggleFullscreen,
                    onTracks = { onShowTracksChanged(true) },
                )
            }
            Box(Modifier.fillMaxWidth().testTag("playback-transport").semantics { traversalIndex = 1f }) {
                PrimaryTransport(state, coordinator)
            }
            Column(Modifier.testTag("playback-timeline").semantics { traversalIndex = 2f }) {
                Slider(
                    value = state.positionTicks.toFloat(),
                    onValueChange = { coordinator.seekTo(it.toLong()) },
                    valueRange = 0f..state.runtimeTicks.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Playback position ${formatTicks(state.positionTicks)} of ${formatTicks(state.runtimeTicks)}"
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTicks(state.positionTicks), color = MaterialTheme.colorScheme.onSurface)
                    Text("−${formatTicks((state.runtimeTicks - state.positionTicks).coerceAtLeast(0))}", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (showTracks) {
            TracksSurface(
                state = state,
                layout = tracksLayout,
                onDismiss = { onShowTracksChanged(false) },
                onSelect = coordinator::selectTrack,
            )
        }
    }
}

@Composable
private fun PrimaryTransport(state: PlaybackState.Active, coordinator: PlaybackCoordinator) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                if (maxWidth < 240.dp) 0.dp else 28.dp,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ coordinator.seekBy(-10 * TICKS_PER_SECOND) }, Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "Rewind 10 seconds", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(coordinator::playPause, Modifier.size(48.dp)) {
                Icon(
                    if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    if (state.isPaused) "Play" else "Pause",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton({ coordinator.seekBy(30 * TICKS_PER_SECOND) }, Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, "Forward 30 seconds", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun PlaybackHeader(
    title: String,
    onBack: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onTracks: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val titleContent: @Composable (Modifier) -> Unit = { titleModifier ->
            Text(
                title.ifBlank { "Now playing" },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
        }
        val secondaryControls: @Composable () -> Unit = {
            IconButton(onClick = onToggleOrientation, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ScreenRotation, "Change orientation", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Fullscreen, "Fullscreen", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onTracks, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Subtitles, "Tracks", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (maxWidth < 360.dp) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(onBack)
                    titleContent(Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { secondaryControls() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                titleContent(Modifier.weight(1f))
                secondaryControls()
            }
        }
    }
}

@Composable
private fun TracksSurface(
    state: PlaybackState.Active,
    layout: PlaybackTracksLayout,
    onDismiss: () -> Unit,
    onSelect: (PlaybackTrackSelection) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(
        safePane(layout.safePane)
            .testTag(
                if (layout.presentation == PlaybackTracksPresentation.AnchoredSide) {
                    "tracks-side-sheet"
                } else {
                    "tracks-bottom-sheet"
                },
            ),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Close Tracks",
                role = Role.Button,
                onClick = onDismiss,
            ),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = if (layout.presentation == PlaybackTracksPresentation.AnchoredSide) {
                Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.78f)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            },
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Tracks", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                val trackChangeError = state.trackChangeError
                if (trackChangeError != null) {
                    Text(
                        trackChangeError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (state.isChangingTrack) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Applying track…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    val currentSelection = PlaybackTrackSelection(
                        audioStreamIndex = state.audioTracks.firstOrNull { it.isCurrent }?.index,
                        subtitleStreamIndex = state.subtitleTracks.firstOrNull { it.isCurrent }?.index,
                    )
                    item { TrackSectionHeading("Audio") }
                    if (state.audioTracks.isEmpty()) {
                        item { MissingTracks("No audio tracks available") }
                    } else {
                        items(state.audioTracks, key = { "audio-${it.index}" }) { track ->
                            TrackRow(trackLabel(track), track.isCurrent, state.isChangingTrack) {
                                onSelect(
                                    currentSelection.copy(audioStreamIndex = track.index),
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)); TrackSectionHeading("Subtitles") }
                    item {
                        val isOff = state.subtitleTracks.none { it.isCurrent }
                        TrackRow(if (isOff) "Off · Current" else "Off", isOff, state.isChangingTrack) {
                            onSelect(
                                currentSelection.copy(subtitleStreamIndex = null),
                            )
                        }
                    }
                    items(state.subtitleTracks, key = { "subtitle-${it.index}" }) { track ->
                        TrackRow(trackLabel(track), track.isCurrent, state.isChangingTrack) {
                            onSelect(
                                currentSelection.copy(subtitleStreamIndex = track.index),
                            )
                        }
                    }
                    if (state.subtitleTracks.isEmpty()) {
                        item { MissingTracks("No subtitle streams available") }
                    }
                }
            }
        }
    }
    }
    }
}

private fun BoxScope.safePane(pane: PlaybackSafePane): Modifier = when (pane) {
    PlaybackSafePane.WholeWindow -> Modifier.fillMaxSize()
    is PlaybackSafePane.Left -> Modifier.align(AbsoluteAlignment.CenterLeft).width(pane.widthDp.dp).fillMaxHeight()
    is PlaybackSafePane.Right -> Modifier.align(AbsoluteAlignment.CenterRight).width(pane.widthDp.dp).fillMaxHeight()
    is PlaybackSafePane.Top -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(pane.heightDp.dp)
    is PlaybackSafePane.Bottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(pane.heightDp.dp)
}

@Composable
private fun TrackSectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
    )
}

@Composable
private fun MissingTracks(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, changing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
            selected = selected,
            enabled = !changing,
            role = Role.RadioButton,
            onClick = onClick,
        ).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun trackLabel(track: PlaybackTrack): String = buildList {
    track.language?.let { add(displayLanguage(it)) }
    track.codec?.let { add(it.uppercase(Locale.ROOT)) }
    track.title?.let(::add)
    when (track.delivery) {
        TrackDelivery.Embedded -> if (track.type == PlaybackTrackType.Subtitle) add("Embedded")
        TrackDelivery.External -> add("External")
        TrackDelivery.BurnIn -> add("Burn-in required")
    }
    track.qualifiers.forEach {
        add(
            when (it) {
                TrackQualifier.HearingImpaired -> "Hearing impaired"
                TrackQualifier.Commentary -> "Commentary"
                TrackQualifier.VisuallyImpaired -> "Audio description"
            },
        )
    }
    if (track.isDefault) add("Default")
    if (track.isCurrent) add("Current")
}.ifEmpty { listOf("Track ${track.index}") }.joinToString(" · ")

private fun displayLanguage(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
    ?: code

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
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineMedium)
        Text(guidance, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = if (state.reason.canRetry) coordinator::retry else coordinator::exit) {
            Text(if (state.reason.canRetry) "Retry" else "Back")
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to details", tint = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatTicks(ticks: Long): String {
    val seconds = ticks / TICKS_PER_SECOND
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val TICKS_PER_SECOND = 10_000_000L
