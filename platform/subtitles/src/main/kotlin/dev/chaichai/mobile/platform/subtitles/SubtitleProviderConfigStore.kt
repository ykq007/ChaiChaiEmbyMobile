package dev.chaichai.mobile.platform.subtitles

import android.content.Context
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A user-configured subtitle provider as the store models it. There are NO embedded defaults; the user
 * supplies the name and base URL. [id] is stable across renames and is the key under which any
 * per-provider account credentials and proxy credentials are stored. [routing] is the per-provider
 * Proxy Routing choice — default [SubtitleProviderRouting.Direct] so every provider connects directly
 * unless the user routes it through a proxy. Non-secret: never carries a username/password.
 */
data class SubtitleProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val routing: SubtitleProviderRouting = SubtitleProviderRouting.Direct,
) {
    /** The provider's target host (for LAN-bypass decisions), or blank if the base URL is malformed. */
    fun hostOrBlank(): String = runCatching { baseUrl.trimEnd('/').toHttpUrl().host }.getOrDefault("")
}

/** Persisted subtitle provider configuration. Empty by default (no providers). */
data class SubtitleProviderStoreState(val providers: List<SubtitleProvider> = emptyList())

/**
 * Reads/writes the subtitle provider list. Providers are user-owned; the default is an empty list, so
 * nothing contacts any provider until the user adds one. Kept behind an interface so the coordinator
 * and manager stay JVM-unit-testable against an in-memory fake.
 */
interface SubtitleProviderConfigStore {
    fun load(): SubtitleProviderStoreState
    fun setProviders(providers: List<SubtitleProvider>)
}

/** In-memory store (tests, and a safe fallback). Starts with no providers. */
class InMemorySubtitleProviderConfigStore(
    initial: SubtitleProviderStoreState = SubtitleProviderStoreState(),
) : SubtitleProviderConfigStore {
    private var state = initial
    override fun load(): SubtitleProviderStoreState = state
    override fun setProviders(providers: List<SubtitleProvider>) { state = state.copy(providers = providers) }
}

/**
 * Scoped-JSON-over-SharedPreferences persistence, mirroring the danmaku/proxy convention (plain
 * key-value; the provider list is non-sensitive so it is not encrypted — credentials live only in the
 * Keystore vault). Schema is versioned so future additive fields decode old data unchanged.
 */
class SharedPreferencesSubtitleProviderConfigStore(context: Context) : SubtitleProviderConfigStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): SubtitleProviderStoreState {
        val providers = preferences.getString(KEY_PROVIDERS, null)
            ?.let { runCatching { json.decodeFromString<List<StoredProvider>>(it) }.getOrNull() }
            ?.map { it.toProvider() }
            ?: emptyList()
        return SubtitleProviderStoreState(providers = providers)
    }

    override fun setProviders(providers: List<SubtitleProvider>) {
        val payload = json.encodeToString(providers.map { StoredProvider.from(it) })
        preferences.edit()
            .putString(KEY_PROVIDERS, payload)
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .apply()
    }

    @Serializable
    private data class StoredProvider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val enabled: Boolean = true,
        val routingProxy: StoredProxy? = null,
    ) {
        fun toProvider(): SubtitleProvider = SubtitleProvider(
            id = id,
            name = name,
            baseUrl = baseUrl,
            enabled = enabled,
            routing = routingProxy?.let { SubtitleProviderRouting.Proxy(it.toConfig()) }
                ?: SubtitleProviderRouting.Direct,
        )

        companion object {
            fun from(provider: SubtitleProvider): StoredProvider = StoredProvider(
                id = provider.id,
                name = provider.name,
                baseUrl = provider.baseUrl,
                enabled = provider.enabled,
                routingProxy = (provider.routing as? SubtitleProviderRouting.Proxy)?.let { StoredProxy.from(it.config) },
            )
        }
    }

    @Serializable
    private data class StoredProxy(
        val kind: String = ProxyKind.Http.name,
        val host: String = "",
        val port: Int = 0,
        val enabled: Boolean = false,
        val lanBypass: Boolean = false,
        val hasCredentials: Boolean = false,
    ) {
        fun toConfig(): ServerProxyConfig = ServerProxyConfig(
            kind = runCatching { ProxyKind.valueOf(kind) }.getOrDefault(ProxyKind.Http),
            host = host,
            port = port,
            enabled = enabled,
            lanBypass = lanBypass,
            hasCredentials = hasCredentials,
        )

        companion object {
            fun from(config: ServerProxyConfig): StoredProxy = StoredProxy(
                kind = config.kind.name,
                host = config.host,
                port = config.port,
                enabled = config.enabled,
                lanBypass = config.lanBypass,
                hasCredentials = config.hasCredentials,
            )
        }
    }

    companion object {
        internal const val PREFERENCES_NAME = "subtitle_providers"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val SCHEMA_VERSION = 1
    }
}

/**
 * Overlay the vault-derived credential presence onto the provider's non-secret config so the settings
 * UI can show/clear both provider-account and proxy credentials without ever reading the secret.
 */
internal fun SubtitleProvider.toConfig(hasCredentials: Boolean, hasProxyCredentials: Boolean): SubtitleProviderConfig =
    SubtitleProviderConfig(
        id = id,
        name = name,
        baseUrl = baseUrl,
        enabled = enabled,
        hasCredentials = hasCredentials,
        routing = when (val routing = routing) {
            is SubtitleProviderRouting.Direct -> SubtitleProviderRouting.Direct
            is SubtitleProviderRouting.Proxy ->
                SubtitleProviderRouting.Proxy(routing.config.copy(hasCredentials = hasProxyCredentials))
        },
    )
