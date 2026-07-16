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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import dev.chaichai.mobile.core.contracts.DanmakuController
import dev.chaichai.mobile.core.contracts.DanmakuMatchOption
import dev.chaichai.mobile.core.contracts.DanmakuMatchOptions
import dev.chaichai.mobile.core.contracts.DanmakuMediaKey
import dev.chaichai.mobile.core.contracts.DanmakuPosition
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuTuning
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.PlaybackProgressSync
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackSelection
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.core.contracts.SkipTarget
import dev.chaichai.mobile.core.contracts.SubtitleAppearance
import dev.chaichai.mobile.core.contracts.SubtitleCandidate
import dev.chaichai.mobile.core.contracts.SubtitleColorPreset
import dev.chaichai.mobile.core.contracts.SubtitleEdgeStyle
import dev.chaichai.mobile.core.contracts.SubtitlePosition
import dev.chaichai.mobile.core.contracts.SubtitleProviderController
import dev.chaichai.mobile.core.contracts.SubtitleProviderOutcome
import dev.chaichai.mobile.core.contracts.SubtitleSearchHints
import dev.chaichai.mobile.core.contracts.SubtitleSearchState
import dev.chaichai.mobile.core.contracts.TrackDelivery
import dev.chaichai.mobile.core.contracts.TrackQualifier
import dev.chaichai.mobile.core.contracts.VideoScaleMode
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chaichai.mobile.platform.adaptive.PlaybackSafePane
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
    windowLayout: PlaybackWindowLayout = PlaybackWindowLayout(
        PlaybackSafePane.WholeWindow,
        PlaybackSystemBars.Visible,
    ),
    keepControlsVisible: Boolean = false,
    danmaku: DanmakuController? = null,
    subtitleProvider: SubtitleProviderController? = null,
) {
    val state by coordinator.state.collectAsState()
    if (danmaku != null) {
        DanmakuPlaybackBridge(danmaku, state)
    }
    val playbackKey = (state as? PlaybackState.Active)?.identity?.let { "${it.serverId}:${it.itemId}" } ?: "none"
    var showTracks by rememberSaveable(playbackKey) { mutableStateOf(false) }
    var showDanmakuPanel by rememberSaveable(playbackKey) { mutableStateOf(false) }
    var showSubtitleSearch by rememberSaveable(playbackKey) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is PlaybackState.Exited || state is PlaybackState.Failed) onPlaybackEnded()
    }
    PredictiveBackHandler(enabled = state !is PlaybackState.Idle && state !is PlaybackState.Exited) { progress ->
        progress.collect { }
        when {
            showSubtitleSearch -> showSubtitleSearch = false
            showDanmakuPanel -> showDanmakuPanel = false
            showTracks -> showTracks = false
            else -> coordinator.exit()
        }
    }
    when (val snapshot = state) {
        PlaybackState.Idle, is PlaybackState.Exited -> Unit
        is PlaybackState.Negotiating -> PlaybackLoading(
            snapshot.title, coordinator::exit, windowLayout, modifier,
        )
        is PlaybackState.Active -> PlaybackControls(
            snapshot, coordinator, onToggleOrientation, onToggleFullscreen,
            windowLayout, keepControlsVisible, showTracks, { showTracks = it },
            showDanmakuPanel, { showDanmakuPanel = it }, danmaku,
            subtitleProvider, showSubtitleSearch, { showSubtitleSearch = it }, modifier,
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

/**
 * Drives the [DanmakuController] from the playback state. Danmaku stays independent of media: this
 * only forwards lifecycle/position events; the controller contains any failure internally.
 */
@Composable
private fun DanmakuPlaybackBridge(danmaku: DanmakuController, state: PlaybackState) {
    val active = state as? PlaybackState.Active
    val identity = active?.identity
    val scope = active?.scope
    val seriesIdentity = active?.seriesIdentity
    val seasonNumber = active?.seasonNumber
    val episodeNumber = active?.episodeNumber
    LaunchedEffect(danmaku, identity, scope, seriesIdentity, seasonNumber, episodeNumber) {
        if (active != null && identity != null && scope != null) {
            val mediaKey = if (seriesIdentity != null && seasonNumber != null && episodeNumber != null) {
                DanmakuMediaKey.Episode(identity.serverId, seriesIdentity.itemId, seasonNumber, episodeNumber)
            } else {
                null
            }
            danmaku.attach(identity, scope, active.title, active.runtimeTicks, mediaKey)
        }
    }
    LaunchedEffect(danmaku, active?.positionTicks, active?.isPaused, active?.playbackSpeed) {
        if (active != null) danmaku.onPlayback(active.positionTicks, active.isPaused, active.playbackSpeed)
    }
    DisposableEffect(danmaku) { onDispose { danmaku.detach() } }
}

@Composable
private fun PlaybackControls(
    state: PlaybackState.Active,
    coordinator: PlaybackCoordinator,
    onToggleOrientation: () -> Unit,
    onToggleFullscreen: () -> Unit,
    windowLayout: PlaybackWindowLayout,
    keepControlsVisible: Boolean,
    showTracks: Boolean,
    onShowTracksChanged: (Boolean) -> Unit,
    showDanmakuPanel: Boolean,
    onShowDanmakuPanelChanged: (Boolean) -> Unit,
    danmaku: DanmakuController?,
    subtitleProvider: SubtitleProviderController?,
    showSubtitleSearch: Boolean,
    onShowSubtitleSearchChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    LaunchedEffect(keepControlsVisible, state.controlsVisible) {
        if (keepControlsVisible && !state.controlsVisible) coordinator.toggleControls()
    }
    val danmakuState = danmaku?.state?.collectAsState()?.value
    Box(modifier.fillMaxSize()) {
        if (danmakuState != null && !showTracks) {
            DanmakuOverlay(danmakuState, safePane(windowLayout.safePane))
        }
        Box(
            Modifier.fillMaxSize().then(
                if (showTracks || keepControlsVisible) Modifier else Modifier.clickable(
                    onClickLabel = if (state.controlsVisible) "Hide playback controls" else "Show playback controls",
                    onClick = coordinator::toggleControls,
                ),
            ).testTag("playback-screen"),
        ) {
            if (state.controlsVisible) {
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
            Column(Modifier.fillMaxWidth().testTag("playback-header").semantics { traversalIndex = 0f }) {
                PlaybackHeader(
                    state.title,
                    coordinator::exit,
                    onToggleOrientation,
                    onToggleFullscreen,
                    onTracks = { onShowTracksChanged(true) },
                    danmakuState = danmakuState,
                    onOpenDanmakuPanel = danmaku?.let { { onShowDanmakuPanelChanged(true) } },
                )
                if (danmakuState != null && !showDanmakuPanel) {
                    DanmakuStatusBadge(danmakuState, Modifier.padding(top = 4.dp))
                }
            }
            val skipTarget = state.skipTargets.firstOrNull()
            if (skipTarget != null) {
                // A dedicated Column slot ABOVE the transport row (issue #34 AC3): being its own
                // sibling inside this vertically-stacked Column guarantees it can never overlap
                // "playback-transport" below it, and it already sits inside the safe-drawing-inset
                // Column shared by every other control here, so it stays clear of system/cutout/fold/
                // hinge insets the same way the header and timeline do.
                Box(
                    Modifier.fillMaxWidth().testTag("playback-skip-row"),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    SkipTargetButton(skipTarget, coordinator::skip)
                }
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
                        layout = windowLayout,
                        onDismiss = { onShowTracksChanged(false) },
                        onSelect = coordinator::selectTrack,
                        onSetSpeed = coordinator::setPlaybackSpeed,
                        onAdjustSubtitleDelay = coordinator::setSubtitleDelay,
                        onSetSubtitleAppearance = coordinator::setSubtitleAppearance,
                        onSetVideoScaleMode = coordinator::setVideoScaleMode,
                        onFindSubtitlesOnline = subtitleProvider?.let {
                            {
                                onShowTracksChanged(false)
                                onShowSubtitleSearchChanged(true)
                            }
                        },
                    )
                }
                if (showSubtitleSearch && subtitleProvider != null) {
                    SubtitleSearchSurface(
                        state = state,
                        controller = subtitleProvider,
                        layout = windowLayout,
                        onDismiss = { onShowSubtitleSearchChanged(false) },
                    )
                }
                if (showDanmakuPanel && danmaku != null && danmakuState != null) {
                    DanmakuSurface(
                        danmakuState = danmakuState,
                        matchOptions = danmaku.matchOptions.collectAsState().value,
                        tuning = danmaku.tuning.collectAsState().value,
                        layout = windowLayout,
                        onDismiss = { onShowDanmakuPanelChanged(false) },
                        onSetEnabled = danmaku::setEnabled,
                        onSearch = danmaku::searchMatches,
                        onSelectMatch = danmaku::selectMatch,
                        onClearMatch = danmaku::clearMatch,
                        onUpdateTuning = danmaku::updateTuning,
                    )
                }
            }
        }
        val syncFailure = state.progressSync as? PlaybackProgressSync.Failed
        if (syncFailure != null && !showTracks) {
            ProgressSyncFailure(syncFailure.message, coordinator::retryProgressSync)
        }
    }
}

@Composable
private fun BoxScope.ProgressSyncFailure(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.align(Alignment.TopCenter)
            .padding(top = 72.dp, start = 12.dp, end = 12.dp)
            .fillMaxWidth()
            .testTag("progress-sync-failure"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

/**
 * Skip intro/outro action (issue #34): only ever composed when [PlaybackState.Active.skipTargets]
 * currently offers it, so it appears/disappears cleanly as playback crosses the validated marker's
 * window. A 48dp target with an explicit [Role.Button] and [target]'s user-facing label as its
 * accessible name/announcement, placed by its caller in its own safe-inset Column slot clear of the
 * primary transport row.
 */
@Composable
private fun SkipTargetButton(target: SkipTarget, onSkip: (SkipTarget) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
        TextButton(
            onClick = { onSkip(target) },
            // The tag lives on this TextButton (the actual merge boundary/clickable node), not the
            // wrapping Surface: a testTag on a non-merging ancestor of a merged semantics node is
            // invisible in the MERGED semantics tree that onNodeWithTag searches by default.
            modifier = Modifier.heightIn(min = 48.dp).testTag("skip-${target.kind.name.lowercase(Locale.ROOT)}").semantics {
                role = Role.Button
                contentDescription = target.label
            },
        ) {
            Text(target.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    danmakuState: DanmakuState? = null,
    onOpenDanmakuPanel: (() -> Unit)? = null,
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
            if (danmakuState != null && onOpenDanmakuPanel != null) {
                DanmakuEntryButton(danmakuState, onOpenDanmakuPanel)
            }
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
    layout: PlaybackWindowLayout,
    onDismiss: () -> Unit,
    onSelect: (PlaybackTrackSelection) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onAdjustSubtitleDelay: (Long) -> Unit,
    onSetSubtitleAppearance: (SubtitleAppearance) -> Unit,
    onSetVideoScaleMode: (VideoScaleMode) -> Unit,
    onFindSubtitlesOnline: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(
        safePane(layout.safePane)
            .testTag(
                if (layout.tracksPresentation == PlaybackTracksPresentation.AnchoredSide) {
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
            modifier = if (layout.tracksPresentation == PlaybackTracksPresentation.AnchoredSide) {
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
                    if (state.speedControlSupported) {
                        item { PlaybackSpeedControl(state.playbackSpeed, onSetSpeed) }
                    }
                    if (state.subtitleDelaySupported) {
                        item { SubtitleDelayControl(state.subtitleDelayMillis, onAdjustSubtitleDelay) }
                    }
                    if (state.subtitleAppearanceSupported) {
                        item { SubtitleAppearanceControls(state.subtitleAppearance, onSetSubtitleAppearance) }
                    }
                    if (state.videoScaleModeSupported && state.supportedScaleModes.isNotEmpty()) {
                        item {
                            VideoScaleModeControl(
                                state.videoScaleMode, state.supportedScaleModes, onSetVideoScaleMode,
                            )
                        }
                    }
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
                    if (onFindSubtitlesOnline != null) {
                        item {
                            TextButton(
                                onClick = onFindSubtitlesOnline,
                                modifier = Modifier.heightIn(min = 48.dp).padding(top = 4.dp)
                                    .testTag("find-subtitles-online"),
                            ) { Text("Find subtitles online") }
                        }
                    }
                }
            }
        }
    }
    }
    }
}

/**
 * The single Danmaku entry surface: enable, current match status, manual search + selection, and
 * tuning — mirroring [TracksSurface]'s adaptive bottom/side sheet presentation so it never crowds
 * the essential transport controls.
 */
@Composable
private fun DanmakuSurface(
    danmakuState: DanmakuState,
    matchOptions: DanmakuMatchOptions,
    tuning: DanmakuTuning,
    layout: PlaybackWindowLayout,
    onDismiss: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onSelectMatch: (String) -> Unit,
    onClearMatch: () -> Unit,
    onUpdateTuning: (DanmakuTuning) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(
        safePane(layout.safePane)
            .testTag(
                if (layout.tracksPresentation == PlaybackTracksPresentation.AnchoredSide) {
                    "danmaku-side-sheet"
                } else {
                    "danmaku-bottom-sheet"
                },
            ),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)).semantics {
                contentDescription = "Close Danmaku"
            }.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onDismiss,
            ),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = if (layout.tracksPresentation == PlaybackTracksPresentation.AnchoredSide) {
                Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag("danmaku-panel")
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.86f)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag("danmaku-panel")
            },
        ) {
            LazyColumn(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                item {
                    Text(
                        "Danmaku",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                item { DanmakuEnableRow(danmakuState !is DanmakuState.Disabled, onSetEnabled) }
                item { DanmakuStatusBadge(danmakuState, Modifier.padding(top = 8.dp)) }
                if (danmakuState !is DanmakuState.Disabled) {
                    item { DanmakuMatchSearch(matchOptions, onSearch, onSelectMatch, onClearMatch) }
                    item { DanmakuTuningControls(tuning, onUpdateTuning) }
                }
            }
        }
    }
    }
    }
}

