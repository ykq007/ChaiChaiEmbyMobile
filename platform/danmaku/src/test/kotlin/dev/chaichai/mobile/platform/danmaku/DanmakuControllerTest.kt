package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuComment
import dev.chaichai.mobile.core.contracts.DanmakuState
import dev.chaichai.mobile.core.contracts.DanmakuUnavailableReason
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
