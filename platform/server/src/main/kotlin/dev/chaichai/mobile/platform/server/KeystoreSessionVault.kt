package dev.chaichai.mobile.platform.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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

class KeystoreSessionVault(context: Context) : SessionVault {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun restore(): StoredSession? {
        val scope = preferences.getString(CURRENT_SCOPE, null) ?: return null
        val encrypted = preferences.getString(sessionKey(scope), null) ?: return null
        return try {
            val payload = json.decodeFromString<EncryptedPayload>(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_LENGTH_BITS, decode(payload.iv)))
            }
            val record = json.decodeFromString<SessionRecord>(
                cipher.doFinal(decode(payload.ciphertext)).toString(StandardCharsets.UTF_8),
            )
            val address = (ServerAddress.parse(record.address) as? AddressValidation.Valid)?.address ?: return null
            StoredSession(
                address,
                record.serverId,
                record.userId,
                record.username,
                AccessToken.fromRaw(record.accessToken),
                record.bypassScheme?.let { scheme ->
                    ServerAuthority(scheme, record.bypassHost.orEmpty(), record.bypassPort ?: return@let null)
                },
                record.serverName,
            )
        } catch (_: Exception) {
            preferences.edit().remove(sessionKey(scope)).remove(CURRENT_SCOPE).apply()
            null
        }
    }

    override fun save(session: StoredSession) {
        val scope = scopeOf(session.serverId, session.userId)
        val previousScope = preferences.getString(CURRENT_SCOPE, null)
        val record = SessionRecord(
            address = session.address.value,
            serverId = session.serverId,
            userId = session.userId,
            username = session.username,
            accessToken = session.accessToken.encoded(),
            bypassScheme = session.certificateBypassAuthority?.scheme,
            bypassHost = session.certificateBypassAuthority?.host,
            bypassPort = session.certificateBypassAuthority?.port,
            serverName = session.serverName,
        )
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, encryptionKey())
        }
        val encrypted = EncryptedPayload(
            iv = encode(cipher.iv),
            ciphertext = encode(cipher.doFinal(json.encodeToString(record).toByteArray(StandardCharsets.UTF_8))),
        )
        preferences.edit().apply {
            if (previousScope != null && previousScope != scope) remove(sessionKey(previousScope))
        }
            .putString(sessionKey(scope), json.encodeToString(encrypted))
            .putString(CURRENT_SCOPE, scope)
            .apply()
    }

    override fun clear() {
        val scope = preferences.getString(CURRENT_SCOPE, null)
        preferences.edit().remove(CURRENT_SCOPE).apply {
            if (scope != null) remove(sessionKey(scope))
        }.apply()
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

    private fun scopeOf(serverId: String, userId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$serverId\u0000$userId".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun sessionKey(scope: String) = "session_$scope"
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    @Serializable
    private data class EncryptedPayload(val iv: String, val ciphertext: String)

    @Serializable
    private data class SessionRecord(
        val address: String,
        val serverId: String,
        val userId: String,
        val username: String,
        val accessToken: String,
        val bypassScheme: String?,
        val bypassHost: String?,
        val bypassPort: Int?,
        val serverName: String,
    )

    companion object {
        internal const val PREFERENCES_NAME = "server_user_sessions"
        internal const val KEY_ALIAS = "chai_chai_server_session_key"
        private const val CURRENT_SCOPE = "current_scope"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
    }
}
