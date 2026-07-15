package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayAuthenticationStatus
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request

fun interface SessionVerifier {
    suspend fun verify(session: StoredSession): GatewayAuthenticationStatus
}

class AuthenticatedEmbyGateway(
    private val vault: SessionVault,
    private val clients: AuthorityScopedHttpClients = AuthorityScopedHttpClients(),
) : EmbyGateway, SessionVerifier {
    private val mutableConnectionState = MutableStateFlow(GatewayConnectionState.Disconnected)
    override val connectionState: StateFlow<GatewayConnectionState> = mutableConnectionState

    var onAuthenticationExpired: ((String?) -> Unit)? = null

    fun setConnected(connected: Boolean) {
        mutableConnectionState.value = if (connected) {
            GatewayConnectionState.Connected
        } else {
            GatewayConnectionState.Disconnected
        }
    }

    override suspend fun verifyAuthentication(requestedDestination: String?): GatewayAuthenticationStatus {
        val session = vault.restore() ?: return GatewayAuthenticationStatus.Expired.also {
            setConnected(false)
            onAuthenticationExpired?.invoke(requestedDestination)
        }
        return verify(session).also { status ->
            setConnected(status == GatewayAuthenticationStatus.Valid)
            if (status == GatewayAuthenticationStatus.Expired) {
                onAuthenticationExpired?.invoke(requestedDestination)
            }
        }
    }

    override suspend fun verify(session: StoredSession): GatewayAuthenticationStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(session.address.apiUrl("Users/${session.userId}").toString())
            .header("X-Emby-Token", session.accessToken.encoded())
            .get()
            .build()
        try {
            clients.forRequest(session.address.authority, session.certificateBypassAuthority)
                .newCall(request)
                .execute()
                .use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> GatewayAuthenticationStatus.Expired
                        response.isSuccessful -> GatewayAuthenticationStatus.Valid
                        else -> GatewayAuthenticationStatus.Unavailable
                    }
                }
        } catch (_: Exception) {
            GatewayAuthenticationStatus.Unavailable
        }
    }
}
