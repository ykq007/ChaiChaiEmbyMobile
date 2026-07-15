package dev.chaichai.mobile.platform.server

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class AuthorityScopedHttpClients {
    private val normal = baseBuilder().build()

    fun forRequest(authority: ServerAuthority, bypassAuthority: ServerAuthority?): OkHttpClient {
        if (bypassAuthority != authority) return normal

        // This trust exception is reachable only through an authority equality check. Automatic
        // redirects are disabled, so a redirected authority always returns to the normal client.
        val scopedTrust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(scopedTrust), SecureRandom())
        }
        return baseBuilder()
            .sslSocketFactory(context.socketFactory, scopedTrust)
            .hostnameVerifier { host, _ -> host.equals(authority.host, ignoreCase = true) }
            .build()
    }

    private fun baseBuilder() = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(Duration.ofSeconds(12))
}
