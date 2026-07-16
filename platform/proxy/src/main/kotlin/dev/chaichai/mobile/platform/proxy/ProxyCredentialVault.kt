package dev.chaichai.mobile.platform.proxy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-protected store for proxy authentication secrets. Kept behind an interface so non-secret
 * config stores stay JVM-unit-testable with an in-memory fake. Reused by BOTH the Emby server proxy
 * store and the Danmaku endpoint manager; each supplies its own namespace so the two never share or
 * overwrite each other's secrets.
 *
 * The [key] argument is a caller-chosen scope identifier (a serverId for Emby, an endpoint id for
 * Danmaku). The vault never interprets it beyond hashing it into an entry name.
 */
interface ProxyCredentialVault {
    fun load(key: String): ProxyCredentials?
    fun save(key: String, credentials: ProxyCredentials)
    fun remove(key: String)
}

class InMemoryProxyCredentialVault : ProxyCredentialVault {
    private val map = mutableMapOf<String, ProxyCredentials>()
    override fun load(key: String): ProxyCredentials? = map[key]
    override fun save(key: String, credentials: ProxyCredentials) { map[key] = credentials }
    override fun remove(key: String) { map.remove(key) }
}

/**
 * Keystore-protected [ProxyCredentialVault]: each secret is AES/GCM-encrypted under an AndroidKeyStore
 * key and keyed by a SHA-256 hash of the scope [key], so the proxy password is never persisted in
 * cleartext and never shared across scopes.
 *
 * The preferences file, Keystore alias and entry prefix are all injectable, so independent subsystems
 * (Emby servers vs. Danmaku endpoints) get fully separate credential namespaces. Defaults preserve the
 * original #30 Emby server-proxy locations so existing stored secrets keep loading unchanged.
 */
class KeystoreProxyCredentialVault(
    context: Context,
    private val preferencesName: String = "server_proxy_credentials",
    private val keyAlias: String = "chai_chai_server_proxy_key",
    private val entryPrefix: String = "proxy_",
) : ProxyCredentialVault {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(key: String): ProxyCredentials? {
        val encrypted = preferences.getString(entryKey(key), null) ?: return null
        return try {
            val payload = json.decodeFromString<EncryptedPayload>(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_LENGTH_BITS, decode(payload.iv)))
            }
            val record = json.decodeFromString<CredentialRecord>(
                cipher.doFinal(decode(payload.ciphertext)).toString(StandardCharsets.UTF_8),
            )
            ProxyCredentials(record.username, record.password)
        } catch (_: Exception) {
            preferences.edit().remove(entryKey(key)).apply()
            null
        }
    }

    override fun save(key: String, credentials: ProxyCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val record = CredentialRecord(credentials.username, credentials.password)
        val encrypted = EncryptedPayload(
            iv = encode(cipher.iv),
            ciphertext = encode(cipher.doFinal(json.encodeToString(record).toByteArray(StandardCharsets.UTF_8))),
        )
        preferences.edit().putString(entryKey(key), json.encodeToString(encrypted)).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(entryKey(key)).apply()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun entryKey(key: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$entryPrefix$hash"
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    @Serializable
    private data class EncryptedPayload(val iv: String, val ciphertext: String)

    @Serializable
    private data class CredentialRecord(val username: String, val password: String)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
