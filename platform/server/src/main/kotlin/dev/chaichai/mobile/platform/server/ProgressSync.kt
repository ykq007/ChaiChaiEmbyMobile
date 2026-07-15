package dev.chaichai.mobile.platform.server

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.HomeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class PendingProgress(
    val scope: HomeScope,
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val method: PlaybackMethod,
    val runtimeTicks: Long,
    val positionTicks: Long,
    val isPaused: Boolean,
    val event: PlaybackProgressEvent?,
    val recordedAtEpochMillis: Long,
    val attempts: Int = 0,
)

interface ProgressOutbox {
    suspend fun save(progress: PendingProgress)
    suspend fun pending(scope: HomeScope? = null): List<PendingProgress>
    suspend fun removeIfCurrent(progress: PendingProgress)
    suspend fun failed(progress: PendingProgress)
    suspend fun rejected(progress: PendingProgress)
    suspend fun retryRejected(scope: HomeScope)
    suspend fun clear(scope: HomeScope)
}

class InMemoryProgressOutbox : ProgressOutbox {
    private val entries = linkedMapOf<ProgressIdentity, PendingProgress>()
    override suspend fun save(progress: PendingProgress) {
        val existing = entries[progress.identity]
        if ((existing?.recordedAtEpochMillis ?: Long.MIN_VALUE) <= progress.recordedAtEpochMillis) {
            entries[progress.identity] = progress.copy(attempts = existing?.attempts ?: progress.attempts)
        }
    }
    override suspend fun pending(scope: HomeScope?): List<PendingProgress> = entries.values
        .filter { scope == null || it.scope == scope }
        .sortedWith(PENDING_ORDER)
    override suspend fun removeIfCurrent(progress: PendingProgress) {
        if (entries[progress.identity]?.recordedAtEpochMillis == progress.recordedAtEpochMillis) {
            entries.remove(progress.identity)
        }
    }
    override suspend fun failed(progress: PendingProgress) {
        entries[progress.identity]?.takeIf { it.recordedAtEpochMillis == progress.recordedAtEpochMillis }?.let {
            entries[progress.identity] = it.copy(attempts = it.attempts + 1)
        }
    }
    override suspend fun rejected(progress: PendingProgress) {
        entries[progress.identity]?.takeIf { it.recordedAtEpochMillis == progress.recordedAtEpochMillis }?.let {
            entries[progress.identity] = it.copy(attempts = REJECTED_ATTEMPTS)
        }
    }
    override suspend fun retryRejected(scope: HomeScope) {
        entries.replaceAll { _, value ->
            if (value.scope == scope && value.attempts == REJECTED_ATTEMPTS) value.copy(attempts = 0) else value
        }
    }
    override suspend fun clear(scope: HomeScope) {
        entries.keys.removeAll { it.scope == scope }
    }
}

interface ProgressRemote {
    suspend fun serverPosition(progress: PendingProgress): ServerProgressPosition
    suspend fun send(progress: PendingProgress): ProgressSendResult
}

enum class ProgressSendResult { Confirmed, RetryableFailure, Rejected }

sealed interface ServerProgressPosition {
    data class Known(val positionTicks: Long, val updatedAtEpochMillis: Long? = null) : ServerProgressPosition
    data object NoPosition : ServerProgressPosition
    data object Unavailable : ServerProgressPosition
}

interface ProgressRetryScheduler {
    fun schedule(scope: HomeScope)
    fun cancel(scope: HomeScope)
}

interface ProgressAccountSync {
    suspend fun finalSync(scope: HomeScope): Boolean
    suspend fun hasPending(scope: HomeScope): Boolean
    suspend fun clear(scope: HomeScope)
}

sealed interface ProgressSyncStatus {
    data object Synced : ProgressSyncStatus
    data object Pending : ProgressSyncStatus
    data class Failed(val message: String) : ProgressSyncStatus
}

