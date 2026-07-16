package dev.chaichai.mobile.platform.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerRegistryStoreTest {
    @Test
    fun `absent registry loads as null so migration can seed it`() {
        val store = ServerRegistryStore(InMemoryRegistryPersistence())
        assertNull(store.load())
    }

    @Test
    fun `round trips entries active pointer and version`() {
        val persistence = InMemoryRegistryPersistence()
        val store = ServerRegistryStore(persistence)
        val snapshot = ServerRegistrySnapshot(
            servers = listOf(
                ServerRegistryEntry("id-1", "s1", "u1", "https://a.example", "A", alias = "Home"),
                ServerRegistryEntry("id-2", "s2", "u2", "https://b.example", "B"),
            ),
            activeId = "id-2",
        )
        store.save(snapshot)

        val loaded = store.load()!!
        assertEquals(ServerRegistryStore.CURRENT_VERSION, loaded.version)
        assertEquals(listOf("id-1", "id-2"), loaded.servers.map { it.id })
        assertEquals("Home", loaded.servers.first().alias)
        assertEquals("id-2", loaded.activeId)
    }

    @Test
    fun `unversioned older payload is upgraded to the current version on load`() {
        val persistence = InMemoryRegistryPersistence(
            """{"version":0,"servers":[{"id":"id-1","serverId":"s1","userId":"u1","address":"https://a.example","serverName":"A"}],"activeId":"id-1"}""",
        )
        val store = ServerRegistryStore(persistence)
        val loaded = store.load()!!
        assertEquals(ServerRegistryStore.CURRENT_VERSION, loaded.version)
        assertEquals("id-1", loaded.activeId)
    }
}
