package dev.chaichai.mobile.platform.server

import java.net.URI

enum class AddressProblem(val guidance: String) {
    Empty("Enter your Emby server hostname or its full HTTP or HTTPS address."),
    Invalid("Enter a hostname or a complete HTTP(S) Server Address."),
    UnsupportedScheme("Only HTTP and HTTPS Server Addresses are supported."),
    QueryOrFragment("Remove everything after ? or # and enter the server deployment address."),
    WebInterfacePath("Remove the Emby web or dashboard page path and enter only the server deployment address."),
}

sealed interface AddressValidation {
    data class Valid(val address: ServerAddress) : AddressValidation
    data class Invalid(val problem: AddressProblem) : AddressValidation
}

data class ServerAuthority(val scheme: String, val host: String, val port: Int)

class ServerAddress private constructor(
    val value: String,
    internal val uri: URI,
) {
    val authority = ServerAuthority(
        scheme = uri.scheme.lowercase(),
        host = uri.host.lowercase(),
        port = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80,
    )

    val isCleartext: Boolean get() = authority.scheme == "http"

    fun apiUrl(route: String): URI = URI("$value/${route.trimStart('/')}")

    override fun equals(other: Any?): Boolean = other is ServerAddress && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        private val webPath = Regex("(?i)(^|/)(web|dashboard)(/|$)|/(index|login)\\.html?$")

        fun parse(input: String): AddressValidation {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return AddressValidation.Invalid(AddressProblem.Empty)
            val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val uri = try {
                URI(candidate)
            } catch (_: Exception) {
                return AddressValidation.Invalid(AddressProblem.Invalid)
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme !in setOf("http", "https")) {
                return AddressValidation.Invalid(AddressProblem.UnsupportedScheme)
            }
            if (uri.host.isNullOrBlank() || uri.userInfo != null) {
                return AddressValidation.Invalid(AddressProblem.Invalid)
            }
            if (uri.rawQuery != null || uri.rawFragment != null) {
                return AddressValidation.Invalid(AddressProblem.QueryOrFragment)
            }
            val path = uri.rawPath.orEmpty()
            if (webPath.containsMatchIn(path)) {
                return AddressValidation.Invalid(AddressProblem.WebInterfacePath)
            }
            val normalizedPath = path.replace(Regex("/+$"), "")
            val normalized = try {
                URI(scheme, null, uri.host.lowercase(), uri.port, normalizedPath, null, null)
            } catch (_: Exception) {
                return AddressValidation.Invalid(AddressProblem.Invalid)
            }
            return AddressValidation.Valid(ServerAddress(normalized.toASCIIString(), normalized))
        }
    }
}
