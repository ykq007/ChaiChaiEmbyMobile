package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.SearchResultGroup
import dev.chaichai.mobile.core.contracts.SearchMediaType
import dev.chaichai.mobile.core.contracts.SearchState
import dev.chaichai.mobile.core.contracts.ServerSearchOutcome
import dev.chaichai.mobile.core.contracts.ServerSearchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Cross-server Aggregated Search (#29). Decorates a single-server [EmbyGateway] (Home, libraries,
 * playback, details all stay bound to whichever server is currently active) and replaces only
 * [search]/[searchState] with a fan-out across every session in [vault]: one query is issued
 * per configured server — reusing the exact single-server [AuthenticatedEmbyGateway.search] path
 * via [gatewayFactory] — with results merged by media type while each [SearchResult]'s
 * `scope`/`identity` (server provenance, from the per-session fetch) travels untouched. With
 * exactly one configured server this reproduces the pre-#29 single-server shape byte-for-byte
 * (no [ServerSearchStatus] entries), so existing single-server consumers are unaffected.
 *
 * Concurrency is bounded by [maxConcurrency] (a counting [Semaphore]) so a large server directory
 * can't open unbounded parallel connections. A newer [search] call cancels the in-flight fan-out
 * job before starting its own (mirroring [AuthenticatedEmbyGateway]'s own generation-guarded
 * `activeSearchCall` cancellation), and every per-server fetch is structured underneath that job,
 * so a superseded query's still-arriving per-server responses are cancelled and never published —
 * including for a server that was switched away from or removed mid-search, since [gatewayFactory]
 * builds a throwaway gateway bound to exactly the [StoredSession] captured at fan-out time and
 * never a live, reassignable credential.
 */
class AggregatedSearchGateway(
    private val delegate: EmbyGateway,
    private val vault: SessionVault,
    private val gatewayFactory: (StoredSession) -> EmbyGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrency: Int = DefaultMaxConcurrency,
    private val perServerTimeoutMillis: Long = DefaultPerServerTimeoutMillis,
) : EmbyGateway by delegate {
    private val mutableSearchState = MutableStateFlow<SearchState>(SearchState.Initial)
    override val searchState: StateFlow<SearchState> = mutableSearchState

    private var searchGeneration = 0L
    private var activeJob: Job? = null

    override suspend fun search(query: String) {
        val normalizedQuery = query.trim()
        val generation = synchronized(this) {
            activeJob?.cancel()
            activeJob = null
            ++searchGeneration
        }
        if (normalizedQuery.length < MinimumQueryLength) {
            if (isCurrent(generation)) mutableSearchState.value = SearchState.Initial
            return
        }
        val sessions = vault.sessions()
        if (sessions.isEmpty()) {
            if (isCurrent(generation)) {
                mutableSearchState.value = SearchState.Failure(
                    normalizedQuery,
                    "Sign in again to search your servers.",
                )
            }
            return
        }
        if (isCurrent(generation)) mutableSearchState.value = SearchState.Searching(normalizedQuery)
        coroutineScope {
            val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
            val job = launch(ioDispatcher) {
                val outcomes = sessions.map { session ->
                    async { semaphore.withPermit { fetchForSession(session, normalizedQuery) } }
                }.awaitAll()
                if (isCurrent(generation)) mutableSearchState.value = merge(normalizedQuery, outcomes)
            }
            synchronized(this@AggregatedSearchGateway) { if (generation == searchGeneration) activeJob = job }
            job.join()
        }
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(this) { generation == searchGeneration }

    private suspend fun fetchForSession(session: StoredSession, query: String): PerServerOutcome {
        val gateway = gatewayFactory(session)
        return try {
            withTimeout(perServerTimeoutMillis) { gateway.search(query) }
            when (val state = gateway.searchState.value) {
                is SearchState.Results -> PerServerOutcome(session, state.groups, ServerSearchOutcome.Ok)
                is SearchState.Empty -> PerServerOutcome(session, emptyList(), ServerSearchOutcome.Empty)
                is SearchState.Failure -> PerServerOutcome(session, emptyList(), ServerSearchOutcome.Failed)
                // Searching/Initial after the call returned means the per-server gateway bailed
                // out without a terminal state (e.g. an expired session skips straight to an
                // authentication callback) — isolate it as a failure for this server only.
                else -> PerServerOutcome(session, emptyList(), ServerSearchOutcome.Failed)
            }
        } catch (timeout: TimeoutCancellationException) {
            PerServerOutcome(session, emptyList(), ServerSearchOutcome.TimedOut)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PerServerOutcome(session, emptyList(), ServerSearchOutcome.Failed)
        }
    }

    private fun merge(query: String, outcomes: List<PerServerOutcome>): SearchState {
        val mergedGroups = SearchMediaType.entries.map { type ->
            SearchResultGroup(type, outcomes.flatMap { outcome -> outcome.itemsOf(type) })
        }
        val anyItems = mergedGroups.any { it.items.isNotEmpty() }
        val isFailed = { outcome: ServerSearchOutcome ->
            outcome == ServerSearchOutcome.Failed || outcome == ServerSearchOutcome.TimedOut
        }
        val anyFailure = outcomes.any { isFailed(it.outcome) }
        val allFailed = outcomes.isNotEmpty() && outcomes.all { isFailed(it.outcome) }
        val primaryScope = vault.restore()?.let { HomeScope(it.serverId, it.userId) }
            ?: outcomes.first().session.let { HomeScope(it.serverId, it.userId) }
        // Single configured server: reproduce the pre-#29 shape exactly (no statuses attached).
        val statuses = if (outcomes.size > 1) {
            outcomes.map { ServerSearchStatus(it.session.serverId, it.session.serverName, it.outcome) }
        } else {
            emptyList()
        }
        return when {
            allFailed -> SearchState.Failure(
                query,
                "Search couldn't be completed. Check the connection and retry.",
                primaryScope,
            )
            anyItems || anyFailure -> SearchState.Results(primaryScope, query, mergedGroups, statuses)
            else -> SearchState.Empty(primaryScope, query)
        }
    }

    private fun PerServerOutcome.itemsOf(type: SearchMediaType) =
        groups.filter { it.mediaType == type }.flatMap { it.items }

    private data class PerServerOutcome(
        val session: StoredSession,
        val groups: List<SearchResultGroup>,
        val outcome: ServerSearchOutcome,
    )

    private companion object {
        const val MinimumQueryLength = 2
        const val DefaultMaxConcurrency = 4
        const val DefaultPerServerTimeoutMillis = 8_000L
    }
}

/**
 * A [SessionVault] view fixed to exactly one already-stored [session], used to run the
 * single-server [AuthenticatedEmbyGateway.search] path against a specific server-user scope
 * regardless of which server is currently active. Read-only: mutating calls never touch the real
 * vault, so a fanned-out per-server search can never reassign or leak another server's active
 * credential.
 */
class SingleSessionVault(private val session: StoredSession) : SessionVault {
    override fun restore(): StoredSession = session
    override fun save(session: StoredSession) = Unit
    override fun clear() = Unit
    override fun sessions(): List<StoredSession> = listOf(session)
    override fun selectActive(serverId: String, userId: String): Boolean =
        serverId == session.serverId && userId == session.userId
    override fun remove(serverId: String, userId: String) = Unit
}
