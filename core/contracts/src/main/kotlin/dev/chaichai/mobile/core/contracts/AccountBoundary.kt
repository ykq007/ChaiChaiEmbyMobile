package dev.chaichai.mobile.core.contracts

import kotlinx.coroutines.flow.StateFlow

sealed interface SignOutState {
    data object Idle : SignOutState
    data object Syncing : SignOutState
    data class ConfirmationRequired(
        val message: String = "Watch progress hasn't synced yet. Sign out and discard it?",
    ) : SignOutState
    data object SignedOut : SignOutState
}

interface AccountBoundary {
    val signOutState: StateFlow<SignOutState>
    fun requestSignOut()
    fun confirmProgressLoss()
    fun cancelSignOut()
}
