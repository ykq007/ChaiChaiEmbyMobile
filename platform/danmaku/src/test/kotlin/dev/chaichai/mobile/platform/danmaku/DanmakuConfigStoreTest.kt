package dev.chaichai.mobile.platform.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DanmakuConfigStoreTest {

    @Test
    fun in_memory_store_is_disabled_with_no_endpoints_by_default() {
        val config = InMemoryDanmakuConfigStore().load()
        assertFalse(config.enabled)
        assertTrue(config.endpoints.isEmpty())
    }

    @Test
    fun shared_preferences_store_defaults_to_disabled_and_empty() {
        val context = RuntimeEnvironment.getApplication()
        val config = SharedPreferencesDanmakuConfigStore(context).load()
        assertFalse(config.enabled)
        assertTrue(config.endpoints.isEmpty())
    }

    @Test
    fun shared_preferences_store_persists_named_endpoints_and_enabled_flag() {
        val context = RuntimeEnvironment.getApplication()
        val store = SharedPreferencesDanmakuConfigStore(context)
        store.setEndpoints(
            listOf(DanmakuEndpoint("Community", "https://a.example"), DanmakuEndpoint("Backup", "https://b.example")),
        )
        store.setEnabled(true)

        val reloaded = SharedPreferencesDanmakuConfigStore(context).load()
        assertTrue(reloaded.enabled)
        assertEquals(listOf("Community", "Backup"), reloaded.endpoints.map { it.name })
        assertEquals("https://a.example", reloaded.endpoints.first().baseUrl)
    }
}
