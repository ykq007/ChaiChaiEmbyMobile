package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.ConfiguredServer
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.RemovalConfirmation
import dev.chaichai.mobile.core.contracts.ServerConnectionHealth
import dev.chaichai.mobile.core.contracts.ServerDirectory
import dev.chaichai.mobile.core.contracts.ServerDirectoryState
import dev.chaichai.mobile.core.contracts.ServerIcon
import dev.chaichai.mobile.core.contracts.ServerIconShape
import dev.chaichai.mobile.core.contracts.ServerRemovalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Real [ServerDirectory] over a versioned [ServerRegistryStore] and the multi-session
 * [SessionVault]. The registry holds ordered, non-secret metadata; every credential, cache, media
 * identity, progress record and Certificate Bypass stays keyed to its own server scope and is only
 * ever addressed through [SessionVault]/[ScopedPrivateDataCleaner]/[ProgressAccountSync] by the
 * exact (serverId, userId) of the entry being acted on — so nothing crosses a server boundary.
 *
 * Switching is restart-free: [selectServer] re-points the vault's active scope and invokes
 * [onActiveRebind], which resets the shared gateway so Home/library/search reload against the newly
 * active scope. The gateway, playback and progress boundaries already resolve their scope live from
 * the vault on every operation, so no boundary needs to be reconstructed.
 */
