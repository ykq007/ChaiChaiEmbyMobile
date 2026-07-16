package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuEndpointManagerTest {

    private var idSeq = 0
    private val vault = InMemoryProxyCredentialVault()
    private val store = InMemoryDanmakuConfigStore()
    private var changes = 0
    private val manager = DanmakuEndpointManager(
        configStore = store,
        vault = vault,
        tester = DanmakuEndpointTester(DanmakuEndpointHttpClients(vault)),
        onConfigChanged = { changes++ },
        idFactory = { "id-${idSeq++}" },
    )

    private fun proxy(hasCredentials: Boolean) = DanmakuEndpointRouting.Proxy(
        ServerProxyConfig(ProxyKind.Http, "p.example", 8080, enabled = true, hasCredentials = hasCredentials),
    )

    @Test
    fun add_name_and_remove_endpoint_round_trips_through_the_store() {
        val id = manager.addEndpoint("Community", "https://c.example")
        assertEquals(1, manager.endpoints().size)
        assertEquals("Community", manager.endpoints().first().name)

        manager.renameEndpoint(id, "Community 2")
        assertEquals("Community 2", manager.endpoints().first().name)
        // Rename preserves id (and therefore any bound credentials/routing).
        assertEquals(id, manager.endpoints().first().id)

        manager.removeEndpoint(id)
        assertTrue(manager.endpoints().isEmpty())
        assertTrue(changes >= 3)
    }

    @Test
    fun adding_a_proxy_override_stores_credentials_at_rest_keyed_by_endpoint_id() {
        val id = manager.addEndpoint("EP", "https://e.example")
        manager.updateRouting(id, proxy(hasCredentials = true), ProxyCredentials("user", "secret"))

        assertNotNull(vault.load(id))
        assertEquals("secret", vault.load(id)!!.password)
        // The UI-facing config reflects credential presence but never the secret itself.
        val routing = manager.endpoints().first().routing as DanmakuEndpointRouting.Proxy
        assertTrue(routing.config.hasCredentials)
        assertEquals("p.example", routing.config.host)
    }

    @Test
    fun reverting_a_proxy_to_direct_clears_the_stored_credential() {
        val id = manager.addEndpoint("EP", "https://e.example")
        manager.updateRouting(id, proxy(hasCredentials = true), ProxyCredentials("user", "secret"))
        assertNotNull(vault.load(id))

        manager.updateRouting(id, DanmakuEndpointRouting.Direct)
        assertNull(vault.load(id))
        assertTrue(manager.endpoints().first().routing is DanmakuEndpointRouting.Direct)
    }

    @Test
    fun clearing_credentials_flag_removes_the_secret_but_keeps_the_proxy() {
        val id = manager.addEndpoint("EP", "https://e.example")
        manager.updateRouting(id, proxy(hasCredentials = true), ProxyCredentials("user", "secret"))
        manager.updateRouting(id, proxy(hasCredentials = false), null)

        assertNull(vault.load(id))
        val routing = manager.endpoints().first().routing as DanmakuEndpointRouting.Proxy
        assertFalse(routing.config.hasCredentials)
    }

    @Test
    fun removing_an_endpoint_clears_its_credentials() {
        val id = manager.addEndpoint("EP", "https://e.example")
        manager.updateRouting(id, proxy(hasCredentials = true), ProxyCredentials("user", "secret"))
        manager.removeEndpoint(id)
        assertNull(vault.load(id))
    }

    @Test
    fun credentials_are_isolated_per_endpoint() {
        val a = manager.addEndpoint("A", "https://a.example")
        val b = manager.addEndpoint("B", "https://b.example")
        manager.updateRouting(a, proxy(hasCredentials = true), ProxyCredentials("ua", "pa"))
        manager.updateRouting(b, proxy(hasCredentials = true), ProxyCredentials("ub", "pb"))

        assertEquals("pa", vault.load(a)!!.password)
        assertEquals("pb", vault.load(b)!!.password)
        // Removing A's proxy never touches B's secret.
        manager.updateRouting(a, DanmakuEndpointRouting.Direct)
        assertNull(vault.load(a))
        assertEquals("pb", vault.load(b)!!.password)
    }
}
