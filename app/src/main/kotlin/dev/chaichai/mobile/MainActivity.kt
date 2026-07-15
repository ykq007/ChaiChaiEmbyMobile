package dev.chaichai.mobile

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.view.accessibility.AccessibilityManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.platform.adaptive.PlaybackSystemBars
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var boundaries: dev.chaichai.mobile.core.contracts.AppBoundaries
    private var userRequestedFullscreen = false
    private var adaptiveSystemBars = PlaybackSystemBars.Visible
    private var notificationPermissionRequested = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Playback remains available when notification visibility is denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val layoutInfo by WindowInfoTracker.getOrCreate(this)
                .windowLayoutInfo(this)
                .collectAsState(initial = null)
            val separatingHinge = layoutInfo?.displayFeatures
                ?.filterIsInstance<FoldingFeature>()
                ?.firstOrNull { it.isSeparating }
            val playbackState by boundaries.playback.state.collectAsState()
            androidx.compose.runtime.LaunchedEffect(playbackState is dev.chaichai.mobile.core.contracts.PlaybackState.Active) {
                if (playbackState is dev.chaichai.mobile.core.contracts.PlaybackState.Active) {
                    requestPlaybackNotificationPermission()
                }
            }
            val accessibilityManager = remember {
                getSystemService(AccessibilityManager::class.java)
            }
            var touchExplorationEnabled by remember {
                mutableStateOf(accessibilityManager.isTouchExplorationEnabled)
            }
            DisposableEffect(accessibilityManager) {
                val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
                    touchExplorationEnabled = enabled
                }
                accessibilityManager.addTouchExplorationStateChangeListener(listener)
                onDispose { accessibilityManager.removeTouchExplorationStateChangeListener(listener) }
            }

            ChaiChaiTheme(reducedMotion = !ValueAnimator.areAnimatorsEnabled()) {
                MobileApp(
                    boundaries = boundaries,
                    separatingHinge = separatingHinge?.let { feature ->
                        SeparatingHinge(
                            leftPx = feature.bounds.left,
                            topPx = feature.bounds.top,
                            rightPx = feature.bounds.right,
                            bottomPx = feature.bounds.bottom,
                            orientation = if (feature.orientation == FoldingFeature.Orientation.VERTICAL) {
                                HingeOrientation.Vertical
                            } else {
                                HingeOrientation.Horizontal
                            },
                        )
                    },
                    onTogglePlaybackOrientation = ::togglePlaybackOrientation,
                    onTogglePlaybackFullscreen = ::togglePlaybackFullscreen,
                    onPlaybackEnded = ::restorePlaybackWindow,
                    onPlaybackSystemBarsChanged = ::setPlaybackSystemBars,
                    keepPlaybackControlsVisible = touchExplorationEnabled,
                )
            }
        }
    }

    private fun togglePlaybackOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun togglePlaybackFullscreen() {
        userRequestedFullscreen = !userRequestedFullscreen
        updatePlaybackSystemBars()
    }

    private fun setPlaybackSystemBars(systemBars: PlaybackSystemBars) {
        if (adaptiveSystemBars == systemBars) return
        adaptiveSystemBars = systemBars
        updatePlaybackSystemBars()
    }

    private fun restorePlaybackWindow() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        userRequestedFullscreen = false
        adaptiveSystemBars = PlaybackSystemBars.Visible
        updatePlaybackSystemBars()
    }

    private fun updatePlaybackSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (userRequestedFullscreen || adaptiveSystemBars == PlaybackSystemBars.Immersive) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun requestPlaybackNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionRequested) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
