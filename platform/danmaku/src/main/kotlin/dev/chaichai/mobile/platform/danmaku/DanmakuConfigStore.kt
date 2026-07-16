package dev.chaichai.mobile.platform.danmaku

import android.content.Context
import dev.chaichai.mobile.core.contracts.DanmakuMediaKey
import dev.chaichai.mobile.core.contracts.DanmakuPosition
import dev.chaichai.mobile.core.contracts.DanmakuTuning
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A manually chosen match remembered for one [DanmakuMediaKey] scope. */
data class DanmakuRememberedMatch(val endpointName: String, val mediaId: String, val title: String)

/** Persisted danmaku configuration. Disabled by default, with NO embedded endpoints. */
data class DanmakuConfig(
    val enabled: Boolean = false,
    val endpoints: List<DanmakuEndpoint> = emptyList(),
    val rememberedMatches: Map<String, DanmakuRememberedMatch> = emptyMap(),
    val tunings: Map<String, DanmakuTuning> = emptyMap(),
)

/**
 * Reads/writes [DanmakuConfig]. Endpoints are user-owned: the default is disabled with an empty
 * endpoint list, so nothing contacts the network until the user both configures an endpoint and
 * turns danmaku on.
 *
 * Remembered matches and tuning are keyed by [DanmakuMediaKey.scopeKey], which embeds the server id
 * so a choice made on one server/media scope can never be read back for another.
 */
interface DanmakuConfigStore {
    fun load(): DanmakuConfig
    fun setEnabled(enabled: Boolean)
    fun setEndpoints(endpoints: List<DanmakuEndpoint>)

    fun rememberedMatch(key: DanmakuMediaKey): DanmakuRememberedMatch? = load().rememberedMatches[key.scopeKey()]
    fun rememberMatch(key: DanmakuMediaKey, match: DanmakuRememberedMatch)
    fun clearMatch(key: DanmakuMediaKey)

    fun tuning(key: DanmakuMediaKey): DanmakuTuning = load().tunings[key.scopeKey()] ?: DanmakuTuning.Neutral
    fun setTuning(key: DanmakuMediaKey, tuning: DanmakuTuning)
}

/** Deterministic, server-scoped key string identifying a movie or a single episode. */
fun DanmakuMediaKey.scopeKey(): String = when (this) {
    is DanmakuMediaKey.Movie -> "movie:$serverId:$itemId"
    is DanmakuMediaKey.Episode -> "episode:$serverId:$seriesId:$seasonNumber:$episodeNumber"
}

/** In-memory store (tests, and a safe fallback). Starts disabled with no endpoints. */
class InMemoryDanmakuConfigStore(initial: DanmakuConfig = DanmakuConfig()) : DanmakuConfigStore {
    private var config = initial
    override fun load(): DanmakuConfig = config
    override fun setEnabled(enabled: Boolean) { config = config.copy(enabled = enabled) }
    override fun setEndpoints(endpoints: List<DanmakuEndpoint>) { config = config.copy(endpoints = endpoints) }
    override fun rememberMatch(key: DanmakuMediaKey, match: DanmakuRememberedMatch) {
        config = config.copy(rememberedMatches = config.rememberedMatches + (key.scopeKey() to match))
    }
    override fun clearMatch(key: DanmakuMediaKey) {
        config = config.copy(rememberedMatches = config.rememberedMatches - key.scopeKey())
    }
    override fun setTuning(key: DanmakuMediaKey, tuning: DanmakuTuning) {
        config = config.copy(tunings = config.tunings + (key.scopeKey() to tuning))
    }
}

