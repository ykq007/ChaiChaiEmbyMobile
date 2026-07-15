package dev.chaichai.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only host used to verify that playback ownership survives real Activity recreation. */
class PlaybackRecreationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        creationCount.incrementAndGet()
        setContent {
            ChaiChaiTheme(reducedMotion = true) {
                MobileApp(boundaries, null, onPlaybackEnded = { playbackEndedCount.incrementAndGet() })
            }
        }
    }

    companion object {
        lateinit var boundaries: AppBoundaries
        val creationCount = AtomicInteger()
        val playbackEndedCount = AtomicInteger()
    }
}
