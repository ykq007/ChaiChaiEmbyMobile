package dev.chaichai.mobile.platform.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreSessionVaultTest {
    @Test
    fun token_is_keystore_encrypted_and_restores_by_server_user_scope() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreSessionVault(context)
        vault.clear()
        val address = (ServerAddress.parse("https://media.example/emby") as AddressValidation.Valid).address
        vault.save(
            StoredSession(
                address, "server", "user", "Ada", AccessToken.fromRaw("token-secret"), null, "Cinema",
            ),
        )

        val raw = context.getSharedPreferences(KeystoreSessionVault.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .all.values.joinToString()
        assertFalse(raw.contains("token-secret"))
        assertEquals("token-secret", vault.restore()!!.accessToken.encoded())

        vault.save(
            StoredSession(
                address,
                "other-server",
                "other-user",
                "Grace",
                AccessToken.fromRaw("other-token"),
                null,
                "Archive",
            ),
        )
        val replacedRaw = context.getSharedPreferences(KeystoreSessionVault.PREFERENCES_NAME, Context.MODE_PRIVATE).all
        assertEquals(2, replacedRaw.size)
        assertFalse(replacedRaw.values.joinToString().contains("other-token"))
        assertEquals("other-server", vault.restore()!!.serverId)
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreSessionVault.KEY_ALIAS))
    }
}
