package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SubtitleProviderConfigStoreTest {

    @Test
    fun in_memory_store_has_no_providers_by_default() {
        assertTrue(InMemorySubtitleProviderConfigStore().load().providers.isEmpty())
    }

    @Test
    fun shared_preferences_store_defaults_to_empty() {
        val store = SharedPreferencesSubtitleProviderConfigStore(RuntimeEnvironment.getApplication())
        assertTrue(store.load().providers.isEmpty())
    }

    @Test
    fun shared_preferences_store_round_trips_providers_with_routing() {
        val store = SharedPreferencesSubtitleProviderConfigStore(RuntimeEnvironment.getApplication())
        store.setProviders(
            listOf(
                SubtitleProvider("a", "OpenSubs", "https://o.example", enabled = true),
                SubtitleProvider(
                    "b", "Proxied", "https://p.example", enabled = false,
                    routing = SubtitleProviderRouting.Proxy(
                        ServerProxyConfig(ProxyKind.Socks5, "px.example", 1080, enabled = true, hasCredentials = true),
                    ),
                ),
            ),
        )
        val loaded = SharedPreferencesSubtitleProviderConfigStore(RuntimeEnvironment.getApplication()).load().providers
        assertEquals(2, loaded.size)
        assertEquals("OpenSubs", loaded[0].name)
        assertTrue(loaded[0].routing is SubtitleProviderRouting.Direct)
        assertFalse(loaded[1].enabled)
        val proxy = loaded[1].routing as SubtitleProviderRouting.Proxy
        assertEquals(ProxyKind.Socks5, proxy.config.kind)
        assertEquals("px.example", proxy.config.host)
        assertTrue(proxy.config.hasCredentials)
    }
}