/**
 * The in-player "Find subtitles online" panel (Subtitle Expansion, #32). Searches the configured
 * providers by the current media identity and shows candidates grouped and labeled by provider, each
 * with its language, release/match metadata and provider provenance. Selecting a candidate downloads
 * and activates it as the current subtitle without leaving playback (spinner while activating); a
 * provider or download failure shows a contained, actionable message in a polite live region while the
 * prior subtitle stays active. Mirrors [TracksSurface]'s adaptive bottom/side sheet and safe insets.
 */
@Composable
private fun SubtitleSearchSurface(
    state: PlaybackState.Active,
    controller: SubtitleProviderController,
    layout: PlaybackWindowLayout,
    onDismiss: () -> Unit,
) {
    val searchState by controller.searchState.collectAsState()
    var query by rememberSaveable(state.identity.itemId) { mutableStateOf(state.title) }

    DisposableEffect(state.identity.serverId, state.identity.itemId) {
        controller.searchForCurrentMedia(state.identity, state.scope ?: dev.chaichai.mobile.core.contracts.HomeScope("", ""), hintsFrom(state, query))
        onDispose { controller.cancelSearch() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(
        safePane(layout.safePane).testTag(
            if (layout.tracksPresentation == PlaybackTracksPresentation.AnchoredSide) {
                "subtitle-search-side-sheet"
            } else {
                "subtitle-search-bottom-sheet"
            },
        ),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Close subtitle search",
                role = Role.Button,
                onClick = onDismiss,
            ),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = if (layout.tracksPresentation == PlaybackTracksPresentation.AnchoredSide) {
                Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing).testTag("subtitle-search-panel")
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.86f)
                    .windowInsetsPadding(WindowInsets.safeDrawing).testTag("subtitle-search-panel")
            },
        ) {
            LazyColumn(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                item {
                    Text(
                        "Find subtitles online",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("subtitle-search-field"),
                            label = { Text("Title") },
                        )
                        Button(
                            onClick = {
                                controller.searchForCurrentMedia(
                                    state.identity,
                                    state.scope ?: dev.chaichai.mobile.core.contracts.HomeScope("", ""),
                                    hintsFrom(state, query),
                                )
                            },
                            modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp).testTag("subtitle-search-button"),
                        ) { Text("Search") }
                    }
                }
                subtitleSearchBody(searchState, controller::selectCandidate)
            }
        }
    }
    }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.subtitleSearchBody(
    searchState: SubtitleSearchState,
    onSelect: (String) -> Unit,
) {
    when (searchState) {
        is SubtitleSearchState.Idle -> Unit
        is SubtitleSearchState.Searching -> item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    "Searching providers…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        is SubtitleSearchState.Failure -> item {
            Text(
                searchState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp).testTag("subtitle-search-failure")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        is SubtitleSearchState.Results -> {
            searchState.activationError?.let { error ->
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp).testTag("subtitle-activation-error")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            item { SubtitleProviderStatusRow(searchState.providerStatuses) }
            if (searchState.candidates.isEmpty()) {
                item { MissingTracks("No subtitles found for this title.") }
            }
            items(searchState.candidates, key = { it.id }) { candidate ->
                SubtitleCandidateRow(
                    candidate = candidate,
                    activating = searchState.activatingCandidateId == candidate.id,
                    activated = searchState.activatedCandidateId == candidate.id,
                    enabled = searchState.activatingCandidateId == null,
                    onSelect = { onSelect(candidate.id) },
                )
            }
        }
    }
}

