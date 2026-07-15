package dev.chaichai.mobile.platform.server

internal fun embyAuthorization(deviceId: String, userId: String? = null): String = buildString {
    append("MediaBrowser Client=\"ChaiChai Mobile\", Device=\"Android Mobile\", ")
    append("DeviceId=\"")
    append(deviceId)
    append("\", Version=\"0.1.0\"")
    userId?.let {
        append(", UserId=\"")
        append(it)
        append('"')
    }
}
