package dev.chaichai.mobile

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val layoutInfo by WindowInfoTracker.getOrCreate(this)
                .windowLayoutInfo(this)
                .collectAsState(initial = null)
            val separatingVerticalHinge = layoutInfo?.displayFeatures
                ?.filterIsInstance<FoldingFeature>()
                ?.firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }

            ChaiChaiTheme(reducedMotion = !ValueAnimator.areAnimatorsEnabled()) {
                MobileApp(
                    boundaries = boundaries,
                    verticalHinge = separatingVerticalHinge?.bounds?.let {
                        VerticalHinge(leftPx = it.left, rightPx = it.right)
                    },
                )
            }
        }
    }
}