class ProgressSyncManager(
    private val outbox: ProgressOutbox,
    private val remote: ProgressRemote,
    private val clock: AppClock,
    private val connectivity: ConnectivityMonitor,
    private val scheduler: ProgressRetryScheduler,
    private val scope: CoroutineScope,
) : ProgressAccountSync, AutoCloseable {
    private val statuses = ConcurrentHashMap<HomeScope, MutableStateFlow<ProgressSyncStatus>>()
    private val syncMutex = Mutex()
    private var connectivityJob: Job = scope.launch {
        connectivity.isOnline.collectLatest { online ->
            refreshStatuses()
            if (online) syncAll()
        }
    }

    fun status(scope: HomeScope): StateFlow<ProgressSyncStatus> =
        statuses.getOrPut(scope) { MutableStateFlow(ProgressSyncStatus.Synced) }

    suspend fun enqueue(progress: PendingProgress) {
        outbox.save(progress.copy(recordedAtEpochMillis = progress.recordedAtEpochMillis.coerceAtLeast(clock.now().toEpochMilli())))
        refreshStatuses(progress.scope)
        scheduler.schedule(progress.scope)
        if (connectivity.isOnline.value) scope.launch { syncAll() }
    }

    fun retryNow(homeScope: HomeScope) {
        scope.launch {
            outbox.retryRejected(homeScope)
            refreshStatuses(homeScope)
            syncAll(homeScope)
        }
    }

    suspend fun syncAll(scopeFilter: HomeScope? = null): Boolean = syncMutex.withLock {
        if (!connectivity.isOnline.value) {
            refreshStatuses(scopeFilter)
            return@withLock false
        }
        var succeeded = true
        for (progress in outbox.pending(scopeFilter)) {
            if (progress.attempts == REJECTED_ATTEMPTS) continue
            val serverPosition = try {
                remote.serverPosition(progress)
            } catch (_: Exception) {
                ServerProgressPosition.Unavailable
            }
            when (serverPosition) {
                is ServerProgressPosition.Known -> {
                    val serverTime = serverPosition.updatedAtEpochMillis
                    if (serverTime == null) {
                        outbox.failed(progress)
                        succeeded = false
                        continue
                    }
                    if (serverTime >= progress.recordedAtEpochMillis) {
                        outbox.removeIfCurrent(progress)
                        continue
                    }
                }
                ServerProgressPosition.Unavailable -> {
                    outbox.failed(progress)
                    succeeded = false
                    continue
                }
                ServerProgressPosition.NoPosition -> Unit
            }
            val sent = try {
                remote.send(progress)
            } catch (_: Exception) {
                ProgressSendResult.RetryableFailure
            }
            when (sent) {
                ProgressSendResult.Confirmed -> outbox.removeIfCurrent(progress)
                ProgressSendResult.RetryableFailure -> {
                    outbox.failed(progress)
                    succeeded = false
                }
                ProgressSendResult.Rejected -> outbox.rejected(progress)
            }
        }
        refreshStatuses(scopeFilter)
        succeeded && outbox.pending(scopeFilter).none { it.attempts != REJECTED_ATTEMPTS }
    }

    override suspend fun finalSync(scope: HomeScope): Boolean = syncAll(scope)

    override suspend fun hasPending(scope: HomeScope): Boolean = outbox.pending(scope).isNotEmpty()

    override suspend fun clear(scope: HomeScope) {
        scheduler.cancel(scope)
        outbox.clear(scope)
        refreshStatuses(scope)
    }

    override fun close() { connectivityJob.cancel() }

    private suspend fun refreshStatuses(scopeFilter: HomeScope? = null) {
        val pending = outbox.pending(scopeFilter)
        val scopes = if (scopeFilter != null) setOf(scopeFilter) else pending.mapTo(statuses.keys.toMutableSet()) { it.scope }
        scopes.forEach { accountScope ->
            val scoped = pending.filter { it.scope == accountScope }
            mutableStatus(accountScope).value = when {
                scoped.isEmpty() -> ProgressSyncStatus.Synced
                scoped.any { it.attempts == REJECTED_ATTEMPTS } -> ProgressSyncStatus.Failed(
                    "The server rejected watch progress. Sign in again, then retry.",
                )
                scoped.any { it.attempts >= PERSISTENT_FAILURE_ATTEMPTS } -> ProgressSyncStatus.Failed(
                    "Watch progress isn't syncing. Check your connection and retry.",
                )
                else -> ProgressSyncStatus.Pending
            }
        }
    }

    private fun mutableStatus(scope: HomeScope) =
        statuses.getOrPut(scope) { MutableStateFlow(ProgressSyncStatus.Synced) }

    private companion object { const val PERSISTENT_FAILURE_ATTEMPTS = 3 }
}

internal const val REJECTED_ATTEMPTS = -1
private data class ProgressIdentity(val scope: HomeScope, val itemId: String)
private val PendingProgress.identity get() = ProgressIdentity(scope, itemId)
private val PENDING_ORDER = compareBy<PendingProgress>(
    { it.recordedAtEpochMillis }, { it.scope.serverId }, { it.scope.userId }, { it.itemId },
)

@Entity(tableName = "pending_progress", primaryKeys = ["serverId", "userId", "itemId"])
internal data class PendingProgressEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val method: String,
    val runtimeTicks: Long,
    val positionTicks: Long,
    val isPaused: Boolean,
    val event: String?,
    val recordedAtEpochMillis: Long,
    val attempts: Int,
)

