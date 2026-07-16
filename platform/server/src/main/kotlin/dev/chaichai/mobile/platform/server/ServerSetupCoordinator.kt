package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ServerSetupBoundary
import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.core.contracts.SetupFailure
import dev.chaichai.mobile.core.contracts.GatewayAuthenticationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StoredSession(
    val address: ServerAddress,
    val serverId: String,
    val userId: String,
    val username: String,
    val accessToken: AccessToken,
    val certificateBypassAuthority: ServerAuthority?,
    val serverName: String,
)

interface SessionVault {
    fun restore(): StoredSession?
    fun save(session: StoredSession)
    fun clear()

    /**
     * Every stored server-user session, active or not. Default single-session behaviour keeps
     * existing fakes compiling; the real vault enumerates all registered scopes.
     */
    fun sessions(): List<StoredSession> = listOfNotNull(restore())

    /** Re-point the active scope at an already-stored session. Returns false when none is stored. */
    fun selectActive(serverId: String, userId: String): Boolean =
        restore()?.let { it.serverId == serverId && it.userId == userId } ?: false

    /** Remove only the named server-user session, never another server's. */
    fun remove(serverId: String, userId: String) {
        val restored = restore()
        if (restored?.serverId == serverId && restored.userId == userId) clear()
    }
}

class ServerSetupCoordinator(
    private val scope: CoroutineScope,
    private val probe: ServerProbe,
    private val authenticator: InteractiveAuthenticator,
    private val vault: SessionVault,
    private val deviceId: String,
    private val sessionVerifier: SessionVerifier = SessionVerifier { GatewayAuthenticationStatus.Valid },
) : ServerSetupBoundary {
    private val mutableState = MutableStateFlow<ServerSetupState>(ServerSetupState.Restoring)
    override val state: StateFlow<ServerSetupState> = mutableState.asStateFlow()

    private var enteredAddress: ServerAddress? = null
    private var discovered: ProbeResult.Success? = null
    private var certificateBypassAuthority: ServerAuthority? = null
    private var acknowledgedCleartextAuthority: ServerAuthority? = null
    private var pendingReturnDestination: String? = null

    init {
        scope.launch {
            val restored = vault.restore()
            if (restored == null) {
                mutableState.value = ServerSetupState.EnterAddress()
            } else {
                enteredAddress = restored.address
                certificateBypassAuthority = restored.certificateBypassAuthority
                discovered = ProbeResult.Success(
                    restored.address,
                    restored.address,
                    DiscoveredServer(restored.serverId, restored.serverName, "", Compatibility.BestEffort),
                    0,
                )
                when (sessionVerifier.verify(restored)) {
                    GatewayAuthenticationStatus.Expired -> {
                        vault.clear()
                        mutableState.value = ServerSetupState.SignIn(
                            restored.address.value,
                            restored.serverName,
                            restored.username,
                            SetupFailure.InvalidCredentials,
                        )
                    }
                    GatewayAuthenticationStatus.Valid,
                    GatewayAuthenticationStatus.Unavailable,
                    -> mutableState.value = ServerSetupState.Authenticated(restored.serverName, restored.username)
                }
            }
        }
    }

    override fun submitAddress(address: String) {
        when (val validation = ServerAddress.parse(address)) {
            is AddressValidation.Invalid -> mutableState.value = ServerSetupState.EnterAddress(
                address = address,
                guidance = validation.problem.guidance,
            )
            is AddressValidation.Valid -> {
                enteredAddress = validation.address
                discovered = null
                certificateBypassAuthority = null
                acknowledgedCleartextAuthority = null
                if (validation.address.isCleartext) {
                    mutableState.value = ServerSetupState.CleartextRisk(validation.address.value)
                } else {
                    beginProbe()
                }
            }
        }
    }

    override fun acceptCleartextRisk() {
        val risk = mutableState.value as? ServerSetupState.CleartextRisk ?: return
        val address = (ServerAddress.parse(risk.address) as? AddressValidation.Valid)?.address ?: return
        acknowledgedCleartextAuthority = address.authority
        val existing = discovered
        if (existing?.finalAddress == address) showConfirmation(existing) else beginProbe()
    }

    override fun acceptCertificateBypass() {
        val risk = mutableState.value as? ServerSetupState.CertificateRisk ?: return
        val address = (ServerAddress.parse(risk.address) as? AddressValidation.Valid)?.address ?: return
        certificateBypassAuthority = address.authority
        beginProbe()
    }

    override fun confirmServer() {
        val server = discovered ?: return
        mutableState.value = ServerSetupState.SignIn(
            address = server.finalAddress.value,
            serverName = server.server.name,
        )
    }

    override fun authenticate(username: String, password: String) {
        val server = discovered ?: return
        if (password.isEmpty()) {
            mutableState.value = ServerSetupState.SignIn(
                server.finalAddress.value, server.server.name, username, SetupFailure.InvalidCredentials,
            )
            return
        }
        mutableState.value = ServerSetupState.Authenticating(server.finalAddress.value, server.server.name, username)
        scope.launch {
            when (val result = authenticator.authenticate(
                server.finalAddress,
                server.server.id,
                username,
                password,
                deviceId,
                certificateBypassAuthority,
            )) {
                is AuthenticationResult.Success -> {
                    val session = result.session
                    vault.save(
                        StoredSession(
                            session.serverAddress,
                            session.serverId,
                            session.userId,
                            session.username,
                            session.accessToken,
                            certificateBypassAuthority?.takeIf { it == session.serverAddress.authority },
                            server.server.name,
                        ),
                    )
                    mutableState.value = ServerSetupState.Authenticated(
                        server.server.name,
                        session.username,
                        pendingReturnDestination.also { pendingReturnDestination = null },
                    )
                }
                is AuthenticationResult.Failure -> {
                    val reason = result.reason.toSetupFailure()
                    mutableState.value = ServerSetupState.SignIn(
                        server.finalAddress.value,
                        server.server.name,
                        username,
                        reason,
                    )
                }
            }
        }
    }

    override fun retry() {
        val failure = mutableState.value as? ServerSetupState.Failure ?: return
        submitAddress(failure.address)
    }

    override fun authenticationExpired(requestedDestination: String?) {
        val server = discovered ?: return
        val prior = vault.restore()
        vault.clear()
        pendingReturnDestination = requestedDestination?.takeIf(::isSafeDestination)
        mutableState.value = ServerSetupState.SignIn(
            server.finalAddress.value,
            server.server.name,
            prior?.username.orEmpty(),
            SetupFailure.InvalidCredentials,
        )
    }

    override fun signedOut() {
        enteredAddress = null
        discovered = null
        certificateBypassAuthority = null
        acknowledgedCleartextAuthority = null
        pendingReturnDestination = null
        mutableState.value = ServerSetupState.EnterAddress()
    }

    /**
     * Begin registering an additional server. Resets the interactive flow to address entry but,
     * unlike [signedOut], leaves the currently active server's stored session untouched so the
     * existing scope keeps resolving until the new server authenticates.
     */
    override fun beginAddServer() {
        enteredAddress = null
        discovered = null
        certificateBypassAuthority = null
        acknowledgedCleartextAuthority = null
        pendingReturnDestination = null
        mutableState.value = ServerSetupState.EnterAddress()
    }

    private fun beginProbe() {
        val address = enteredAddress ?: return
        mutableState.value = ServerSetupState.Probing(address.value)
        scope.launch {
            when (val result = probe.probe(address, certificateBypassAuthority, acknowledgedCleartextAuthority)) {
                is ProbeResult.Success -> {
                    discovered = result
                    if (result.finalAddress.authority != certificateBypassAuthority) {
                        certificateBypassAuthority = null
                    }
                    if (result.finalAddress.isCleartext && acknowledgedCleartextAuthority != result.finalAddress.authority) {
                        mutableState.value = ServerSetupState.CleartextRisk(result.finalAddress.value)
                    } else {
                        showConfirmation(result)
                    }
                }
                is ProbeResult.Failure -> {
                    val failedAddress = result.address ?: address
                    if (result.reason == ProbeFailure.Tls && !failedAddress.isCleartext) {
                        mutableState.value = ServerSetupState.CertificateRisk(failedAddress.value)
                    } else {
                        mutableState.value = ServerSetupState.Failure(
                            reason = result.reason.toSetupFailure(),
                            address = address.value,
                            guidance = result.reason.guidance(),
                        )
                    }
                }
                is ProbeResult.CleartextRedirect -> {
                    mutableState.value = ServerSetupState.CleartextRisk(result.redirectAddress.value)
                }
            }
        }
    }

    private fun showConfirmation(result: ProbeResult.Success) {
        mutableState.value = ServerSetupState.ConfirmServer(
            enteredAddress = result.initialAddress.value,
            finalAddress = result.finalAddress.value,
            serverName = result.server.name,
            version = result.server.version,
            compatibilityWarning = if (result.server.compatibility == Compatibility.BestEffort) {
                "This server version is outside the verified 4.9 and 4.8 Supported Server Lines. Continue with best-effort compatibility."
            } else null,
        )
    }

    private fun AuthenticationFailure.toSetupFailure() = when (this) {
        AuthenticationFailure.InvalidCredentials, AuthenticationFailure.ExpiredAuthentication -> SetupFailure.InvalidCredentials
        AuthenticationFailure.InsufficientAccess -> SetupFailure.InsufficientAccess
        AuthenticationFailure.Timeout -> SetupFailure.Timeout
        AuthenticationFailure.Tls -> SetupFailure.Tls
        AuthenticationFailure.Unreachable -> SetupFailure.Unreachable
        AuthenticationFailure.TransportPolicy -> SetupFailure.TransportPolicy
        AuthenticationFailure.InvalidResponse -> SetupFailure.InvalidResponse
    }

    private fun ProbeFailure.toSetupFailure() = when (this) {
        ProbeFailure.Timeout -> SetupFailure.Timeout
        ProbeFailure.Tls -> SetupFailure.Tls
        ProbeFailure.IncompatibleServer -> SetupFailure.IncompatibleServer
        ProbeFailure.TransportPolicy, ProbeFailure.TooManyRedirects -> SetupFailure.TransportPolicy
        ProbeFailure.Unreachable -> SetupFailure.Unreachable
        ProbeFailure.InvalidResponse -> SetupFailure.InvalidResponse
    }

    private fun ProbeFailure.guidance() = when (this) {
        ProbeFailure.Timeout -> "The server did not respond in time. Check the address and network, then retry."
        ProbeFailure.Tls -> "Android could not verify this certificate. Review the address or explicitly confirm Certificate Bypass."
        ProbeFailure.TransportPolicy -> "The server requested an unsafe or invalid redirect. Verify its public address."
        ProbeFailure.IncompatibleServer -> "This server is incompatible with the required interactive authentication contract."
        ProbeFailure.TooManyRedirects -> "The server redirected too many times. Enter its final deployment address."
        ProbeFailure.Unreachable -> "The server could not be reached. Check the address, server, and network."
        ProbeFailure.InvalidResponse -> "The address responded, but not as a compatible Emby server."
    }

    private fun isSafeDestination(destination: String): Boolean =
        destination in setOf("home", "libraries", "search", "settings") ||
            MovieDetailsDestination.matches(destination)

    private companion object {
        private const val EncodedRouteSegment = "(?:[A-Za-z0-9._~!()*'-]|%[0-9A-Fa-f]{2})+"
        val MovieDetailsDestination = Regex("movies/$EncodedRouteSegment/$EncodedRouteSegment")
    }
}
