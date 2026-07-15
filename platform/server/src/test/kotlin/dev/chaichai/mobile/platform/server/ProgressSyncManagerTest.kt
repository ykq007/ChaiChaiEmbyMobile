package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.HomeScope
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressSyncManagerTest {
    @Test
    fun `newest progress replaces older progress for the same playback identity`() = runTest {
        val store = InMemoryProgressOutbox()
        val manager = manager(store = store, online = false)

        manager.enqueue(progress(position = 100, recordedAt = 1))
        manager.enqueue(progress(position = 80, recordedAt = 2))

        assertEquals(listOf(80L), store.pending(HomeScope("server-a", "user-a")).map { it.positionTicks })
        manager.close()
    }

    @Test
    fun `late older write cannot replace the newest durable position`() = runTest {
        val store = InMemoryProgressOutbox()
        store.save(progress(position = 100, recordedAt = 20))
        store.save(progress(position = 80, recordedAt = 10))

        assertEquals(listOf(100L), store.pending(HomeScope("server-a", "user-a")).map { it.positionTicks })
    }

    @Test
    fun `coalescing newer progress preserves the accumulated failure streak`() = runTest {
        val store = InMemoryProgressOutbox()
        store.save(progress(position = 100, recordedAt = 1).copy(attempts = 3))

        store.save(progress(position = 200, recordedAt = 2))

        val pending = store.pending(HomeScope("server-a", "user-a")).single()
        assertEquals(200L, pending.positionTicks)
        assertEquals(3, pending.attempts)
    }

    @Test
    fun `offline coalescing keeps persistent failure visible`() = runTest {
        val store = InMemoryProgressOutbox()
        val scope = HomeScope("server-a", "user-a")
        store.save(progress(position = 100, recordedAt = 1).copy(attempts = 3))
        val manager = manager(store, online = false)

        manager.enqueue(progress(position = 200, recordedAt = 2))
        advanceUntilIdle()

        assertTrue(manager.status(scope).value is ProgressSyncStatus.Failed)
        assertEquals(3, store.pending(scope).single().attempts)
        manager.close()
    }

    @Test
    fun `restart and connectivity recovery drain durable progress in deterministic order`() = runTest {
        val store = InMemoryProgressOutbox()
        store.save(progress(item = "second", position = 20, recordedAt = 2))
        store.save(progress(item = "first", position = 10, recordedAt = 1))
        val connectivity = MutableStateFlow(false)
        val remote = FakeProgressRemote()

        val manager = ProgressSyncManager(
            store, remote, AppClock { Instant.ofEpochMilli(3) },
            object : ConnectivityMonitor { override val isOnline = connectivity }, FakeRetryScheduler(), this,
        )
        connectivity.value = true
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), remote.sent.map { it.itemId })
        assertTrue(store.pending(HomeScope("server-a", "user-a")).isEmpty())
        manager.close()
    }

    @Test
    fun `queued progress never overwrites a newer server position`() = runTest {
        val store = InMemoryProgressOutbox()
        val remote = FakeProgressRemote(
            serverPositions = mutableMapOf("movie" to ServerProgressPosition.Known(900L, 20)),
        )
        val manager = manager(store, remote = remote, online = true)

        manager.enqueue(progress(position = 500, recordedAt = 1))
        advanceUntilIdle()

        assertTrue(remote.sent.isEmpty())
        assertTrue(store.pending(HomeScope("server-a", "user-a")).isEmpty())
        manager.close()
    }

    @Test
    fun `newer server observation wins even when its intentional position is lower`() = runTest {
        val store = InMemoryProgressOutbox()
        val remote = FakeProgressRemote(
            serverPositions = mutableMapOf("movie" to ServerProgressPosition.Known(100L, updatedAtEpochMillis = 20)),
        )
        val manager = manager(store, remote = remote, online = true)

        manager.enqueue(progress(position = 500, recordedAt = 10))
        advanceUntilIdle()

        assertTrue(remote.sent.isEmpty())
        assertTrue(store.pending(HomeScope("server-a", "user-a")).isEmpty())
        manager.close()
    }

    @Test
    fun `equal timestamp is conservatively server authoritative even when positions differ`() = runTest {
        val store = InMemoryProgressOutbox()
        val remote = FakeProgressRemote(
            serverPositions = mutableMapOf("movie" to ServerProgressPosition.Known(900L, updatedAtEpochMillis = 10)),
        )
        val manager = manager(store, remote = remote, online = true)

        manager.enqueue(progress(position = 500, recordedAt = 10))
        advanceUntilIdle()

        assertTrue(remote.sent.isEmpty())
        assertTrue(store.pending(HomeScope("server-a", "user-a")).isEmpty())
        manager.close()
    }

    @Test
    fun `newer local seek is sent even when its intentional position is lower`() = runTest {
        val store = InMemoryProgressOutbox()
        val remote = FakeProgressRemote(
            serverPositions = mutableMapOf("movie" to ServerProgressPosition.Known(900L, updatedAtEpochMillis = 5)),
        )
        val manager = manager(store, remote = remote, online = true)

        manager.enqueue(progress(position = 500, recordedAt = 10))
        advanceUntilIdle()

        assertEquals(listOf(500L), remote.sent.map { it.positionTicks })
        manager.close()
    }

    @Test
    fun `unavailable reconciliation never sends potentially stale progress`() = runTest {
        val store = InMemoryProgressOutbox()
        val remote = FakeProgressRemote(
            serverPositions = mutableMapOf("movie" to ServerProgressPosition.Unavailable),
        )
        val manager = manager(store, remote = remote, online = true)

        manager.enqueue(progress(position = 500, recordedAt = 1))
        advanceUntilIdle()

        assertTrue(remote.sent.isEmpty())
        assertFalse(store.pending(HomeScope("server-a", "user-a")).isEmpty())
        manager.close()
    }

    @Test
    fun `failed retry retains progress and exposes an actionable non blocking status`() = runTest {
        val store = InMemoryProgressOutbox()
        val remote = FakeProgressRemote(sendSucceeds = false)
        val manager = manager(store, remote = remote, online = true)

        manager.enqueue(progress(position = 500, recordedAt = 1))
        advanceUntilIdle()
        manager.retryNow(HomeScope("server-a", "user-a"))
        advanceUntilIdle()
        manager.retryNow(HomeScope("server-a", "user-a"))
        advanceUntilIdle()

        assertTrue(manager.status(HomeScope("server-a", "user-a")).value is ProgressSyncStatus.Failed)
        assertFalse(store.pending(HomeScope("server-a", "user-a")).isEmpty())
        remote.sendSucceeds = true
        manager.retryNow(HomeScope("server-a", "user-a"))
        advanceUntilIdle()
        assertEquals(ProgressSyncStatus.Synced, manager.status(HomeScope("server-a", "user-a")).value)
        manager.close()
    }

    @Test
    fun `server and user scopes remain isolated during sync and cleanup`() = runTest {
        val store = InMemoryProgressOutbox()
        val a = HomeScope("server-a", "user-a")
        val b = HomeScope("server-b", "user-b")
        store.save(progress(scope = a, item = "shared", position = 10, recordedAt = 1))
        store.save(progress(scope = b, item = "shared", position = 20, recordedAt = 2))

        store.clear(a)

        assertTrue(store.pending(a).isEmpty())
        assertEquals(listOf(20L), store.pending(b).map { it.positionTicks })
    }

    @Test
    fun `failure status from another server user never leaks into active scope`() = runTest {
        val store = InMemoryProgressOutbox()
        val a = HomeScope("server-a", "user-a")
        val b = HomeScope("server-b", "user-b")
        store.save(progress(scope = a, position = 10, recordedAt = 1).copy(attempts = 3))
        val manager = manager(store, online = false)
        advanceUntilIdle()

        assertTrue(manager.status(a).value is ProgressSyncStatus.Failed)
        assertEquals(ProgressSyncStatus.Synced, manager.status(b).value)
        manager.close()
    }

    @Test
    fun `permanent server rejection stops automatic retry and remains actionable`() = runTest {
        val store = InMemoryProgressOutbox()
        val remote = FakeProgressRemote(rejectSend = true)
        val manager = manager(store, remote = remote, online = true)

        manager.enqueue(progress(position = 500, recordedAt = 10))
        advanceUntilIdle()

        assertTrue(manager.status(HomeScope("server-a", "user-a")).value is ProgressSyncStatus.Failed)
        assertEquals(REJECTED_ATTEMPTS, store.pending(HomeScope("server-a", "user-a")).single().attempts)
        manager.close()
    }

    @Test
    fun `manual retry resets rejected progress only for the requested server user`() = runTest {
        val store = InMemoryProgressOutbox()
        val a = HomeScope("server-a", "user-a")
        val b = HomeScope("server-b", "user-b")
        store.save(progress(scope = a, position = 10, recordedAt = 1).copy(attempts = REJECTED_ATTEMPTS))
        store.save(progress(scope = b, position = 20, recordedAt = 2).copy(attempts = REJECTED_ATTEMPTS))
        val manager = manager(store, online = false)

        manager.retryNow(a)
        advanceUntilIdle()

        assertEquals(0, store.pending(a).single().attempts)
        assertEquals(REJECTED_ATTEMPTS, store.pending(b).single().attempts)
        manager.close()
    }

    private fun TestScope.manager(
        store: ProgressOutbox = InMemoryProgressOutbox(),
        remote: FakeProgressRemote = FakeProgressRemote(),
        online: Boolean,
    ) = ProgressSyncManager(
        store, remote, AppClock { Instant.ofEpochMilli(10) },
        object : ConnectivityMonitor { override val isOnline = MutableStateFlow(online) },
        FakeRetryScheduler(), this,
    )

    private fun progress(
        scope: HomeScope = HomeScope("server-a", "user-a"),
        item: String = "movie",
        position: Long,
        recordedAt: Long,
    ) = PendingProgress(
        scope, item, "source", "session", PlaybackMethod.DirectPlay, 1_000, position,
        isPaused = false, event = PlaybackProgressEvent.TimeUpdate, recordedAtEpochMillis = recordedAt,
    )

    private class FakeRetryScheduler : ProgressRetryScheduler {
        val scopes = mutableListOf<HomeScope>()
        override fun schedule(scope: HomeScope) { scopes += scope }
        override fun cancel(scope: HomeScope) { scopes -= scope }
    }

    private class FakeProgressRemote(
        val serverPositions: MutableMap<String, ServerProgressPosition> = mutableMapOf(),
        var sendSucceeds: Boolean = true,
        val rejectSend: Boolean = false,
    ) : ProgressRemote {
        val sent = mutableListOf<PendingProgress>()
        override suspend fun serverPosition(progress: PendingProgress) =
            serverPositions[progress.itemId] ?: ServerProgressPosition.NoPosition
        override suspend fun send(progress: PendingProgress): ProgressSendResult = if (rejectSend) {
            ProgressSendResult.Rejected
        } else if (sendSucceeds) {
            sent += progress
            ProgressSendResult.Confirmed
        } else {
            ProgressSendResult.RetryableFailure
        }
    }
}
