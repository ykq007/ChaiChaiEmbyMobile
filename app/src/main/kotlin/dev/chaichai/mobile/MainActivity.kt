package dev.chaichai.mobile

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.chaichai.mobile.design.system.ChaiChaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val boundaries = remember { ProductionBoundaries.create(this) }
            val layoutInfo by WindowInfoTracker.getOrCreate(this)
                .windowLayoutInfo(this)
                .collectAsState(initial = null)
            val hasSeparatingVerticalHinge = layoutInfo?.displayFeatures
                ?.filterIsInstance<FoldingFeature>()
                ?.any { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
                ?: false

            ChaiChaiTheme(reducedMotion = !ValueAnimator.areAnimatorsEnabled()) {
                MobileApp(
                    boundaries = boundaries,
                    hasSeparatingVerticalHinge = hasSeparatingVerticalHinge,
                )
            }
        }
    }
}
