package dev.chaichai.mobile.core.contracts

import kotlinx.coroutines.flow.StateFlow

enum class SetupFailure {
    InvalidAddress,
    Timeout,
    Tls,
    TransportPolicy,
    IncompatibleServer,
    InvalidCredentials,
    InsufficientAccess,
    Unreachable,
    InvalidResponse,
}

sealed interface ServerSetupState {
    data object Restoring : ServerSetupState
    data class EnterAddress(val address: String = "", val guidance: String? = null) : ServerSetupState
    data class CleartextRisk(val address: String) : ServerSetupState
    data class Probing(val address: String) : ServerSetupState
    data class CertificateRisk(val address: String) : ServerSetupState
    data class ConfirmServer(
        val enteredAddress: String,
        val finalAddress: String,
        val serverName: String,
        val version: String,
        val compatibilityWarning: String?,
    ) : ServerSetupState
    data class SignIn(
        val address: String,
        val serverName: String,
        val username: String = "",
        val error: SetupFailure? = null,
    ) : ServerSetupState
    data class Authenticating(val address: String, val serverName: String, val username: String) : ServerSetupState
    data class Authenticated(
        val serverName: String,
        val username: String,
        val returnDestination: String? = null,
    ) : ServerSetupState
    data class Failure(
        val reason: SetupFailure,
        val address: String,
        val username: String = "",
        val guidance: String,
    ) : ServerSetupState
}

interface ServerSetupBoundary {
    val state: StateFlow<ServerSetupState>
    fun submitAddress(address: String)
    fun acceptCleartextRisk()
    fun acceptCertificateBypass()
    fun confirmServer()
    fun authenticate(username: String, password: String)
    fun retry()
    fun authenticationExpired(requestedDestination: String?)
    fun signedOut() = Unit
}