/**
 * Scoped-JSON-over-SharedPreferences persistence, mirroring the platform:server convention
 * (plain key-value, not sensitive so no encryption). Schema v1 (#26) only ever wrote [KEY_ENABLED]
 * and [KEY_ENDPOINTS]; schema v2 (#27) adds remembered matches and tuning without touching those
 * keys, so v1 data loads unchanged with empty matches/tuning maps — see
 * [SharedPreferencesDanmakuConfigStoreTest] migration coverage.
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
        // Schema version is absent for v1 (#26) data; matches/tuning simply default to empty.
        val matches = preferences.getString(KEY_MATCHES, null)
            ?.let { runCatching { json.decodeFromString<Map<String, StoredMatch>>(it) }.getOrNull() }
            ?.mapValues { (_, stored) -> DanmakuRememberedMatch(stored.endpointName, stored.mediaId, stored.title) }
            ?: emptyMap()
        val tunings = preferences.getString(KEY_TUNINGS, null)
            ?.let { runCatching { json.decodeFromString<Map<String, StoredTuning>>(it) }.getOrNull() }
            ?.mapValues { (_, stored) -> stored.toTuning() }
            ?: emptyMap()
        return DanmakuConfig(enabled = enabled, endpoints = endpoints, rememberedMatches = matches, tunings = tunings)
    }

    override fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION).apply()
    }

    override fun setEndpoints(endpoints: List<DanmakuEndpoint>) {
        val payload = json.encodeToString(endpoints.map { StoredEndpoint(it.name, it.baseUrl) })
        preferences.edit().putString(KEY_ENDPOINTS, payload).putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION).apply()
    }

    override fun rememberMatch(key: DanmakuMediaKey, match: DanmakuRememberedMatch) {
        val current = load().rememberedMatches + (
            key.scopeKey() to match
        )
        writeMatches(current)
    }

    override fun clearMatch(key: DanmakuMediaKey) {
        writeMatches(load().rememberedMatches - key.scopeKey())
    }

    override fun setTuning(key: DanmakuMediaKey, tuning: DanmakuTuning) {
        val current = load().tunings + (key.scopeKey() to tuning)
        val stored = current.mapValues { (_, value) -> StoredTuning.from(value) }
        preferences.edit()
            .putString(KEY_TUNINGS, json.encodeToString(stored))
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .apply()
    }

    private fun writeMatches(matches: Map<String, DanmakuRememberedMatch>) {
        val stored = matches.mapValues { (_, value) -> StoredMatch(value.endpointName, value.mediaId, value.title) }
        preferences.edit()
            .putString(KEY_MATCHES, json.encodeToString(stored))
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .apply()
    }

    @Serializable
    private data class StoredEndpoint(val name: String, val baseUrl: String)

    @Serializable
    private data class StoredMatch(val endpointName: String, val mediaId: String, val title: String)

    @Serializable
    private data class StoredTuning(
        val timingOffsetMillis: Long = 0L,
        val speed: Float = 1f,
        val textScale: Float = 1f,
        val opacity: Float = 1f,
        val screenFraction: Float = 1f,
        val allowedPositions: List<String> = listOf("Scroll", "Top", "Bottom"),
    ) {
        fun toTuning(): DanmakuTuning = DanmakuTuning(
            timingOffsetMillis = timingOffsetMillis,
            speed = speed,
            textScale = textScale,
            opacity = opacity,
            screenFraction = screenFraction,
            allowedPositions = allowedPositions.mapNotNull {
                runCatching { DanmakuPosition.valueOf(it) }.getOrNull()
            }.toSet().ifEmpty { setOf(DanmakuPosition.Scroll, DanmakuPosition.Top, DanmakuPosition.Bottom) },
        )

        companion object {
            fun from(tuning: DanmakuTuning): StoredTuning = StoredTuning(
                timingOffsetMillis = tuning.timingOffsetMillis,
                speed = tuning.speed,
                textScale = tuning.textScale,
                opacity = tuning.opacity,
                screenFraction = tuning.screenFraction,
                allowedPositions = tuning.allowedPositions.map { it.name },
            )
        }
    }

    companion object {
        internal const val PREFERENCES_NAME = "danmaku_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINTS = "endpoints"
        private const val KEY_MATCHES = "matches_v2"
        private const val KEY_TUNINGS = "tunings_v2"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val SCHEMA_VERSION = 2
    }
}
