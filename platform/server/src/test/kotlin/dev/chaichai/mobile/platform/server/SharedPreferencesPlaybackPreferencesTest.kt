package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.SubtitleAppearance
import dev.chaichai.mobile.core.contracts.SubtitleColorPreset
import dev.chaichai.mobile.core.contracts.SubtitleEdgeStyle
import dev.chaichai.mobile.core.contracts.SubtitlePosition
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Subtitle Expansion (#33): proves the DOCUMENTED scope for subtitle appearance — server+user
 * ([HomeScope]), same as playback speed and unlike media-scoped subtitle delay — and that a pre-#33
 * install (no persisted appearance record) migrates to [SubtitleAppearance.Default] with no data loss.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesPlaybackPreferencesTest {
    private fun preferences() = SharedPreferencesPlaybackPreferences(RuntimeEnvironment.getApplication())

    @Test fun `absent appearance record migrates to the contrast-safe default`() {
        val prefs = preferences()
        assertEquals(SubtitleAppearance.Default, prefs.subtitleAppearanceFor(HomeScope("server", "user")))
    }

    @Test fun `appearance persists and loads at the server user scope`() {
        val prefs = preferences()
        val scope = HomeScope("server", "user")
        val appearance = SubtitleAppearance(
            textScale = 1.5f,
            position = SubtitlePosition.Upper,
            colorPreset = SubtitleColorPreset.YellowOnBlack,
            edgeStyle = SubtitleEdgeStyle.DropShadow,
            windowOpacity = 0.9f,
        )

        prefs.setSubtitleAppearance(scope, appearance)

        assertEquals(appearance, prefs.subtitleAppearanceFor(scope))
    }

    @Test fun `appearance is scoped per server user and never crosses a different account`() {
        val prefs = preferences()
        val scopeA = HomeScope("server", "alice")
        val scopeB = HomeScope("server", "bob")
        val appearanceA = SubtitleAppearance(textScale = 1.75f, colorPreset = SubtitleColorPreset.BlackOnWhite)

        prefs.setSubtitleAppearance(scopeA, appearanceA)

        assertEquals(appearanceA, prefs.subtitleAppearanceFor(scopeA))
        assertEquals(SubtitleAppearance.Default, prefs.subtitleAppearanceFor(scopeB))
    }

    @Test fun `appearance scope is independent of media-scoped subtitle delay`() {
        val prefs = preferences()
        val scope = HomeScope("server", "user")
        val identity = MediaIdentity("server", "movie")
        val appearance = SubtitleAppearance(textScale = 1.25f)

        prefs.setSubtitleAppearance(scope, appearance)
        prefs.setSubtitleDelay(identity, 500L)

        // Changing the media-scoped delay never disturbs the server+user-scoped appearance record.
        assertEquals(appearance, prefs.subtitleAppearanceFor(scope))
        assertEquals(500L, prefs.subtitleDelayFor(identity))
    }
}
