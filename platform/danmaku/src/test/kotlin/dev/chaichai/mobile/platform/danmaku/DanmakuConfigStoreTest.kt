package dev.chaichai.mobile.platform.danmaku

import dev.chaichai.mobile.core.contracts.DanmakuMediaKey
import dev.chaichai.mobile.core.contracts.DanmakuTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun pre_27_v1_data_written_with_only_enabled_and_endpoints_keys_migrates_without_loss() {
        val context = RuntimeEnvironment.getApplication()
        // Write RAW v1 (#26) shaped data directly: only the enabled flag and endpoints JSON, with
        // no schema_version, matches_v2 or tunings_v2 keys — exactly what #26 ever persisted.
        context.getSharedPreferences("danmaku_config", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", true)
            .putString("endpoints", """[{"name":"Community","baseUrl":"https://a.example"}]""")
            .apply()

        val migrated = SharedPreferencesDanmakuConfigStore(context).load()
        assertTrue(migrated.enabled)
        assertEquals(listOf("Community"), migrated.endpoints.map { it.name })
        assertTrue(migrated.rememberedMatches.isEmpty())
        assertTrue(migrated.tunings.isEmpty())
    }

    @Test
    fun remembered_matches_and_tuning_are_scoped_per_key_and_survive_reload() {
        val context = RuntimeEnvironment.getApplication()
        val store = SharedPreferencesDanmakuConfigStore(context)
        val movie = DanmakuMediaKey.Movie("server-1", "movie-1")
        val episode = DanmakuMediaKey.Episode("server-1", "series-1", 1, 2)

        store.rememberMatch(movie, DanmakuRememberedMatch("Community", "media-1", "Movie Title"))
        store.setTuning(episode, DanmakuTuning(timingOffsetMillis = 500L, speed = 1.5f))

        val reloaded = SharedPreferencesDanmakuConfigStore(context)
        assertEquals("media-1", reloaded.rememberedMatch(movie)?.mediaId)
        assertNull(reloaded.rememberedMatch(episode))
        assertEquals(DanmakuTuning.Neutral, reloaded.tuning(movie))
        assertEquals(500L, reloaded.tuning(episode).timingOffsetMillis)
        assertEquals(1.5f, reloaded.tuning(episode).speed)

        reloaded.clearMatch(movie)
        assertNull(reloaded.rememberedMatch(movie))
    }
}