@Dao
internal interface ProgressOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entity: PendingProgressEntity)
    @Query("""UPDATE pending_progress SET mediaSourceId=:mediaSourceId, playSessionId=:playSessionId, method=:method,
        runtimeTicks=:runtimeTicks, positionTicks=:positionTicks, isPaused=:isPaused, event=:event,
        recordedAtEpochMillis=:recordedAt
        WHERE serverId=:serverId AND userId=:userId AND itemId=:itemId AND recordedAtEpochMillis<=:recordedAt""")
    suspend fun replaceIfNewer(
        serverId: String, userId: String, itemId: String, mediaSourceId: String, playSessionId: String,
        method: String, runtimeTicks: Long, positionTicks: Long, isPaused: Boolean, event: String?, recordedAt: Long,
    )
    @Query("SELECT * FROM pending_progress ORDER BY recordedAtEpochMillis, serverId, userId, itemId")
    suspend fun pending(): List<PendingProgressEntity>
    @Query("SELECT * FROM pending_progress WHERE serverId=:serverId AND userId=:userId ORDER BY recordedAtEpochMillis, itemId")
    suspend fun pending(serverId: String, userId: String): List<PendingProgressEntity>
    @Query("DELETE FROM pending_progress WHERE serverId=:serverId AND userId=:userId AND itemId=:itemId AND recordedAtEpochMillis=:recordedAt")
    suspend fun removeIfCurrent(serverId: String, userId: String, itemId: String, recordedAt: Long)
    @Query("UPDATE pending_progress SET attempts=attempts+1 WHERE serverId=:serverId AND userId=:userId AND itemId=:itemId AND recordedAtEpochMillis=:recordedAt")
    suspend fun failed(serverId: String, userId: String, itemId: String, recordedAt: Long)
    @Query("UPDATE pending_progress SET attempts=:attempts WHERE serverId=:serverId AND userId=:userId AND itemId=:itemId AND recordedAtEpochMillis=:recordedAt")
    suspend fun rejected(serverId: String, userId: String, itemId: String, recordedAt: Long, attempts: Int = REJECTED_ATTEMPTS)
    @Query("UPDATE pending_progress SET attempts=0 WHERE serverId=:serverId AND userId=:userId AND attempts=:rejectedAttempts")
    suspend fun retryRejected(serverId: String, userId: String, rejectedAttempts: Int = REJECTED_ATTEMPTS)
    @Query("DELETE FROM pending_progress WHERE serverId=:serverId AND userId=:userId")
    suspend fun clear(serverId: String, userId: String)
}

@Database(entities = [PendingProgressEntity::class], version = 1, exportSchema = false)
internal abstract class ProgressOutboxDatabase : RoomDatabase() { abstract fun dao(): ProgressOutboxDao }

internal class RoomProgressOutbox(private val dao: ProgressOutboxDao) : ProgressOutbox {
    override suspend fun save(progress: PendingProgress) {
        val entity = progress.toEntity()
        dao.insert(entity)
        dao.replaceIfNewer(
            entity.serverId, entity.userId, entity.itemId, entity.mediaSourceId, entity.playSessionId,
            entity.method, entity.runtimeTicks, entity.positionTicks, entity.isPaused, entity.event,
            entity.recordedAtEpochMillis,
        )
    }
    override suspend fun pending(scope: HomeScope?): List<PendingProgress> =
        (scope?.let { dao.pending(it.serverId, it.userId) } ?: dao.pending()).map { it.toContract() }
    override suspend fun removeIfCurrent(progress: PendingProgress) = dao.removeIfCurrent(
        progress.scope.serverId, progress.scope.userId, progress.itemId, progress.recordedAtEpochMillis,
    )
    override suspend fun failed(progress: PendingProgress) = dao.failed(
        progress.scope.serverId, progress.scope.userId, progress.itemId, progress.recordedAtEpochMillis,
    )
    override suspend fun rejected(progress: PendingProgress) = dao.rejected(
        progress.scope.serverId, progress.scope.userId, progress.itemId, progress.recordedAtEpochMillis,
    )
    override suspend fun retryRejected(scope: HomeScope) = dao.retryRejected(scope.serverId, scope.userId)
    override suspend fun clear(scope: HomeScope) = dao.clear(scope.serverId, scope.userId)
}

fun createRoomProgressOutbox(context: Context): ProgressOutbox = RoomProgressOutbox(
    Room.databaseBuilder(context, ProgressOutboxDatabase::class.java, "server_user_progress_outbox.db").build().dao(),
)

private fun PendingProgress.toEntity() = PendingProgressEntity(
    scope.serverId, scope.userId, itemId, mediaSourceId, playSessionId, method.name, runtimeTicks,
    positionTicks, isPaused, event?.name, recordedAtEpochMillis, attempts,
)
private fun PendingProgressEntity.toContract() = PendingProgress(
    HomeScope(serverId, userId), itemId, mediaSourceId, playSessionId, PlaybackMethod.valueOf(method),
    runtimeTicks, positionTicks, isPaused, event?.let(PlaybackProgressEvent::valueOf), recordedAtEpochMillis, attempts,
)
