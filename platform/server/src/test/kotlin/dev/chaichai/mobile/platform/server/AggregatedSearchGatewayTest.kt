package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.SearchMediaType
import dev.chaichai.mobile.core.contracts.SearchResult
import dev.chaichai.mobile.core.contracts.SearchResultGroup
import dev.chaichai.mobile.core.contracts.SearchState
import dev.chaichai.mobile.core.contracts.ServerSearchOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregatedSearchGatewayTest {

    private fun address(value: String) = (ServerAddress.parse(value) as AddressValidation.Valid).address

    private fun session(serverId: String, userId: String, name: String) = StoredSession(
        address("https://$serverId.example/emby"),
        serverId,
        userId,
        "User $userId",
        AccessToken.fromRaw("t-$serverId"),
        null,
        name,
    )

    /** In-memory multi-session vault; mirrors the real vault's "every stored session" contract. */
    private class FakeVault(sessions: List<StoredSession>, active: StoredSession? = sessions.firstOrNull()) : SessionVault {
        private val stored = sessions.toMutableList()
        private var activeSession = active
        override fun restore(): StoredSession? = activeSession
        override fun save(session: StoredSession) { stored += session; activeSession = session }
        override fun clear() { activeSession = null }
        override fun sessions(): List<StoredSession> = stored.toList()
        override fun selectActive(serverId: String, userId: String): Boolean {
            val match = stored.firstOrNull { it.serverId == serverId && it.userId == userId } ?: return false
            activeSession = match
            return true
        }
        override fun remove(serverId: String, userId: String) {
            stored.removeAll { it.serverId == serverId && it.userId == userId }
        }
    }

    /** A single-server fake standing in for the real [AuthenticatedEmbyGateway.search] path. */
    private class FakeServerGateway(
        @Suppress("UNUSED_PARAMETER") session: StoredSession,
        private val onFetchStarted: (() -> Unit)? = null,
        private val behavior: suspend (String) -> SearchState,
    ) : EmbyGateway {
        override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
        override val searchState = MutableStateFlow<SearchState>(SearchState.Initial)
        override suspend fun search(query: String) {
            onFetchStarted?.invoke()
            searchState.value = behavior(query)
        }
    }

    @Test
    fun single_configured_server_reproduces_the_pre_aggregation_shape_with_no_statuses() = runTest {
        val one = session("one", "userA", "Server One")
        val vault = FakeVault(listOf(one))
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            gatewayFactory = { session ->
                FakeServerGateway(session) { query ->
                    SearchState.Results(
                        HomeScope(session.serverId, session.userId),
                        query,
                        listOf(SearchResultGroup(SearchMediaType.Movie, listOf(movie(session, "m1", "Arrival")))),
                    )
                }
            },
        )

        gateway.search("ar")

        val result = gateway.searchState.value as SearchState.Results
        assertEquals("Arrival", result.groups[0].items.single().title)
        assertTrue(result.serverStatuses.isEmpty())
    }

    @Test
    fun fan_out_merges_groups_across_servers_preserving_provenance_and_keeping_identical_item_ids_separate() = runTest {
        val one = session("one", "userA", "Server One")
        val two = session("two", "userB", "Server Two")
        val vault = FakeVault(listOf(one, two))
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            gatewayFactory = { session ->
                FakeServerGateway(session) { query ->
                    SearchState.Results(
                        HomeScope(session.serverId, session.userId),
                        query,
                        listOf(SearchResultGroup(SearchMediaType.Movie, listOf(movie(session, "shared-id", "Arrival ${session.serverId}")))),
                    )
                }
            },
        )

        gateway.search("ar")

        val result = gateway.searchState.value as SearchState.Results
        val movies = result.groups.single { it.mediaType == SearchMediaType.Movie }.items
        assertEquals(2, movies.size)
        assertEquals(setOf("one", "two"), movies.map { it.identity.serverId }.toSet())
        // Same itemId on both servers stays two separate results distinguished by scope.
        assertTrue(movies.all { it.identity.itemId == "shared-id" })
        assertEquals(2, result.serverStatuses.size)
        assertTrue(result.serverStatuses.all { it.outcome == ServerSearchOutcome.Ok })
    }

    @Test
    fun one_server_failing_yields_partial_results_with_the_other_servers_marked_ok() = runTest {
        val one = session("one", "userA", "Server One")
        val two = session("two", "userB", "Server Two")
        val vault = FakeVault(listOf(one, two))
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            gatewayFactory = { session ->
                if (session.serverId == "two") {
                    FakeServerGateway(session) { throw RuntimeException("boom") }
                } else {
                    FakeServerGateway(session) { query ->
                        SearchState.Results(
                            HomeScope(session.serverId, session.userId),
                            query,
                            listOf(SearchResultGroup(SearchMediaType.Movie, listOf(movie(session, "m1", "Arrival")))),
                        )
                    }
                }
            },
        )

        gateway.search("ar")

        val result = gateway.searchState.value as SearchState.Results
        assertEquals("Arrival", result.groups.single { it.mediaType == SearchMediaType.Movie }.items.single().title)
        val statuses = result.serverStatuses.associateBy { it.serverId }
        assertEquals(ServerSearchOutcome.Ok, statuses.getValue("one").outcome)
        assertEquals(ServerSearchOutcome.Failed, statuses.getValue("two").outcome)
    }

    @Test
    fun all_servers_failing_yields_failure_not_a_silent_empty_result() = runTest {
        val one = session("one", "userA", "Server One")
        val vault = FakeVault(listOf(one))
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            gatewayFactory = { session -> FakeServerGateway(session) { throw RuntimeException("boom") } },
        )

        gateway.search("ar")

        assertTrue(gateway.searchState.value is SearchState.Failure)
    }

    @Test
    fun empty_server_contributes_nothing_but_is_not_reported_as_a_failure() = runTest {
        val one = session("one", "userA", "Server One")
        val two = session("two", "userB", "Server Two")
        val vault = FakeVault(listOf(one, two))
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            gatewayFactory = { session ->
                if (session.serverId == "two") {
                    FakeServerGateway(session) { query -> SearchState.Empty(HomeScope(session.serverId, session.userId), query) }
                } else {
                    FakeServerGateway(session) { query ->
                        SearchState.Results(
                            HomeScope(session.serverId, session.userId),
                            query,
                            listOf(SearchResultGroup(SearchMediaType.Movie, listOf(movie(session, "m1", "Arrival")))),
                        )
                    }
                }
            },
        )

        gateway.search("ar")

        val result = gateway.searchState.value as SearchState.Results
        val statuses = result.serverStatuses.associateBy { it.serverId }
        assertEquals(ServerSearchOutcome.Empty, statuses.getValue("two").outcome)
        assertFalse(statuses.getValue("two").outcome == ServerSearchOutcome.Failed)
    }

    @Test
    fun all_servers_empty_yields_a_clean_empty_state() = runTest {
        val one = session("one", "userA", "Server One")
        val two = session("two", "userB", "Server Two")
        val vault = FakeVault(listOf(one, two))
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            gatewayFactory = { session ->
                FakeServerGateway(session) { query -> SearchState.Empty(HomeScope(session.serverId, session.userId), query) }
            },
        )

        gateway.search("ar")

        assertTrue(gateway.searchState.value is SearchState.Empty)
    }

    @Test
    fun expired_session_on_one_server_is_isolated_while_the_other_stays_ok() = runTest {
        val one = session("one", "userA", "Server One")
        val two = session("two", "userB", "Server Two")
        val vault = FakeVault(listOf(one, two))
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            gatewayFactory = { session ->
                if (session.serverId == "two") {
                    // Mirrors AuthenticatedEmbyGateway: an expired session bails out without ever
                    // reaching a terminal search state (it signals expiry via a callback instead).
                    FakeServerGateway(session) { SearchState.Searching("ar") }
                } else {
                    FakeServerGateway(session) { query ->
                        SearchState.Results(
                            HomeScope(session.serverId, session.userId),
                            query,
                            listOf(SearchResultGroup(SearchMediaType.Movie, listOf(movie(session, "m1", "Arrival")))),
                        )
                    }
                }
            },
        )

        gateway.search("ar")

        val result = gateway.searchState.value as SearchState.Results
        val statuses = result.serverStatuses.associateBy { it.serverId }
        assertEquals(ServerSearchOutcome.Ok, statuses.getValue("one").outcome)
        assertEquals(ServerSearchOutcome.Failed, statuses.getValue("two").outcome)
    }

    @Test
    fun no_configured_servers_reports_a_sign_in_failure() = runTest {
        val vault = FakeVault(emptyList())
        val gateway = AggregatedSearchGateway(FakeDelegate(), vault, gatewayFactory = { error("not reached") })

        gateway.search("ar")

        assertTrue(gateway.searchState.value is SearchState.Failure)
    }

    @Test
    fun concurrency_never_exceeds_the_configured_cap() = runTest {
        val sessions = (1..6).map { session("s$it", "u$it", "Server $it") }
        val vault = FakeVault(sessions)
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            maxConcurrency = 2,
            gatewayFactory = { session ->
                FakeServerGateway(session, behavior = { query ->
                    val now = inFlight.incrementAndGet()
                    maxObserved.updateAndGet { current -> maxOf(current, now) }
                    delay(20)
                    inFlight.decrementAndGet()
                    SearchState.Empty(HomeScope(session.serverId, session.userId), query)
                })
            },
        )

        gateway.search("ar")

        assertTrue("observed concurrency was ${maxObserved.get()}", maxObserved.get() <= 2)
    }

    @Test
    fun a_newer_query_cancels_the_older_fan_out_so_only_the_newest_results_publish() = runTest {
        val one = session("one", "userA", "Server One")
        val vault = FakeVault(listOf(one))
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val gateway = AggregatedSearchGateway(
            delegate = FakeDelegate(),
            vault = vault,
            gatewayFactory = { session ->
                FakeServerGateway(session) { query ->
                    if (query == "old") {
                        oldStarted.complete(Unit)
                        releaseOld.await()
                    }
                    SearchState.Results(
                        HomeScope(session.serverId, session.userId),
                        query,
                        listOf(SearchResultGroup(SearchMediaType.Movie, listOf(movie(session, query, query)))),
                    )
                }
            },
        )

        val oldJob = launch(Dispatchers.Unconfined) {
            runCatching { gateway.search("old") }
        }
        oldStarted.await()
        gateway.search("new")
        releaseOld.complete(Unit)
        oldJob.join()

        val result = gateway.searchState.value as SearchState.Results
        assertEquals("new", result.query)
        assertEquals("new", result.groups[0].items.single().title)
    }

    private fun movie(session: StoredSession, itemId: String, title: String) = SearchResult(
        HomeScope(session.serverId, session.userId),
        MediaIdentity(session.serverId, itemId),
        SearchMediaType.Movie,
        title,
    )

    /** Minimal stand-in for the real single-server gateway that Aggregated Search decorates. */
    private class FakeDelegate : EmbyGateway {
        override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
    }
}
