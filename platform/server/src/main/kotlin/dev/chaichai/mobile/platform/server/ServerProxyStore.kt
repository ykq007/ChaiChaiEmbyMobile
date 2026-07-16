package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One persisted proxy config entry. Non-secret ONLY: host, port, kind, enabled and LAN bypass. The
 * proxy username/password never live here — they are held in [ProxyCredentialVault]. [hasCredentials]
 * is derived from the vault at read time, so the persisted config can never disagree with (or leak)
 * the secret's presence.
 */
@Serializable
data class ProxyConfigRecord(
    val serverId: String,
    val kind: String = ProxyKind.Http.name,
    val host: String = "",
    val port: Int = 0,
    val enabled: Boolean = false,
    val lanBypass: Boolean = false,
)

@Serializable
data class ProxyStoreSnapshot(
    val version: Int = ServerProxyStore.CURRENT_VERSION,
    val entries: List<ProxyConfigRecord> = emptyList(),
)

/**
 * Keystore-protected store for proxy authentication secrets, mirroring [KeystoreSessionVault]. Kept
 * behind an interface so the non-secret config store stays JVM-unit-testable with an in-memory fake.
 */
interface ProxyCredentialVault {
    fun load(serverId: String): ProxyCredentials?
    fun save(serverId: String, credentials: ProxyCredentials)
    fun remove(serverId: String)
}

class InMemoryProxyCredentialVault : ProxyCredentialVault {
    private val map = mutableMapOf<String, ProxyCredentials>()
    override fun load(serverId: String): ProxyCredentials? = map[serverId]
    override fun save(serverId: String, credentials: ProxyCredentials) { map[serverId] = credentials }
    override fun remove(serverId: String) { map.remove(serverId) }
}

/**
 * Versioned scoped-JSON store of per-server Proxy Routing configuration. Non-secret config lives in
 * the injected [RegistryPersistence] (reused from the #28 registry seam); credentials live only in
 * the Keystore-protected [ProxyCredentialVault]. Migration: a persistence with no proxy data (every
 * pre-#30 install) reads back as [ServerProxyConfig.Direct] for every server, with no credentials.
 */
class ServerProxyStore(
    private val persistence: RegistryPersistence,
    private val credentials: ProxyCredentialVault,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun snapshot(): ProxyStoreSnapshot {
        val raw = persistence.read() ?: return ProxyStoreSnapshot()
        return try {
            migrate(json.decodeFromString<ProxyStoreSnapshot>(raw))
        } catch (_: Exception) {
            ProxyStoreSnapshot()
        }
    }

    /** The non-secret config for [serverId]; [ServerProxyConfig.Direct] when nothing is stored. */
    fun config(serverId: String): ServerProxyConfig {
        val record = snapshot().entries.firstOrNull { it.serverId == serverId }
            ?: return ServerProxyConfig.Direct
        return record.toConfig(hasCredentials = credentials.load(serverId) != null)
    }

    /** The proxy secret for [serverId], if any. Only the routing chokepoint should call this. */
    fun credentialsFor(serverId: String): ProxyCredentials? = credentials.load(serverId)

    /**
     * Persist [config] for [serverId] and reconcile the secret. hasCredentials=false clears any
     * stored secret; hasCredentials=true with a non-null [newCredentials] (re)stores it; a null
     * [newCredentials] leaves an existing secret untouched.
     */
    fun update(serverId: String, config: ServerProxyConfig, newCredentials: ProxyCredentials?) {
        val current = snapshot()
        val record = ProxyConfigRecord(
            serverId = serverId,
            kind = config.kind.name,
            host = config.host.trim(),
            port = config.port,
            enabled = config.enabled,
            lanBypass = config.lanBypass,
        )
        val entries = current.entries.filterNot { it.serverId == serverId } + record
        persistence.write(json.encodeToString(current.copy(version = CURRENT_VERSION, entries = entries)))
        if (!config.hasCredentials) {
            credentials.remove(serverId)
        } else if (newCredentials != null) {
            credentials.save(serverId, newCredentials)
        }
    }

    private fun migrate(snapshot: ProxyStoreSnapshot): ProxyStoreSnapshot = when (snapshot.version) {
        CURRENT_VERSION -> snapshot
        else -> snapshot.copy(version = CURRENT_VERSION)
    }

    private fun ProxyConfigRecord.toConfig(hasCredentials: Boolean) = ServerProxyConfig(
        kind = runCatching { ProxyKind.valueOf(kind) }.getOrDefault(ProxyKind.Http),
        host = host,
        port = port,
        enabled = enabled,
        lanBypass = lanBypass,
        hasCredentials = hasCredentials,
    )

    companion object {
        const val CURRENT_VERSION = 1
    }
}
