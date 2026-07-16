package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuComment
import dev.chaichai.mobile.core.contracts.DanmakuMediaKey
import dev.chaichai.mobile.core.contracts.DanmakuPosition
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuTuning
import dev.chaichai.mobile.core.contracts.DanmakuUnavailableReason
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DanmakuControllerTest {

    private val identity = MediaIdentity("server", "movie-1")
    private val scope = HomeScope("server", "user")

    private fun endpoints(vararg names: String) = names.map { DanmakuEndpoint(it, "https://$it.example") }

    @Test
    fun disabled_by_default_and_does_not_match() = runTest {
        val client = FakeEndpointClient(matchResult = { listOf(DanmakuMatchCandidate("m", "Movie")) })
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main"))),
        )
        controller.attach(identity, scope, "Movie", 1_000_000)
        advanceUntilIdle()
        assertEquals(DanmakuState.Disabled, controller.state.value)
        assertEquals(0, client.matchCalls)
    }

    @Test
    fun enabling_matches_and_loads_time_indexed_comments() = runTest {
        val comments = listOf(DanmakuComment(0, "hello"), DanmakuComment(10_000_000, "world"))
        val client = FakeEndpointClient(
            matchResult = { listOf(DanmakuMatchCandidate("m1", "The Movie", 1_000_000)) },
            commentsResult = { comments },
        )
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("primary"))),
        )
        controller.attach(identity, scope, "The Movie", 1_000_000)
        controller.setEnabled(true)
        advanceUntilIdle()
        val active = controller.state.value as DanmakuState.Active
        assertEquals("primary", active.endpointName)
        assertEquals(2, active.totalComments)
    }

    @Test
    fun no_endpoint_configured_is_actionable_status_not_a_crash() = runTest {
        val controller = DanmakuControllerImpl(
            this, FakeEndpointClient(), InMemoryDanmakuConfigStore(DanmakuConfig()),
        )
        controller.attach(identity, scope, "Movie", 1_000_000)
        controller.setEnabled(true)
        advanceUntilIdle()
        val unavailable = controller.state.value as DanmakuState.Unavailable
        assertEquals(DanmakuUnavailableReason.NoEndpointConfigured, unavailable.reason)
        assertTrue(unavailable.message.isNotBlank())
    }

    @Test
    fun no_match_maps_to_contained_unavailable() = runTest {
        val client = FakeEndpointClient(matchResult = { emptyList() })
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main"))),
        )
        controller.attach(identity, scope, "Unknown", 1_000_000)
        controller.setEnabled(true)
        advanceUntilIdle()
        assertEquals(DanmakuUnavailableReason.NoMatch, (controller.state.value as DanmakuState.Unavailable).reason)
    }

    @Test
    fun endpoint_failure_never_throws_and_becomes_unavailable() = runTest {
        val client = FakeEndpointClient(matchResult = { throw IOException("timeout") })
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main"))),
        )
        controller.attach(identity, scope, "Movie", 1_000_000)
        controller.setEnabled(true) // must not throw
        advanceUntilIdle()
        controller.onPlayback(0, false, 1.0f) // must not throw
        assertTrue(controller.state.value is DanmakuState.Unavailable)
    }

    @Test
    fun second_endpoint_is_tried_when_first_is_unreachable() = runTest {
        val comments = listOf(DanmakuComment(0, "hi"))
        val client = FakeEndpointClient(
            matchResult = { endpoint ->
                if (endpoint.name == "flaky") throw IOException("down")
                else listOf(DanmakuMatchCandidate("m", "Movie", 1_000_000))
            },
            commentsResult = { comments },
        )
        val store = InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("flaky", "backup")))
        val controller = DanmakuControllerImpl(this, client, store)
        controller.attach(identity, scope, "Movie", 1_000_000)
        controller.setEnabled(true)
        advanceUntilIdle()
        assertEquals("backup", (controller.state.value as DanmakuState.Active).endpointName)
    }

    @Test
    fun onPlayback_produces_position_synced_snapshot() = runTest {
        val comments = listOf(DanmakuComment(0, "a"), DanmakuComment(30_000_000, "b"))
        val client = FakeEndpointClient(
            matchResult = { listOf(DanmakuMatchCandidate("m", "Movie", 1_000_000)) },
            commentsResult = { comments },
        )
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main"))),
        )
        controller.attach(identity, scope, "Movie", 1_000_000)
        controller.setEnabled(true)
        advanceUntilIdle()
        controller.onPlayback(0, false, 1.0f)
        val overlay = (controller.state.value as DanmakuState.Active).overlay
        assertEquals(1, overlay.visible.size) // only the comment at t=0 is on screen
        assertEquals("a", overlay.visible.first().comment.text)
    }

    @Test
    fun disabling_clears_state_back_to_disabled() = runTest {
        val client = FakeEndpointClient(
            matchResult = { listOf(DanmakuMatchCandidate("m", "Movie", 1_000_000)) },
            commentsResult = { listOf(DanmakuComment(0, "a")) },
        )
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main"))),
        )
        controller.attach(identity, scope, "Movie", 1_000_000)
        controller.setEnabled(true)
        advanceUntilIdle()
        assertTrue(controller.state.value is DanmakuState.Active)
        controller.setEnabled(false)
        assertEquals(DanmakuState.Disabled, controller.state.value)
    }

    @Test
    fun manual_search_and_selection_overrides_auto_match_and_is_remembered_for_that_scope_only() = runTest {
        val autoComments = listOf(DanmakuComment(0, "auto"))
        val manualComments = listOf(DanmakuComment(0, "manual"))
        // The candidate list returned by "match" changes between the automatic phase and the later
        // manual search, letting the fixture stand in for two different endpoint search results.
        var currentCandidates = listOf(DanmakuMatchCandidate("auto-id", "Movie", 1_000_000))
        val client = FakeEndpointClient(
            matchResult = { currentCandidates },
            commentsResult = { mediaId -> if (mediaId == "manual-id") manualComments else autoComments },
        )
        val store = InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main")))
        val controller = DanmakuControllerImpl(this, client, store)
        val episodeA = DanmakuMediaKey.Episode("server", "series-1", 1, 1)
        controller.attach(identity, scope, "Movie", 1_000_000, episodeA)
        controller.setEnabled(true)
        advanceUntilIdle()
        assertEquals("auto", (controller.state.value as DanmakuState.Active).overlay.visible.first().comment.text)

        currentCandidates = listOf(DanmakuMatchCandidate("manual-id", "Manual Title", 1_000_000))
        controller.searchMatches("Manual Title")
        advanceUntilIdle()
        val option = controller.matchOptions.value.results.single()
        controller.selectMatch(option.candidateId)
        advanceUntilIdle()
        controller.onPlayback(0, false, 1.0f)
        assertEquals("manual", (controller.state.value as DanmakuState.Active).overlay.visible.first().comment.text)

        // Remembered choice is scoped: episode B and another server must NOT see it.
        val remembered = store.load().rememberedMatches
        assertEquals(1, remembered.size)
        val episodeB = DanmakuMediaKey.Episode("server", "series-1", 1, 2)
        val otherServer = DanmakuMediaKey.Episode("other-server", "series-1", 1, 1)
        assertNull(store.rememberedMatch(episodeB))
        assertNull(store.rememberedMatch(otherServer))
        assertNotNull(store.rememberedMatch(episodeA))

        // Re-attaching the SAME scope loads the remembered manual match, skipping auto-match.
        controller.detach()
        controller.attach(identity, scope, "Movie", 1_000_000, episodeA)
        advanceUntilIdle()
        controller.onPlayback(0, false, 1.0f)
        assertEquals("manual", (controller.state.value as DanmakuState.Active).overlay.visible.first().comment.text)
    }

    @Test
    fun clearing_a_match_forgets_it_and_falls_back_to_automatic_matching() = runTest {
        val client = FakeEndpointClient(
            matchResult = { listOf(DanmakuMatchCandidate("auto-id", "Movie", 1_000_000)) },
            commentsResult = { listOf(DanmakuComment(0, "auto")) },
        )
        val store = InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main")))
        val key = DanmakuMediaKey.Movie("server", "movie-1")
        store.rememberMatch(key, DanmakuRememberedMatch("main", "manual-id", "Manual"))
        val controller = DanmakuControllerImpl(this, client, store)
        controller.attach(identity, scope, "Movie", 1_000_000, key)
        controller.setEnabled(true)
        advanceUntilIdle()
        assertNotNull(store.rememberedMatch(key))

        controller.clearMatch()
        advanceUntilIdle()
        assertNull(store.rememberedMatch(key))
        assertTrue(controller.state.value is DanmakuState.Active)
    }

    @Test
    fun search_survives_a_partial_endpoint_failure_and_never_throws() = runTest {
        val client = FakeEndpointClient(
            matchResult = { endpoint ->
                if (endpoint.name == "broken") throw IOException("down")
                listOf(DanmakuMatchCandidate("m1", "Result"))
            },
        )
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("broken", "healthy"))),
        )
        controller.attach(identity, scope, "Movie", 1_000_000)
        controller.searchMatches("query") // must not throw
        advanceUntilIdle()
        val options = controller.matchOptions.value
        assertEquals(1, options.results.size)
        assertNull(options.error)
    }

    @Test
    fun tuning_persists_with_neutral_defaults_and_applies_live_without_reload() = runTest {
        val client = FakeEndpointClient(
            matchResult = { listOf(DanmakuMatchCandidate("m", "Movie", 1_000_000)) },
            commentsResult = { listOf(DanmakuComment(0, "a"), DanmakuComment(20_000_000, "b")) },
        )
        val store = InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main")))
        val controller = DanmakuControllerImpl(this, client, store)
        assertEquals(DanmakuTuning.Neutral, controller.tuning.value)

        val key = DanmakuMediaKey.Movie("server", "movie-1")
        controller.attach(identity, scope, "Movie", 1_000_000, key)
        controller.setEnabled(true)
        advanceUntilIdle()
        controller.onPlayback(0, false, 1.0f)
        assertEquals(1, (controller.state.value as DanmakuState.Active).overlay.visible.size)

        // Timing offset shifts which comments are visible, applied immediately (no reload/re-match).
        controller.updateTuning(DanmakuTuning(timingOffsetMillis = 2_000L))
        val shifted = (controller.state.value as DanmakuState.Active).overlay
        assertEquals(2, shifted.visible.size)
        assertEquals(1, client.matchCalls) // no re-match happened

        // Restricting allowed positions filters the snapshot live too.
        controller.updateTuning(DanmakuTuning(allowedPositions = emptySet()))
        assertTrue((controller.state.value as DanmakuState.Active).overlay.visible.isEmpty())

        assertEquals(DanmakuTuning(allowedPositions = emptySet()), store.tuning(key))
    }

    @Test
    fun tuning_speed_changes_the_scroll_window_live() = runTest {
        val client = FakeEndpointClient(
            matchResult = { listOf(DanmakuMatchCandidate("m", "Movie", 1_000_000)) },
            commentsResult = { listOf(DanmakuComment(0, "a")) },
        )
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main"))),
        )
        controller.attach(identity, scope, "Movie", 1_000_000)
        controller.setEnabled(true)
        advanceUntilIdle()
        controller.onPlayback(12 * 10_000_000L, false, 1.0f)
        assertTrue((controller.state.value as DanmakuState.Active).overlay.visible.isEmpty())

        // Doubling comment speed widens the media-time window enough to bring "a" back on screen.
        controller.updateTuning(DanmakuTuning(speed = 2.0f))
        assertFalse((controller.state.value as DanmakuState.Active).overlay.visible.isEmpty())
    }

    @Test
    fun resource_limits_are_still_enforced_with_a_shrunk_screen_fraction() = runTest {
        val comments = (0 until 100).map { DanmakuComment(0, "c$it") }
        val client = FakeEndpointClient(
            matchResult = { listOf(DanmakuMatchCandidate("m", "Movie", 1_000_000)) },
            commentsResult = { comments },
        )
        val controller = DanmakuControllerImpl(
            this, client, InMemoryDanmakuConfigStore(DanmakuConfig(endpoints = endpoints("main"))),
        )
        controller.attach(identity, scope, "Movie", 1_000_000)
        controller.setEnabled(true)
        advanceUntilIdle()
        controller.onPlayback(0, false, 1.0f)
        val fullBudget = (controller.state.value as DanmakuState.Active).overlay.visible.size

        controller.updateTuning(DanmakuTuning(screenFraction = 0.2f))
        val shrunkBudget = (controller.state.value as DanmakuState.Active).overlay.visible.size
        assertTrue(shrunkBudget in 1 until fullBudget)
    }

    private class FakeEndpointClient(
        val matchResult: (DanmakuEndpoint) -> List<DanmakuMatchCandidate> = { emptyList() },
        val commentsResult: (String) -> List<DanmakuComment> = { emptyList() },
    ) : DanmakuEndpointClient {
        var matchCalls = 0
        override suspend fun match(endpoint: DanmakuEndpoint, query: DanmakuMatchQuery): List<DanmakuMatchCandidate> {
            matchCalls++
            return matchResult(endpoint)
        }
        override suspend fun fetchComments(endpoint: DanmakuEndpoint, mediaId: String): List<DanmakuComment> =
            commentsResult(mediaId)
    }
}
