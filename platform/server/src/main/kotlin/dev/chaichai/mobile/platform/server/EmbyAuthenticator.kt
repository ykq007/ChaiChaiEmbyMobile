package dev.chaichai.mobile.platform.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class AuthenticatedSession(
    val serverAddress: ServerAddress,
    val serverId: String,
    val userId: String,
    val username: String,
    val accessToken: String,
)

enum class AuthenticationFailure {
    InvalidCredentials,
    InsufficientAccess,
    ExpiredAuthentication,
    Timeout,
    Tls,
    Unreachable,
    TransportPolicy,
    InvalidResponse,
}

sealed interface AuthenticationResult {
    data class Success(val session: AuthenticatedSession) : AuthenticationResult
    data class Failure(val reason: AuthenticationFailure) : AuthenticationResult
}

interface InteractiveAuthenticator {
    suspend fun authenticate(
        address: ServerAddress,
        serverId: String,
        username: String,
        password: String,
        deviceId: String,
        certificateBypassAuthority: ServerAuthority? = null,
    ): AuthenticationResult
}

class EmbyAuthenticator(
    private val clients: AuthorityScopedHttpClients = AuthorityScopedHttpClients(),
) : InteractiveAuthenticator {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun authenticate(
        address: ServerAddress,
        serverId: String,
        username: String,
        password: String,
        deviceId: String,
        certificateBypassAuthority: ServerAuthority?,
    ): AuthenticationResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(AuthenticationRequest(username, password))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(address.apiUrl("Users/AuthenticateByName").toString())
            .header(
                "X-Emby-Authorization",
                "MediaBrowser Client=\"ChaiChai Mobile\", Device=\"Android Mobile\", " +
                    "DeviceId=\"$deviceId\", Version=\"0.1.0\"",
            )
            .post(body)
            .build()
        val response = try {
            clients.forRequest(address.authority, certificateBypassAuthority).newCall(request).execute()
        } catch (_: SocketTimeoutException) {
            return@withContext AuthenticationResult.Failure(AuthenticationFailure.Timeout)
        } catch (_: SSLException) {
            return@withContext AuthenticationResult.Failure(AuthenticationFailure.Tls)
        } catch (_: UnknownHostException) {
            return@withContext AuthenticationResult.Failure(AuthenticationFailure.Unreachable)
        } catch (_: ConnectException) {
            return@withContext AuthenticationResult.Failure(AuthenticationFailure.Unreachable)
        } catch (failure: Exception) {
            val reason = if (failure.hasTlsCause()) {
                AuthenticationFailure.Tls
            } else {
                AuthenticationFailure.Unreachable
            }
            return@withContext AuthenticationResult.Failure(reason)
        }
        response.use {
            if (it.code in 300..399) {
                return@withContext AuthenticationResult.Failure(AuthenticationFailure.TransportPolicy)
            }
            if (it.code == 401 || it.code == 403) {
                return@withContext AuthenticationResult.Failure(AuthenticationFailure.InvalidCredentials)
            }
            if (!it.isSuccessful) {
                return@withContext AuthenticationResult.Failure(AuthenticationFailure.InvalidResponse)
            }
            val authenticated = try {
                json.decodeFromString<AuthenticationResponse>(it.body.string())
            } catch (_: Exception) {
                return@withContext AuthenticationResult.Failure(AuthenticationFailure.InvalidResponse)
            }
            if (authenticated.user.policy.isDisabled || !authenticated.user.policy.enableMediaPlayback) {
                return@withContext AuthenticationResult.Failure(AuthenticationFailure.InsufficientAccess)
            }
            if (authenticated.accessToken.isBlank() || authenticated.user.id.isBlank()) {
                return@withContext AuthenticationResult.Failure(AuthenticationFailure.InvalidResponse)
            }
            AuthenticationResult.Success(
                AuthenticatedSession(
                    serverAddress = address,
                    serverId = serverId,
                    userId = authenticated.user.id,
                    username = authenticated.user.name.ifBlank { username },
                    accessToken = authenticated.accessToken,
                ),
            )
        }
    }

    @Serializable
    private data class AuthenticationRequest(
        @SerialName("Username") val username: String,
        @SerialName("Pw") val password: String,
    )

    @Serializable
    private data class AuthenticationResponse(
        @SerialName("AccessToken") val accessToken: String = "",
        @SerialName("User") val user: User = User(),
    )

    @Serializable
    private data class User(
        @SerialName("Id") val id: String = "",
        @SerialName("Name") val name: String = "",
        @SerialName("Policy") val policy: Policy = Policy(),
    )

    @Serializable
    private data class Policy(
        @SerialName("IsDisabled") val isDisabled: Boolean = false,
        @SerialName("EnableMediaPlayback") val enableMediaPlayback: Boolean = true,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
