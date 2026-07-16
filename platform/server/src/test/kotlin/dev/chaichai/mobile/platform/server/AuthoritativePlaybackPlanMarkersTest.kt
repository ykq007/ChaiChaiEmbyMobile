package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MarkerKind
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MediaMarker
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skip trustworthy intro/outro markers (issue #34) ride on [AuthoritativePlaybackPlan] rather than a
 * separate lookup, so a marker can never be attached to the wrong item: it only ever exists as a field
 * on the exact plan negotiated for one [ScopedPlaybackRequest]/identity. This covers that shape plus
 * the non-breaking default, and confirms [MediaMarker.isValid] is what ultimately gates a marker
 * (out-of-range/zero-length markers are rejected regardless of how they arrived on the plan).
 */
class AuthoritativePlaybackPlanMarkersTest {
    @Test fun `plan defaults to no markers so pre-#34 constructions stay non-breaking`() {
        assertTrue(plan().markers.isEmpty())
    }

    @Test fun `markers ride on the plan they were negotiated with`() {
        val intro = MediaMarker(MarkerKind.Intro, 0, 900_000_000)
        val outro = MediaMarker(MarkerKind.Outro, 6_300_000_000, 7_100_000_000)
        val withMarkers = plan(markers = listOf(intro, outro))

        assertEquals(listOf(intro, outro), withMarkers.markers)
        assertEquals(MediaIdentity("server", "movie"), MediaIdentity(withMarkers.request.serverId, withMarkers.request.itemId))
    }

    @Test fun `a marker out of the plan runtime is rejected by validation`() {
        val runtime = 7_200_000_000L
        val outOfRange = MediaMarker(MarkerKind.Outro, runtime - 10, runtime + 1_000)
        val withMarker = plan(runtimeTicks = runtime, markers = listOf(outOfRange))

        assertTrue(withMarker.markers.single().let { !it.isValid(withMarker.runtimeTicks) })
    }

    @Test fun `a zero length marker is rejected by validation`() {
        val zeroLength = MediaMarker(MarkerKind.Intro, 100, 100)
        val withMarker = plan(markers = listOf(zeroLength))

        assertTrue(withMarker.markers.single().let { !it.isValid(withMarker.runtimeTicks) })
    }

    private fun plan(runtimeTicks: Long = 7_200_000_000L, markers: List<MediaMarker> = emptyList()) =
        AuthoritativePlaybackPlan(
            request = ScopedPlaybackRequest(
                HomeScope("server", "user"),
                MediaIdentity("server", "movie"),
                PlaybackStart.Beginning,
            ),
            sessionReference = PlaybackSessionReference("source", "session"),
            method = PlaybackMethod.DirectPlay,
            url = "https://example.test/video".toHttpUrl(),
            headers = emptyMap(),
            runtimeTicks = runtimeTicks,
            audioStreamIndex = null,
            subtitleStreamIndex = null,
            markers = markers,
        )
}