class ServerDirectoryManager(
    private val scope: CoroutineScope,
    private val store: ServerRegistryStore,
    private val vault: SessionVault,
    private val progress: ProgressAccountSync,
    private val privateData: ScopedPrivateDataCleaner,
    private val onActiveRebind: () -> Unit,
    private val onBeginAddServer: () -> Unit,
) : ServerDirectory {
    private var snapshot: ServerRegistrySnapshot = restoreOrMigrate()
    private val mutableState = MutableStateFlow(snapshot.toDirectoryState())
    override val state: StateFlow<ServerDirectoryState> = mutableState
    private val mutableRemoval = MutableStateFlow<ServerRemovalState>(ServerRemovalState.Idle)
    override val removalState: StateFlow<ServerRemovalState> = mutableRemoval

    init {
        // Keep the vault's active pointer aligned with the migrated/restored registry.
        snapshot.activeEntry()?.let { vault.selectActive(it.serverId, it.userId) }
    }

    /**
     * Ensure the currently authenticated vault session is represented in the registry and marked
     * active. Called after the shared setup flow authenticates (first server, an added server, or a
     * re-authentication), so add/first-run/re-auth all converge here without a second code path.
     */
    fun registerActiveSession() {
        val session = vault.restore() ?: return
        val existing = snapshot.servers.firstOrNull {
            it.serverId == session.serverId && it.userId == session.userId
        }
        snapshot = if (existing == null) {
            val entry = ServerRegistryEntry(
                id = UUID.randomUUID().toString(),
                serverId = session.serverId,
                userId = session.userId,
                address = session.address.value,
                serverName = session.serverName,
            )
            snapshot.copy(servers = snapshot.servers + entry, activeId = entry.id)
        } else {
            snapshot.copy(
                servers = snapshot.servers.map {
                    if (it.id == existing.id) {
                        it.copy(address = session.address.value, serverName = session.serverName)
                    } else {
                        it
                    }
                },
                activeId = existing.id,
            )
        }
        persistAndPublish()
    }

    override fun selectServer(id: String) {
        val entry = snapshot.servers.firstOrNull { it.id == id } ?: return
        if (snapshot.activeId == id) return
        vault.selectActive(entry.serverId, entry.userId)
        snapshot = snapshot.copy(activeId = id)
        persistAndPublish()
        onActiveRebind()
    }

    override fun activateScope(scope: HomeScope): Boolean {
        val entry = snapshot.servers.firstOrNull {
            it.serverId == scope.serverId && it.userId == scope.userId
        } ?: return false
        selectServer(entry.id)
        return true
    }

    override fun rename(id: String, alias: String?) {
        updateEntry(id) { it.copy(alias = alias?.trim()?.takeIf(String::isNotEmpty)) }
    }

    override fun updateIcon(id: String, icon: ServerIcon) {
        updateEntry(id) {
            it.copy(iconGlyph = icon.glyph, iconColor = icon.colorArgb, iconShape = icon.shape.name)
        }
    }

    override fun reorder(id: String, toIndex: Int) {
        val current = snapshot.servers
        val fromIndex = current.indexOfFirst { it.id == id }
        if (fromIndex < 0) return
        val target = toIndex.coerceIn(0, current.size - 1)
        if (target == fromIndex) return
        val reordered = current.toMutableList()
        val moved = reordered.removeAt(fromIndex)
        reordered.add(target, moved)
        snapshot = snapshot.copy(servers = reordered)
        persistAndPublish()
    }

    override fun editAddress(id: String, address: String) {
        val normalized = (ServerAddress.parse(address) as? AddressValidation.Valid)?.address?.value ?: return
        updateEntry(id) { it.copy(address = normalized) }
    }

    override fun beginAddServer() {
        onBeginAddServer()
    }

    override fun requestRemove(id: String) {
        val entry = snapshot.servers.firstOrNull { it.id == id } ?: run {
            mutableRemoval.value = ServerRemovalState.Idle
            return
        }
        mutableRemoval.value = ServerRemovalState.Evaluating
        scope.launch {
            val homeScope = HomeScope(entry.serverId, entry.userId)
            val unsynced = try {
                progress.hasPending(homeScope)
            } catch (_: Exception) {
                false
            }
            val affected = buildList {
                add("Saved credentials and Certificate Bypass for ${entry.serverName}")
                add("Home, library and search caches")
                add("Saved watch progress and pending sync work")
                add("Playback and appearance preferences")
            }
            val message = if (unsynced) {
                "Watch progress for ${entry.displayLabel()} hasn't finished syncing. " +
                    "Removing this server discards it along with all of its local data. Continue?"
            } else {
                "Removing ${entry.displayLabel()} deletes all of its local data on this device. " +
                    "Other servers are unaffected. Continue?"
            }
            mutableRemoval.value = ServerRemovalState.ConfirmationRequired(
                RemovalConfirmation(
                    serverId = entry.id,
                    serverName = entry.displayLabel(),
                    affectedState = affected,
                    unsyncedWorkAtRisk = unsynced,
                    message = message,
                ),
            )
        }
    }

    override fun confirmRemove(id: String) {
        val entry = snapshot.servers.firstOrNull { it.id == id } ?: return
        if (mutableRemoval.value !is ServerRemovalState.ConfirmationRequired) return
        mutableRemoval.value = ServerRemovalState.Evaluating
        scope.launch {
            val homeScope = HomeScope(entry.serverId, entry.userId)
            progress.clear(homeScope)
            privateData.clear(homeScope)
            vault.remove(entry.serverId, entry.userId)
            val wasActive = snapshot.activeId == id
            val remaining = snapshot.servers.filterNot { it.id == id }
            val nextActiveId = if (wasActive) remaining.firstOrNull()?.id else snapshot.activeId
            if (wasActive) {
                remaining.firstOrNull()?.let { vault.selectActive(it.serverId, it.userId) }
            }
            snapshot = snapshot.copy(servers = remaining, activeId = nextActiveId)
            persistAndPublish()
            if (wasActive) onActiveRebind()
            mutableRemoval.value = ServerRemovalState.Removed(id)
        }
    }

    override fun cancelRemove() {
        mutableRemoval.value = ServerRemovalState.Idle
    }

    private fun updateEntry(id: String, transform: (ServerRegistryEntry) -> ServerRegistryEntry) {
        if (snapshot.servers.none { it.id == id }) return
        snapshot = snapshot.copy(servers = snapshot.servers.map { if (it.id == id) transform(it) else it })
        persistAndPublish()
    }

    private fun persistAndPublish() {
        store.save(snapshot)
        mutableState.value = snapshot.toDirectoryState()
    }

    private fun restoreOrMigrate(): ServerRegistrySnapshot {
        store.load()?.let { return it }
        // Migration: no explicit registry yet. Register each existing single-server session (the
        // pre-#28 install has exactly one) as an entry, marking the active vault scope active. The
        // per-scope session/caches/progress/prefs stay untouched under their existing scope keys.
        val sessions = vault.sessions()
        val active = vault.restore()
        val entries = sessions.map { session ->
            ServerRegistryEntry(
                id = UUID.randomUUID().toString(),
                serverId = session.serverId,
                userId = session.userId,
                address = session.address.value,
                serverName = session.serverName,
            )
        }
        val activeId = entries.firstOrNull {
            active != null && it.serverId == active.serverId && it.userId == active.userId
        }?.id ?: entries.firstOrNull()?.id
        val migrated = ServerRegistrySnapshot(servers = entries, activeId = activeId)
        store.save(migrated)
        return migrated
    }

    private fun ServerRegistrySnapshot.activeEntry(): ServerRegistryEntry? =
        servers.firstOrNull { it.id == activeId }

    private fun ServerRegistryEntry.displayLabel(): String =
        alias?.takeIf { it.isNotBlank() } ?: serverName

    private fun ServerRegistrySnapshot.toDirectoryState(): ServerDirectoryState = ServerDirectoryState(
        servers = servers.map { entry ->
            ConfiguredServer(
                id = entry.id,
                address = entry.address,
                serverName = entry.serverName,
                alias = entry.alias,
                icon = ServerIcon(
                    glyph = entry.iconGlyph,
                    colorArgb = entry.iconColor,
                    shape = runCatching { ServerIconShape.valueOf(entry.iconShape) }
                        .getOrDefault(ServerIconShape.Rounded),
                ),
                isActive = entry.id == activeId,
                connectionHealth = if (entry.id == activeId) ServerConnectionHealth.Unknown else null,
            )
        },
        activeServerId = activeId,
    )
}
