package dev.chaichai.mobile

import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

abstract class NoOpPlaybackCoordinator : PlaybackCoordinator {
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
    override fun submit(request: MediaPlaybackRequest) = Unit
    override fun toggleControls() = Unit
    override fun playPause() = Unit
    override fun seekBy(deltaTicks: Long) = Unit
    override fun seekTo(positionTicks: Long) = Unit
    override fun retry() = Unit
    override fun exit() = Unit
}
