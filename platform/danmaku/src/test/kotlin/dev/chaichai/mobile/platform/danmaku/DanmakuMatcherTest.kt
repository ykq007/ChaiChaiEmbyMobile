package dev.chaichai.mobile.platform.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanmakuMatcherTest {

    private val matcher = DanmakuMatcher()
    private val tps = 10_000_000L

    @Test
    fun picks_the_closest_title_and_runtime() {
        val query = DanmakuMatchQuery(title = "The Wandering Earth", runtimeTicks = 7_200L * tps)
        val best = matcher.bestMatch(
            query,
            listOf(
                DanmakuMatchCandidate("a", "Wandering", 3_600L * tps),
                DanmakuMatchCandidate("b", "The Wandering Earth", 7_205L * tps),
                DanmakuMatchCandidate("c", "Unrelated Film", 7_200L * tps),
            ),
        )
        assertEquals("b", best?.mediaId)
    }

    @Test
    fun rejects_when_no_title_is_close_enough() {
        val query = DanmakuMatchQuery(title = "Arrival", runtimeTicks = 6_000L * tps)
        assertNull(matcher.bestMatch(query, listOf(DanmakuMatchCandidate("x", "Completely Different"))))
    }

    @Test
    fun disagreeing_episode_is_not_a_match() {
        val query = DanmakuMatchQuery(title = "Show", runtimeTicks = 1_400L * tps, season = 1, episode = 2)
        assertNull(
            matcher.bestMatch(
                query,
                listOf(DanmakuMatchCandidate("e", "Show", 1_400L * tps, season = 1, episode = 5)),
            ),
        )
    }

    @Test
    fun matching_episode_is_preferred() {
        val query = DanmakuMatchQuery(title = "Show", runtimeTicks = 1_400L * tps, season = 1, episode = 2)
        val best = matcher.bestMatch(
            query,
            listOf(
                DanmakuMatchCandidate("wrong", "Show", 1_400L * tps, season = 1, episode = 3),
                DanmakuMatchCandidate("right", "Show", 1_400L * tps, season = 1, episode = 2),
            ),
        )
        assertEquals("right", best?.mediaId)
    }
}
