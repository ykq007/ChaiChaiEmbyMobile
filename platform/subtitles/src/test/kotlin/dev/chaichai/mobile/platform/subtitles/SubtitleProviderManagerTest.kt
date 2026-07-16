package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleProviderManagerTest {

    private var idSeq = 0
    private val vault = InMemoryProxyCredentialVault()
    private val store = InMemorySubtitleProviderConfigStore()
    private val manager = SubtitleProviderManager(
        configStore = store,
        vault = vault,
        tester = SubtitleProviderTester(SubtitleProviderHttpClients(vault)),
        idFactory = { "id-${idSeq++}" },
    )

    @Test
    fun add_name_enable_and_remove_round_trip_through_the_store() {
        val id = manager.addProvider("OpenSubs", "https://o.example")
        assertEquals(1, manager.providers().size)
        assertTrue(manager.providers().first().enabled)

        manager.renameProvider(id, "OpenSubs 2")
        assertEquals("OpenSubs 2", manager.providers().first().name)
        assertEquals(id, manager.providers().first().id) // rename preserves id

        manager.setEnabled(id, false)
        assertFalse(manager.providers().first().enabled)

        manager.removeProvider(id)
        assertTrue(manager.providers().isEmpty())
    }

    @Test
    fun account_credentials_are_stored_at_rest_keyed_by_provider_id_and_never_surfaced() {
        val id = manager.addProvider("OpenSubs", "https://o.example")
        manager.updateCredentials(id, ProxyCredentials("user", "secret"))

        assertNotNull(vault.load(SubtitleProviderHttpClients.accountCredentialKey(id)))
        assertEquals("secret", vault.load(SubtitleProviderHttpClients.accountCredentialKey(id))!!.password)
        // The UI-facing config records only presence, never the secret.
        assertTrue(manager.providers().first().hasCredentials)
    }

    @Test
    fun clearing_account_credentials_removes_the_secret() {
        val id = manager.addProvider("OpenSubs", "https://o.example")
        manager.updateCredentials(id, ProxyCredentials("user", "secret"))
        manager.updateCredentials(id, null)
        assertNull(vault.load(SubtitleProviderHttpClients.accountCredentialKey(id)))
        assertFalse(manager.providers().first().hasCredentials)
    }

    @Test
    fun proxy_and_account_credentials_use_separate_vault_namespaces() {
        val id = manager.addProvider("OpenSubs", "https://o.example")
        manager.updateCredentials(id, ProxyCredentials("acct", "acctpw"))
        manager.updateRouting(
            id,
            SubtitleProviderRouting.Proxy(ServerProxyConfig(ProxyKind.Http, "p.example", 8080, enabled = true, hasCredentials = true)),
            ProxyCredentials("proxyuser", "proxypw"),
        )
        assertEquals("acctpw", vault.load(SubtitleProviderHttpClients.accountCredentialKey(id))!!.password)
        assertEquals("proxypw", vault.load(SubtitleProviderHttpClients.proxyCredentialKey(id))!!.password)
        val routing = manager.providers().first().routing as SubtitleProviderRouting.Proxy
        assertTrue(routing.config.hasCredentials)
    }

    @Test
    fun removing_a_provider_clears_both_credential_namespaces() {
        val id = manager.addProvider("OpenSubs", "https://o.example")
        manager.updateCredentials(id, ProxyCredentials("acct", "acctpw"))
        manager.updateRouting(
            id,
            SubtitleProviderRouting.Proxy(ServerProxyConfig(ProxyKind.Http, "p.example", 8080, enabled = true, hasCredentials = true)),
            ProxyCredentials("proxyuser", "proxypw"),
        )
        manager.removeProvider(id)
        assertNull(vault.load(SubtitleProviderHttpClients.accountCredentialKey(id)))
        assertNull(vault.load(SubtitleProviderHttpClients.proxyCredentialKey(id)))
    }

    @Test
    fun reverting_routing_to_direct_clears_the_proxy_secret() {
        val id = manager.addProvider("OpenSubs", "https://o.example")
        manager.updateRouting(
            id,
            SubtitleProviderRouting.Proxy(ServerProxyConfig(ProxyKind.Http, "p.example", 8080, enabled = true, hasCredentials = true)),
            ProxyCredentials("proxyuser", "proxypw"),
        )
        manager.updateRouting(id, SubtitleProviderRouting.Direct, null)
        assertNull(vault.load(SubtitleProviderHttpClients.proxyCredentialKey(id)))
        assertTrue(manager.providers().first().routing is SubtitleProviderRouting.Direct)
    }
}
