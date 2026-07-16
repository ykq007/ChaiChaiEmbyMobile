package dev.chaichai.mobile.platform.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A single persisted registry entry. Non-secret metadata only: the credential, Certificate Bypass,
 * caches and progress for this server stay in their own scope-keyed stores and are addressed by
 * [serverId] + [userId] (the same scope key the pre-#28 single-server install already used).
 */
@Serializable
data class ServerRegistryEntry(
    val id: String,
    val serverId: String,
    val userId: String,
    val address: String,
    val serverName: String,
    val alias: String? = null,
    val iconGlyph: String = "",
    val iconColor: Int = 0xFF3F51B5.toInt(),
    val iconShape: String = "Rounded",
)

@Serializable
data class ServerRegistrySnapshot(
    val version: Int = ServerRegistryStore.CURRENT_VERSION,
    val servers: List<ServerRegistryEntry> = emptyList(),
    val activeId: String? = null,
)

/** Persistence seam so the store can be unit-tested off-device (SharedPreferences on Android). */
interface RegistryPersistence {
    fun read(): String?
    fun write(value: String)
}

class InMemoryRegistryPersistence(initial: String? = null) : RegistryPersistence {
    private var value: String? = initial
    override fun read(): String? = value
    override fun write(value: String) { this.value = value }
}

/**
 * Versioned scoped-JSON registry of configured servers. Replaces the implicit "one server = the one
 * vault session" assumption with an explicit, ordered, versioned registry. Migration lifts the
 * existing single-server install into this registry without rewriting any per-scope data.
 */
class ServerRegistryStore(private val persistence: RegistryPersistence) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): ServerRegistrySnapshot? {
        val raw = persistence.read() ?: return null
        return try {
            migrate(json.decodeFromString<ServerRegistrySnapshot>(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: ServerRegistrySnapshot) {
        persistence.write(json.encodeToString(snapshot.copy(version = CURRENT_VERSION)))
    }

    /** Forward-compatible migration hook. Version 1 is the initial explicit-registry format. */
    private fun migrate(snapshot: ServerRegistrySnapshot): ServerRegistrySnapshot = when (snapshot.version) {
        CURRENT_VERSION -> snapshot
        else -> snapshot.copy(version = CURRENT_VERSION)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
