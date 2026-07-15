package dev.chaichai.mobile.platform.server

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.HomeScope
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface ProgressAwarePlaybackGateway : PlaybackGateway {
    fun progressStatus(scope: HomeScope): StateFlow<ProgressSyncStatus>
    fun retryProgress(scope: HomeScope)
}

class DurableProgressGateway(
    private val delegate: PlaybackGateway,
    private val progress: ProgressSyncManager,
    private val clock: AppClock,
) : ProgressAwarePlaybackGateway {
    private val lastRecordedAt = AtomicLong(Long.MIN_VALUE)
    override fun progressStatus(scope: HomeScope): StateFlow<ProgressSyncStatus> = progress.status(scope)

    override suspend fun negotiate(request: ScopedPlaybackRequest, capabilities: PlaybackCapabilities) =
        delegate.negotiate(request, capabilities)

    override suspend fun report(event: PlaybackReport): Boolean {
        if (event.kind != PlaybackReportKind.Progress) return delegate.report(event)
        val plan = event.plan
        val wallTime = clock.now().toEpochMilli()
        val orderedTime = lastRecordedAt.updateAndGet { previous -> maxOf(wallTime, previous + 1) }
        progress.enqueue(
            PendingProgress(
                scope = plan.request.scope,
                itemId = plan.request.itemId,
                mediaSourceId = plan.mediaSourceId,
                playSessionId = plan.playSessionId,
                method = plan.method,
                runtimeTicks = plan.runtimeTicks,
                positionTicks = event.positionTicks.coerceIn(0, plan.runtimeTicks),
                isPaused = event.isPaused,
                event = event.event,
                recordedAtEpochMillis = orderedTime,
            ),
        )
        return true
    }

    override fun retryProgress(scope: HomeScope) = progress.retryNow(scope)
}

class EmbyProgressRemote(
    private val vault: SessionVault,
    private val deviceId: String,
    private val clients: AuthorityScopedHttpClients = AuthorityScopedHttpClients(),
) : ProgressRemote {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun serverPosition(progress: PendingProgress): ServerProgressPosition = withContext(Dispatchers.IO) {
        val session = session(progress) ?: return@withContext ServerProgressPosition.Unavailable
        try {
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(
                    authenticatedRequest(session)
                        .url(session.address.apiUrl("Users/${session.userId}/Items/${progress.itemId}").toString())
                        .get().build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) return@withContext ServerProgressPosition.Unavailable
                    val data = json.decodeFromString<ProgressItemDto>(response.body.string()).userData
                    data?.playbackPositionTicks?.let { position ->
                        if (position == 0L && data.lastPlayedDate == null) {
                            return@use ServerProgressPosition.NoPosition
                        }
                        ServerProgressPosition.Known(
                            position,
                            data.lastPlayedDate?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
                        )
                    } ?: ServerProgressPosition.NoPosition
                }
        } catch (_: Exception) {
            ServerProgressPosition.Unavailable
        }
    }

    override suspend fun send(progress: PendingProgress): ProgressSendResult = withContext(Dispatchers.IO) {
        val session = session(progress) ?: return@withContext ProgressSendResult.Rejected
        val body = ProgressReportDto(
            progress.itemId, progress.mediaSourceId, progress.playSessionId, progress.positionTicks,
            progress.runtimeTicks, progress.isPaused, progress.method.apiName, progress.event?.name,
        )
        try {
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(
                    authenticatedRequest(session).url(session.address.apiUrl("Sessions/Playing/Progress").toString())
                        .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE)).build(),
                ).execute().use { response ->
                    when {
                        response.isSuccessful -> ProgressSendResult.Confirmed
                        response.code == 408 || response.code == 429 || response.code >= 500 ->
                            ProgressSendResult.RetryableFailure
                        else -> ProgressSendResult.Rejected
                    }
                }
        } catch (_: IOException) {
            ProgressSendResult.RetryableFailure
        }
    }

    private fun session(progress: PendingProgress) = vault.restore()?.takeIf {
        it.serverId == progress.scope.serverId && it.userId == progress.scope.userId
    }

    private fun authenticatedRequest(session: StoredSession) = Request.Builder()
        .header("X-Emby-Token", session.accessToken.encoded())
        .header("X-Emby-Authorization", embyAuthorization(deviceId, session.userId))

    private val PlaybackMethod.apiName: String get() = when (this) {
        PlaybackMethod.DirectPlay -> "DirectPlay"
        PlaybackMethod.Remux -> "DirectStream"
        PlaybackMethod.Transcode -> "Transcode"
    }

    private companion object { val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType() }
}

class WorkManagerProgressRetryScheduler(private val context: Context) : ProgressRetryScheduler {
    override fun schedule(scope: HomeScope) {
        val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
            .setInputData(workDataOf(SERVER_ID to scope.serverId, USER_ID to scope.userId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(scope), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(scope: HomeScope) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(scope))
    }

    private fun workName(scope: HomeScope) = "progress:${scope.serverId}:${scope.userId}"
}

class ProgressSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val serverId = inputData.getString(SERVER_ID) ?: return Result.failure()
        val userId = inputData.getString(USER_ID) ?: return Result.failure()
        val preferences = applicationContext.getSharedPreferences("mobile_client_identity", Context.MODE_PRIVATE)
        val deviceId = preferences.getString("device_id", null) ?: return Result.retry()
        val online = object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) }
        val manager = ProgressSyncManager(
            createRoomProgressOutbox(applicationContext),
            EmbyProgressRemote(KeystoreSessionVault(applicationContext), deviceId),
            AppClock { java.time.Instant.now() },
            online,
            object : ProgressRetryScheduler {
                override fun schedule(scope: HomeScope) = Unit
                override fun cancel(scope: HomeScope) = Unit
            },
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        return if (manager.syncAll(HomeScope(serverId, userId))) Result.success() else Result.retry()
    }
}

internal const val SERVER_ID = "server_id"
internal const val USER_ID = "user_id"

@Serializable
private data class ProgressItemDto(@SerialName("UserData") val userData: ProgressUserDataDto? = null)
@Serializable
private data class ProgressUserDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)
@Serializable
private data class ProgressReportDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PlaySessionId") val playSessionId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("RunTimeTicks") val runtimeTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("PlayMethod") val playMethod: String,
    @SerialName("EventName") val eventName: String?,
)
