package dev.chaichai.mobile.design.system

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalReducedMotion = staticCompositionLocalOf { false }

private val ScreeningRoomColors = darkColorScheme(
    primary = Color(0xFFF2B84B),
    onPrimary = Color(0xFF241A08),
    background = Color(0xFF0D0E10),
    onBackground = Color(0xFFF5F2EC),
    surface = Color(0xFF17191D),
    onSurface = Color(0xFFF5F2EC),
    onSurfaceVariant = Color(0xFFCAC5BC),
)

@Composable
fun ChaiChaiTheme(reducedMotion: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = ScreeningRoomColors,
            typography = Typography(),
            content = content,
        )
    }
}