@Composable
private fun SubtitleProviderStatusRow(statuses: List<dev.chaichai.mobile.core.contracts.SubtitleProviderStatus>) {
    if (statuses.isEmpty()) return
    val text = statuses.joinToString("  ·  ") { "${it.providerName}: ${outcomeLabel(it.outcome)}" }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp).testTag("subtitle-provider-statuses")
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun outcomeLabel(outcome: SubtitleProviderOutcome): String = when (outcome) {
    SubtitleProviderOutcome.Ok -> "results"
    SubtitleProviderOutcome.Empty -> "no matches"
    SubtitleProviderOutcome.Failed -> "unavailable"
    SubtitleProviderOutcome.TimedOut -> "timed out"
    SubtitleProviderOutcome.AuthFailed -> "sign-in rejected"
}

@Composable
private fun SubtitleCandidateRow(
    candidate: SubtitleCandidate,
    activating: Boolean,
    activated: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("subtitle-candidate-${candidate.id}").clickable(
            enabled = enabled,
            onClickLabel = "Download and use this subtitle",
            role = Role.Button,
            onClick = onSelect,
        ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                subtitleCandidatePrimary(candidate),
                color = if (activated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                subtitleCandidateSecondary(candidate),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (activating) {
            CircularProgressIndicator(
                Modifier.size(20.dp).semantics { liveRegion = LiveRegionMode.Polite },
                strokeWidth = 2.dp,
            )
        } else if (activated) {
            Text("Current", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun subtitleCandidatePrimary(candidate: SubtitleCandidate): String = buildList {
    add((candidate.languageLabel ?: displayLanguage(candidate.language)))
    if (candidate.hearingImpaired) add("Hearing impaired")
    add(candidate.format.uppercase(Locale.ROOT))
}.joinToString(" · ")

private fun subtitleCandidateSecondary(candidate: SubtitleCandidate): String = buildList {
    candidate.releaseName?.let(::add)
    candidate.matchInfo?.let(::add)
    add("via ${candidate.providerName}")
}.joinToString(" · ")

private fun hintsFrom(state: PlaybackState.Active, title: String): SubtitleSearchHints = SubtitleSearchHints(
    title = title.ifBlank { state.title },
    season = state.seasonNumber,
    episode = state.episodeNumber,
    runtimeTicks = state.runtimeTicks.takeIf { it > 0 },
)

@Composable
private fun DanmakuEnableRow(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Enable danmaku", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = enabled,
            onCheckedChange = onSetEnabled,
            modifier = Modifier.testTag("danmaku-enable-switch").semantics {
                contentDescription = if (enabled) "Turn danmaku off" else "Turn danmaku on"
            },
        )
    }
}

@Composable
private fun DanmakuMatchSearch(
    matchOptions: DanmakuMatchOptions,
    onSearch: (String) -> Unit,
    onSelectMatch: (String) -> Unit,
    onClearMatch: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(matchOptions.query) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            "Wrong match? Search",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("danmaku-search-field"),
                label = { Text("Title") },
            )
            Button(
                onClick = { onSearch(query) },
                modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp).testTag("danmaku-search-button"),
            ) { Text("Search") }
        }
        if (matchOptions.isSearching) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    "Searching…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        val searchError = matchOptions.error
        if (searchError != null) {
            Text(
                searchError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        matchOptions.results.forEach { option ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("danmaku-match-${option.candidateId}").clickable(
                    onClickLabel = "Use this match",
                    role = Role.Button,
                    onClick = { onSelectMatch(option.candidateId) },
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    matchOptionLabel(option),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        TextButton(
            onClick = onClearMatch,
            modifier = Modifier.heightIn(min = 48.dp).testTag("danmaku-clear-match"),
        ) { Text("Clear remembered match") }
    }
}

private fun matchOptionLabel(option: DanmakuMatchOption): String = buildList {
    add(option.title)
    option.season?.let { add("S$it") }
    option.episode?.let { add("E$it") }
}.joinToString(" · ")

@Composable
private fun DanmakuTuningControls(
    tuning: DanmakuTuning,
    onUpdateTuning: (DanmakuTuning) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("danmaku-tuning")) {
        Text(
            "Tuning",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        DanmakuTuningSlider(
            label = "Timing offset",
            valueText = "${tuning.timingOffsetMillis} ms",
            value = tuning.timingOffsetMillis.toFloat(),
            range = -10_000f..10_000f,
            testTag = "danmaku-tuning-offset",
            onValueChange = { onUpdateTuning(tuning.copy(timingOffsetMillis = it.toLong())) },
        )
        DanmakuTuningSlider(
            label = "Comment speed",
            valueText = formatMultiplier(tuning.speed),
            value = tuning.speed,
            range = 0.5f..2f,
            testTag = "danmaku-tuning-speed",
            onValueChange = { onUpdateTuning(tuning.copy(speed = it)) },
        )
        DanmakuTuningSlider(
            label = "Text size",
            valueText = formatMultiplier(tuning.textScale),
            value = tuning.textScale,
            range = 0.75f..1.5f,
            testTag = "danmaku-tuning-size",
            onValueChange = { onUpdateTuning(tuning.copy(textScale = it)) },
        )
        DanmakuTuningSlider(
            label = "Opacity",
            valueText = "${(tuning.opacity * 100).toInt()}%",
            value = tuning.opacity,
            range = 0.3f..1f,
            testTag = "danmaku-tuning-opacity",
            onValueChange = { onUpdateTuning(tuning.copy(opacity = it)) },
        )
        DanmakuTuningSlider(
            label = "Screen area",
            valueText = "${(tuning.screenFraction * 100).toInt()}%",
            value = tuning.screenFraction,
            range = 0.3f..1f,
            testTag = "danmaku-tuning-fraction",
            onValueChange = { onUpdateTuning(tuning.copy(screenFraction = it)) },
        )
        Text(
            "Comment positions",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        DanmakuPosition.entries.forEach { position ->
            val checked = position in tuning.allowedPositions
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
                    onClickLabel = if (checked) "Hide $position comments" else "Show $position comments",
                    role = Role.Checkbox,
                    onClick = {
                        val updated = if (checked) tuning.allowedPositions - position else tuning.allowedPositions + position
                        onUpdateTuning(tuning.copy(allowedPositions = updated))
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(position.name, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun DanmakuTuningSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("$testTag-value").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag).semantics {
                contentDescription = "$label $valueText"
            },
        )
    }
}

private fun formatMultiplier(value: Float): String {
    val rounded = kotlin.math.round(value * 100) / 100.0
    return "${rounded}×"
}

private fun BoxScope.safePane(pane: PlaybackSafePane): Modifier = when (pane) {
    PlaybackSafePane.WholeWindow -> Modifier.fillMaxSize()
    is PlaybackSafePane.Left -> Modifier.align(AbsoluteAlignment.CenterLeft).width(pane.widthDp.dp).fillMaxHeight()
    is PlaybackSafePane.Right -> Modifier.align(AbsoluteAlignment.CenterRight).width(pane.widthDp.dp).fillMaxHeight()
    is PlaybackSafePane.Top -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(pane.heightDp.dp)
    is PlaybackSafePane.Bottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(pane.heightDp.dp)
}

private val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
private const val SUBTITLE_DELAY_STEP_MILLIS = 250L

@Composable
private fun PlaybackSpeedControl(speed: Float, onSelect: (Float) -> Unit) {
    val index = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
        .let { if (it == -1) SPEED_STEPS.indexOf(1.0f) else it }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("playback-speed-control")) {
        Text(
            "Playback speed",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (index > 0) onSelect(SPEED_STEPS[index - 1]) },
                enabled = index > 0,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Remove, "Decrease playback speed", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                formatSpeed(speed),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(
                onClick = { if (index < SPEED_STEPS.lastIndex) onSelect(SPEED_STEPS[index + 1]) },
                enabled = index < SPEED_STEPS.lastIndex,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Add, "Increase playback speed", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Aspect/resize control (Playback Polish, #35): ONLY composed above when both
 * [PlaybackState.Active.videoScaleModeSupported] is true AND [PlaybackState.Active.supportedScaleModes]
 * is non-empty, and only ever offers the modes IN [supportedScaleModes] — an engine that cannot apply a
 * mode trustworthily never gets a row for it here, so there is no disabled/failing state to reach
 * (AC2). Reuses the same accessible choice-row shape as [SubtitleAppearanceControls] (48dp targets,
 * [Role.RadioButton], live-region value announcement) and applies the selection immediately through
 * [onSelect] with no local draft — the coordinator republishes the applied value.
 */
@Composable
private fun VideoScaleModeControl(
    selected: VideoScaleMode,
    supportedScaleModes: List<VideoScaleMode>,
    onSelect: (VideoScaleMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("video-scale-mode-control")) {
        SubtitleAppearanceChoiceRow(
            label = "Video fit",
            options = supportedScaleModes,
            selected = selected,
            optionLabel = ::videoScaleModeLabel,
            testTagPrefix = "video-scale-mode",
            onSelect = onSelect,
        )
    }
}

private fun videoScaleModeLabel(mode: VideoScaleMode): String = when (mode) {
    VideoScaleMode.Fit -> "Fit"
    VideoScaleMode.Fill -> "Fill"
    VideoScaleMode.Zoom -> "Zoom"
}

@Composable
private fun SubtitleDelayControl(delayMillis: Long, onAdjust: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("subtitle-delay-control")) {
        Text(
            "Subtitle delay",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onAdjust(-SUBTITLE_DELAY_STEP_MILLIS) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Remove, "Decrease subtitle delay", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                formatDelay(delayMillis),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(
                onClick = { onAdjust(SUBTITLE_DELAY_STEP_MILLIS) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Add, "Increase subtitle delay", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Present provider subtitles accessibly (#33): live-applied size/position/color-style/opacity controls.
 * Every control reads directly from and writes directly through [appearance]/[onSet] — the coordinator
 * publishes the applied value back on [PlaybackState.Active.subtitleAppearance] (persisted per the
 * documented server+user scope), so there is no separate local draft state to lose on recreation.
 * Only shown when [PlaybackState.Active.subtitleAppearanceSupported] gates it (AC1/AC2); each row keeps
 * a 48dp target, a [Role], and a polite live region announcing the new value (AC5 TalkBack/large-text).
 */
@Composable
private fun SubtitleAppearanceControls(appearance: SubtitleAppearance, onSet: (SubtitleAppearance) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("subtitle-appearance-controls")) {
        Text(
            "Subtitle appearance",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        SubtitleAppearanceSlider(
            label = "Text size",
            valueText = "${(appearance.textScale * 100).toInt()}%",
            value = appearance.textScale,
            range = SubtitleAppearance.MinTextScale..SubtitleAppearance.MaxTextScale,
            testTag = "subtitle-text-size-slider",
            onValueChange = { onSet(appearance.copy(textScale = it)) },
        )
        SubtitleAppearanceChoiceRow(
            label = "Position",
            options = SubtitlePosition.entries,
            selected = appearance.position,
            optionLabel = ::subtitlePositionLabel,
            testTagPrefix = "subtitle-position",
            onSelect = { onSet(appearance.copy(position = it)) },
        )
        SubtitleAppearanceChoiceRow(
            label = "Color",
            options = SubtitleColorPreset.entries,
            selected = appearance.colorPreset,
            optionLabel = ::subtitleColorPresetLabel,
            testTagPrefix = "subtitle-color",
            onSelect = { onSet(appearance.copy(colorPreset = it)) },
        )
        SubtitleAppearanceChoiceRow(
            label = "Edge style",
            options = SubtitleEdgeStyle.entries,
            selected = appearance.edgeStyle,
            optionLabel = ::subtitleEdgeStyleLabel,
            testTagPrefix = "subtitle-edge",
            onSelect = { onSet(appearance.copy(edgeStyle = it)) },
        )
        SubtitleAppearanceSlider(
            label = "Background opacity",
            valueText = "${(appearance.windowOpacity * 100).toInt()}%",
            value = appearance.windowOpacity,
            range = 0.3f..1f,
            testTag = "subtitle-opacity-slider",
            onValueChange = { onSet(appearance.copy(windowOpacity = it)) },
        )
    }
}

@Composable
private fun SubtitleAppearanceSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag).semantics {
                contentDescription = "$label $valueText"
            },
        )
    }
}

@Composable
private fun <T> SubtitleAppearanceChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    testTagPrefix: String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = option == selected
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ).testTag("$testTagPrefix-$option").padding(4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        optionLabel(option),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun subtitlePositionLabel(position: SubtitlePosition): String = when (position) {
    SubtitlePosition.BottomDefault -> "Bottom"
    SubtitlePosition.Lower -> "Lower"
    SubtitlePosition.Upper -> "Upper"
}

private fun subtitleColorPresetLabel(preset: SubtitleColorPreset): String = when (preset) {
    SubtitleColorPreset.WhiteOnBlack -> "White"
    SubtitleColorPreset.YellowOnBlack -> "Yellow"
    SubtitleColorPreset.BlackOnWhite -> "Black on white"
}

private fun subtitleEdgeStyleLabel(style: SubtitleEdgeStyle): String = when (style) {
    SubtitleEdgeStyle.None -> "None"
    SubtitleEdgeStyle.Outline -> "Outline"
    SubtitleEdgeStyle.DropShadow -> "Drop shadow"
}

private fun formatSpeed(speed: Float): String {
    val rounded = kotlin.math.round(speed * 100) / 100.0
    val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    return "${text}×"
}

private fun formatDelay(delayMillis: Long): String = when {
    delayMillis == 0L -> "0 ms"
    delayMillis > 0 -> "+$delayMillis ms"
    else -> "$delayMillis ms"
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
