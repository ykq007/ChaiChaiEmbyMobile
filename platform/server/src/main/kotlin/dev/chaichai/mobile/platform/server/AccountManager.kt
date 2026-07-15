package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.AccountBoundary
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.SignOutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AccountManager(
    private val scope: CoroutineScope,
    private val vault: SessionVault,
    private val progress: ProgressAccountSync,
    private val privateData: ScopedPrivateDataCleaner,
    private val onSignedOut: () -> Unit,
) : AccountBoundary {
    private val mutableState = MutableStateFlow<SignOutState>(SignOutState.Idle)
    override val signOutState: StateFlow<SignOutState> = mutableState
    private var pendingScope: HomeScope? = null

    override fun requestSignOut() {
        if (mutableState.value == SignOutState.Syncing) return
        val session = vault.restore() ?: run {
            mutableState.value = SignOutState.SignedOut
            onSignedOut()
            return
        }
        val accountScope = HomeScope(session.serverId, session.userId)
        pendingScope = accountScope
        mutableState.value = SignOutState.Syncing
        scope.launch {
            withTimeoutOrNull(FINAL_SYNC_TIMEOUT_MILLIS) { progress.finalSync(accountScope) }
            if (progress.hasPending(accountScope)) {
                mutableState.value = SignOutState.ConfirmationRequired()
            } else {
                removePrivateData(accountScope)
            }
        }
    }

    override fun confirmProgressLoss() {
        val accountScope = pendingScope ?: return
        if (mutableState.value !is SignOutState.ConfirmationRequired) return
        mutableState.value = SignOutState.Syncing
        scope.launch { removePrivateData(accountScope) }
    }

    override fun cancelSignOut() {
        if (mutableState.value is SignOutState.ConfirmationRequired) {
            pendingScope = null
            mutableState.value = SignOutState.Idle
        }
    }

    private suspend fun removePrivateData(accountScope: HomeScope) {
        progress.clear(accountScope)
        privateData.clear(accountScope)
        val restored = vault.restore()
        if (restored?.serverId == accountScope.serverId && restored.userId == accountScope.userId) vault.clear()
        pendingScope = null
        mutableState.value = SignOutState.SignedOut
        onSignedOut()
    }

    private companion object { const val FINAL_SYNC_TIMEOUT_MILLIS = 5_000L }
}

fun interface ScopedPrivateDataCleaner { suspend fun clear(scope: HomeScope) }

class ServerPrivateDataCleaner(
    private val homeCache: HomeCache,
    private val movieCache: MovieCache,
    private val seriesCache: SeriesCache,
    private val searchCache: SearchCache,
) : ScopedPrivateDataCleaner {
    override suspend fun clear(scope: HomeScope) {
        homeCache.clear(scope)
        movieCache.clear(scope)
        seriesCache.clear(scope)
        searchCache.clear(scope)
    }
}
