package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.SubtitleAppearance
import dev.chaichai.mobile.core.contracts.SubtitleColorPreset
import dev.chaichai.mobile.core.contracts.SubtitlePosition
import dev.chaichai.mobile.core.contracts.SubtitlePositionBounds
import dev.chaichai.mobile.core.contracts.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure accessibility/rendering checks for Subtitle Expansion (#33): every [SubtitlePosition] stays
 * within the safe render pane (never lands text on an unsafe cutout/fold/hinge inset), and the default
 * appearance's color preset meets WCAG AA contrast.
 */
class SubtitleAppearanceAccessibilityTest {
    @Test fun `every position maps to a bottom padding fraction within the safe area bounds`() {
        SubtitlePosition.entries.forEach { position ->
            val fraction = SubtitlePositionBounds.bottomPaddingFraction(position)
            assertTrue(
                "expected $position padding $fraction to stay within the safe bounds",
                fraction in SubtitlePositionBounds.MinBottomPaddingFraction..SubtitlePositionBounds.MaxBottomPaddingFraction,
            )
        }
    }

    @Test fun `default appearance is a bounded position`() {
        assertEquals(SubtitlePosition.BottomDefault, SubtitleAppearance.Default.position)
    }

    @Test fun `default color preset meets WCAG AA contrast`() {
        val default = SubtitleAppearance.Default.colorPreset
        val ratio = contrastRatio(default.foregroundArgb, default.backgroundArgb)
        assertTrue("expected AA contrast (>= 4.5) but was $ratio", ratio >= 4.5)
    }

    @Test fun `every accessible color preset meets WCAG AA contrast`() {
        SubtitleColorPreset.entries.forEach { preset ->
            val ratio = contrastRatio(preset.foregroundArgb, preset.backgroundArgb)
            assertTrue("expected $preset to meet AA contrast (>= 4.5) but was $ratio", ratio >= 4.5)
        }
    }

    @Test fun `identical colors have a contrast ratio of exactly one`() {
        assertEquals(1.0, contrastRatio(0xFF000000L, 0xFF000000L), 0.0001)
    }
}
