package dev.chaichai.mobile.platform.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class Compatibility { Supported, BestEffort, Incompatible }

data class DiscoveredServer(
    val id: String,
    val name: String,
    val version: String,
    val compatibility: Compatibility,
)

enum class ProbeFailure {
    Timeout,
    Tls,
    Unreachable,
    TransportPolicy,
    InvalidResponse,
    IncompatibleServer,
    TooManyRedirects,
}

sealed interface ProbeResult {
    data class Success(
        val initialAddress: ServerAddress,
        val finalAddress: ServerAddress,
        val server: DiscoveredServer,
        val redirectCount: Int,
    ) : ProbeResult

    data class Failure(val reason: ProbeFailure, val address: ServerAddress? = null) : ProbeResult
}

interface ServerProbe {
    suspend fun probe(
        initialAddress: ServerAddress,
        certificateBypassAuthority: ServerAuthority? = null,
    ): ProbeResult
}

class EmbyProbe(
    private val clients: AuthorityScopedHttpClients = AuthorityScopedHttpClients(),
    private val maxRedirects: Int = 5,
) : ServerProbe {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probe(
        initialAddress: ServerAddress,
        certificateBypassAuthority: ServerAuthority?,
    ): ProbeResult = withContext(Dispatchers.IO) {
        var current = initialAddress
        var redirectCount = 0
        while (true) {
            val request = Request.Builder().url(current.apiUrl(PUBLIC_INFO_ROUTE).toString()).get().build()
            val response = try {
                clients.forRequest(current.authority, certificateBypassAuthority).newCall(request).execute()
            } catch (_: SocketTimeoutException) {
                return@withContext ProbeResult.Failure(ProbeFailure.Timeout, current)
            } catch (_: SSLException) {
                return@withContext ProbeResult.Failure(ProbeFailure.Tls, current)
            } catch (_: UnknownHostException) {
                return@withContext ProbeResult.Failure(ProbeFailure.Unreachable, current)
            } catch (_: ConnectException) {
                return@withContext ProbeResult.Failure(ProbeFailure.Unreachable, current)
            } catch (failure: Exception) {
                val reason = if (failure.hasTlsCause()) ProbeFailure.Tls else ProbeFailure.Unreachable
                return@withContext ProbeResult.Failure(reason, current)
            }
            response.use {
                if (it.code in REDIRECT_CODES) {
                    if (redirectCount == maxRedirects) {
                        return@withContext ProbeResult.Failure(ProbeFailure.TooManyRedirects, current)
                    }
                    val location = it.header("Location")
                        ?: return@withContext ProbeResult.Failure(ProbeFailure.InvalidResponse, current)
                    val resolved = request.url.resolve(location)
                        ?: return@withContext ProbeResult.Failure(ProbeFailure.TransportPolicy, current)
                    val redirectedBase = resolved.toString().removeSuffix("/$PUBLIC_INFO_ROUTE")
                    current = when (val validation = ServerAddress.parse(redirectedBase)) {
                        is AddressValidation.Valid -> validation.address
                        is AddressValidation.Invalid -> {
                            return@withContext ProbeResult.Failure(ProbeFailure.TransportPolicy, current)
                        }
                    }
                    redirectCount += 1
                    continue
                }
                if (!it.isSuccessful) return@withContext ProbeResult.Failure(ProbeFailure.InvalidResponse, current)
                val body = it.body.string()
                val info = try {
                    json.decodeFromString<PublicServerInfo>(body)
                } catch (_: Exception) {
                    return@withContext ProbeResult.Failure(ProbeFailure.InvalidResponse, current)
                }
                if (info.id.isBlank() || info.version.isBlank()) {
                    return@withContext ProbeResult.Failure(ProbeFailure.InvalidResponse, current)
                }
                val compatibility = compatibilityOf(info.version)
                if (compatibility == Compatibility.Incompatible) {
                    return@withContext ProbeResult.Failure(ProbeFailure.IncompatibleServer, current)
                }
                return@withContext ProbeResult.Success(
                    initialAddress = initialAddress,
                    finalAddress = current,
                    server = DiscoveredServer(info.id, info.name.ifBlank { "Emby Server" }, info.version, compatibility),
                    redirectCount = redirectCount,
                )
            }
        }
        error("unreachable")
    }

    private fun compatibilityOf(version: String): Compatibility {
        val numbers = version.split('.').mapNotNull(String::toIntOrNull)
        if (numbers.size < 2 || numbers[0] < 4) return Compatibility.Incompatible
        return if (numbers[0] == 4 && numbers[1] in SUPPORTED_MINOR_LINES) {
            Compatibility.Supported
        } else {
            Compatibility.BestEffort
        }
    }

    @Serializable
    private data class PublicServerInfo(
        @SerialName("Id") val id: String = "",
        @SerialName("ServerName") val name: String = "",
        @SerialName("Version") val version: String = "",
    )

    companion object {
        private const val PUBLIC_INFO_ROUTE = "System/Info/Public"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SUPPORTED_MINOR_LINES = setOf(8, 9)
    }
}

internal fun Throwable.hasTlsCause(): Boolean = generateSequence(this) { it.cause }
    .any { it is SSLException || it is java.security.cert.CertificateException }
