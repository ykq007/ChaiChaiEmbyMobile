package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import dev.chaichai.mobile.platform.proxy.ProxyCredentialVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProxyStoreTest {
    private fun store(
        persistence: RegistryPersistence = InMemoryRegistryPersistence(),
        vault: ProxyCredentialVault = InMemoryProxyCredentialVault(),
    ) = ServerProxyStore(persistence, vault) to vault

    @Test fun absent_config_defaults_to_direct_disabled_no_credentials() {
        val (store, _) = store()
        val config = store.config("server-1")
        assertEquals(ServerProxyConfig.Direct, config)
        assertFalse(config.enabled)
        assertFalse(config.hasCredentials)
        assertEquals("", config.host)
        assertEquals(0, config.port)
    }

    @Test fun non_secret_config_round_trips_and_stays_separate_from_credentials() {
        val persistence = InMemoryRegistryPersistence()
        val vault = InMemoryProxyCredentialVault()
        ServerProxyStore(persistence, vault).update(
            "server-1",
            ServerProxyConfig(ProxyKind.Socks5, "proxy.example", 1080, enabled = true, lanBypass = true, hasCredentials = true),
            ProxyCredentials("alice", "s3cret"),
        )
        // The persisted non-secret JSON must not contain the password.
        val raw = persistence.read()!!
        assertFalse(raw.contains("s3cret"))
        assertFalse(raw.contains("alice"))
        // A fresh store over the same backing reads the config plus a credentials-present flag.
        val reloaded = ServerProxyStore(persistence, vault).config("server-1")
        assertEquals(ProxyKind.Socks5, reloaded.kind)
        assertEquals("proxy.example", reloaded.host)
        assertEquals(1080, reloaded.port)
        assertTrue(reloaded.enabled)
        assertTrue(reloaded.lanBypass)
        assertTrue(reloaded.hasCredentials)
        assertEquals(ProxyCredentials("alice", "s3cret"), vault.load("server-1"))
    }

    @Test fun clearing_credentials_removes_the_secret_but_keeps_config() {
        val (store, vault) = store()
        store.update("s", ServerProxyConfig(ProxyKind.Http, "p", 8080, enabled = true, hasCredentials = true), ProxyCredentials("u", "p"))
        assertTrue(store.config("s").hasCredentials)
        store.update("s", ServerProxyConfig(ProxyKind.Http, "p", 8080, enabled = true, hasCredentials = false), null)
        assertFalse(store.config("s").hasCredentials)
        assertNull(vault.load("s"))
        assertTrue(store.config("s").enabled)
    }

    @Test fun updating_other_fields_with_null_credentials_keeps_existing_secret() {
        val (store, vault) = store()
        store.update("s", ServerProxyConfig(ProxyKind.Http, "p", 8080, enabled = true, hasCredentials = true), ProxyCredentials("u", "pw"))
        store.update("s", ServerProxyConfig(ProxyKind.Http, "p2", 9090, enabled = true, hasCredentials = true), null)
        assertEquals(ProxyCredentials("u", "pw"), vault.load("s"))
        assertEquals("p2", store.config("s").host)
    }

    @Test fun each_server_keeps_its_own_config() {
        val (store, _) = store()
        store.update("a", ServerProxyConfig(ProxyKind.Http, "a.proxy", 1, enabled = true), null)
        store.update("b", ServerProxyConfig(ProxyKind.Socks5, "b.proxy", 2, enabled = false), null)
        assertEquals("a.proxy", store.config("a").host)
        assertTrue(store.config("a").enabled)
        assertEquals("b.proxy", store.config("b").host)
        assertFalse(store.config("b").enabled)
    }

    @Test fun migration_pre_proxy_registry_reads_direct_with_no_loss() {
        // A #28 registry snapshot (no proxy store written) → every server defaults to Direct.
        val registry = InMemoryRegistryPersistence(
            """{"version":1,"servers":[{"id":"x","serverId":"s","userId":"u","address":"https://s.example/emby","serverName":"S"}],"activeId":"x"}""",
        )
        // The proxy store uses a DIFFERENT backing key (its own persistence) that is empty here.
        val proxyStore = ServerProxyStore(InMemoryRegistryPersistence(), InMemoryProxyCredentialVault())
        assertEquals(ServerProxyConfig.Direct, proxyStore.config("s"))
        // The existing registry is untouched and still loads intact.
        assertTrue(ServerRegistryStore(registry).load()!!.servers.single().serverId == "s")
    }
}
