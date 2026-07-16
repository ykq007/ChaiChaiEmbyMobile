package dev.chaichai.mobile.platform.danmaku

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persisted danmaku configuration. Disabled by default, with NO embedded endpoints. */
data class DanmakuConfig(
    val enabled: Boolean = false,
    val endpoints: List<DanmakuEndpoint> = emptyList(),
)

/**
 * Reads/writes [DanmakuConfig]. Endpoints are user-owned: the default is disabled with an empty
 * endpoint list, so nothing contacts the network until the user both configures an endpoint and
 * turns danmaku on.
 */
interface DanmakuConfigStore {
    fun load(): DanmakuConfig
    fun setEnabled(enabled: Boolean)
    fun setEndpoints(endpoints: List<DanmakuEndpoint>)
}

/** In-memory store (tests, and a safe fallback). Starts disabled with no endpoints. */
class InMemoryDanmakuConfigStore(initial: DanmakuConfig = DanmakuConfig()) : DanmakuConfigStore {
    private var config = initial
    override fun load(): DanmakuConfig = config
    override fun setEnabled(enabled: Boolean) { config = config.copy(enabled = enabled) }
    override fun setEndpoints(endpoints: List<DanmakuEndpoint>) { config = config.copy(endpoints = endpoints) }
}

/**
 * Scoped-JSON-over-SharedPreferences persistence, mirroring the platform:server convention
 * (plain key-value, not sensitive so no encryption). The endpoint list is stored as JSON.
 */
class SharedPreferencesDanmakuConfigStore(context: Context) : DanmakuConfigStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): DanmakuConfig {
        val enabled = preferences.getBoolean(KEY_ENABLED, false)
        val endpoints = preferences.getString(KEY_ENDPOINTS, null)
            ?.let { runCatching { json.decodeFromString<List<StoredEndpoint>>(it) }.getOrNull() }
            ?.map { DanmakuEndpoint(it.name, it.baseUrl) }
            ?: emptyList()
        return DanmakuConfig(enabled = enabled, endpoints = endpoints)
    }

    override fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun setEndpoints(endpoints: List<DanmakuEndpoint>) {
        val payload = json.encodeToString(endpoints.map { StoredEndpoint(it.name, it.baseUrl) })
        preferences.edit().putString(KEY_ENDPOINTS, payload).apply()
    }

    @Serializable
    private data class StoredEndpoint(val name: String, val baseUrl: String)

    companion object {
        internal const val PREFERENCES_NAME = "danmaku_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINTS = "endpoints"
    }
}
