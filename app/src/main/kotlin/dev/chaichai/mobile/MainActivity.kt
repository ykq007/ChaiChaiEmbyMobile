package dev.chaichai.mobile

import android.animation.ValueAnimator
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var boundaries: dev.chaichai.mobile.core.contracts.AppBoundaries
    private var playbackFullscreen = false

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
        playbackFullscreen = !playbackFullscreen
        WindowCompat.getInsetsController(window, window.decorView).run {
            if (playbackFullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
