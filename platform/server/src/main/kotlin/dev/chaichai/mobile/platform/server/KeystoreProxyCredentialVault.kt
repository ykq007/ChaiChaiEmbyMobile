package dev.chaichai.mobile.platform.server

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
 * Keystore-protected [ProxyCredentialVault], mirroring [KeystoreSessionVault]: each proxy secret is
 * AES/GCM-encrypted under an AndroidKeyStore key and keyed by a SHA-256 hash of the serverId, so the
 * proxy password is never persisted in cleartext and never shared across servers.
 */
class KeystoreProxyCredentialVault(context: Context) : ProxyCredentialVault {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(serverId: String): ProxyCredentials? {
        val encrypted = preferences.getString(entryKey(serverId), null) ?: return null
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
            preferences.edit().remove(entryKey(serverId)).apply()
            null
        }
    }

    override fun save(serverId: String, credentials: ProxyCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val record = CredentialRecord(credentials.username, credentials.password)
        val encrypted = EncryptedPayload(
            iv = encode(cipher.iv),
            ciphertext = encode(cipher.doFinal(json.encodeToString(record).toByteArray(StandardCharsets.UTF_8))),
        )
        preferences.edit().putString(entryKey(serverId), json.encodeToString(encrypted)).apply()
    }

    override fun remove(serverId: String) {
        preferences.edit().remove(entryKey(serverId)).apply()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
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

    private fun entryKey(serverId: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(serverId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$ENTRY_PREFIX$hash"
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    @Serializable
    private data class EncryptedPayload(val iv: String, val ciphertext: String)

    @Serializable
    private data class CredentialRecord(val username: String, val password: String)

    private companion object {
        const val PREFERENCES_NAME = "server_proxy_credentials"
        const val KEY_ALIAS = "chai_chai_server_proxy_key"
        const val ENTRY_PREFIX = "proxy_"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
