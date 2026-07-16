package dev.chaichai.mobile.platform.playback

import dev.chaichai.mobile.core.contracts.MarkerKind
import dev.chaichai.mobile.core.contracts.MediaMarker
import dev.chaichai.mobile.core.contracts.SkipTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure validity/offer-window checks for Skip trustworthy intro/outro markers (issue #34), independent
 * of any coordinator or gateway plumbing — mirrors [SubtitleAppearanceAccessibilityTest]'s convention
 * of unit-testing an Android-free `core:contracts` helper directly.
 */
class SkipTargetsTest {
    private val runtime = 7_200_000_000L // 12 minutes in ticks

    @Test fun `well formed markers within runtime are valid`() {
        val intro = MediaMarker(MarkerKind.Intro, 0, 900_000_000)
        val outro = MediaMarker(MarkerKind.Outro, 6_300_000_000, 7_100_000_000)
        assertTrue(intro.isValid(runtime))
        assertTrue(outro.isValid(runtime))
    }

    @Test fun `zero length marker is rejected`() {
        assertTrue(!MediaMarker(MarkerKind.Intro, 100, 100).isValid(runtime))
    }

    @Test fun `marker whose end exceeds runtime is rejected`() {
        assertTrue(!MediaMarker(MarkerKind.Outro, 100, runtime + 1).isValid(runtime))
    }

    @Test fun `marker with negative start is rejected`() {
        assertTrue(!MediaMarker(MarkerKind.Intro, -10, 100).isValid(runtime))
    }

    @Test fun `inverted marker where end precedes start is rejected`() {
        assertTrue(!MediaMarker(MarkerKind.Intro, 500, 100).isValid(runtime))
    }

    @Test fun `zero runtime invalidates every marker`() {
        assertTrue(!MediaMarker(MarkerKind.Intro, 0, 100).isValid(0))
    }

    @Test fun `intro is offered from playback start through the end of its window`() {
        val intro = MediaMarker(MarkerKind.Intro, 0, 900_000_000)
        assertEquals(1, SkipTargets.current(listOf(intro), runtime, 0).size)
        assertEquals(1, SkipTargets.current(listOf(intro), runtime, 500_000_000).size)
        assertTrue(SkipTargets.current(listOf(intro), runtime, 900_000_000).isEmpty())
    }

    @Test fun `outro is offered only within its own window`() {
        val outro = MediaMarker(MarkerKind.Outro, 6_300_000_000, 7_100_000_000)
        assertTrue(SkipTargets.current(listOf(outro), runtime, 6_000_000_000).isEmpty())
        assertEquals(1, SkipTargets.current(listOf(outro), runtime, 6_500_000_000).size)
        assertTrue(SkipTargets.current(listOf(outro), runtime, 7_100_000_000).isEmpty())
    }

    @Test fun `invalid marker never offers a skip target`() {
        val zeroLength = MediaMarker(MarkerKind.Intro, 0, 0)
        assertTrue(SkipTargets.current(listOf(zeroLength), runtime, 0).isEmpty())
        val outOfRange = MediaMarker(MarkerKind.Outro, runtime - 10, runtime + 1_000)
        assertTrue(SkipTargets.current(listOf(outOfRange), runtime, runtime - 5).isEmpty())
    }

    @Test fun `missing markers offer nothing`() {
        assertTrue(SkipTargets.current(emptyList(), runtime, 0).isEmpty())
    }

    @Test fun `skip target carries the marker end as its seek boundary and a user facing label`() {
        val intro = MediaMarker(MarkerKind.Intro, 0, 900_000_000)
        val target = SkipTargets.current(listOf(intro), runtime, 0).single()
        assertEquals(900_000_000L, target.seekToTicks)
        assertEquals(MarkerKind.Intro, target.kind)
        assertEquals("Skip intro", target.label)
    }
}
