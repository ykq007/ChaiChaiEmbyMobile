package dev.chaichai.mobile.core.contracts

import kotlinx.coroutines.flow.StateFlow

/**
 * How a configured server distinguishes itself visually beyond its alias text.
 * Kept Android-free: [colorArgb] is a packed ARGB int the UI maps to a real colour, and [glyph]
 * is a short emoji or set of initials. No file upload — a preset colour/shape/glyph only.
 */
data class ServerIcon(
    val glyph: String = "",
    val colorArgb: Int = DefaultColorArgb,
    val shape: ServerIconShape = ServerIconShape.Rounded,
) {
    companion object {
        const val DefaultColorArgb: Int = 0xFF3F51B5.toInt()
    }
}

enum class ServerIconShape { Circle, Rounded, Shield }

/** Health of the last reachability observation for a configured server, if known. */
enum class ServerConnectionHealth { Reachable, Unreachable, Unknown }

/**
 * One entry in the [ServerDirectory]. Carries only non-secret metadata; credentials, caches,
 * progress and Certificate Bypass stay in server-scoped storage keyed by [MediaIdentity]/[HomeScope]
 * and never travel inside the directory.
 */
data class ConfiguredServer(
    val id: String,
    val address: String,
    val serverName: String,
    val alias: String? = null,
    val icon: ServerIcon = ServerIcon(),
    val isActive: Boolean = false,
    val connectionHealth: ServerConnectionHealth? = null,
) {
    /** The user-facing label: the alias when present, otherwise the server-reported name. */
    val displayName: String get() = alias?.takeIf { it.isNotBlank() } ?: serverName
}

data class ServerDirectoryState(
    val servers: List<ConfiguredServer> = emptyList(),
    val activeServerId: String? = null,
) {
    val activeServer: ConfiguredServer? get() = servers.firstOrNull { it.id == activeServerId }
}

/**
 * Describes what removing a configured server destroys locally, and whether unsynchronised work
 * would be lost. Surfaced before [ServerDirectory.confirmRemove] so removal always requires an
 * explicit, informed confirmation.
 */
data class RemovalConfirmation(
    val serverId: String,
    val serverName: String,
    val affectedState: List<String>,
    val unsyncedWorkAtRisk: Boolean,
    val message: String,
)

sealed interface ServerRemovalState {
    data object Idle : ServerRemovalState
    data object Evaluating : ServerRemovalState
    data class ConfirmationRequired(val confirmation: RemovalConfirmation) : ServerRemovalState
    data class Removed(val serverId: String) : ServerRemovalState
}

/**
 * Configure, distinguish and switch among more than one Emby server. Deliberately narrow and
 * Android-free. Switching re-points the active gateway/account/playback bindings to the newly
 * selected server's scope with no process restart; per-server credentials, Certificate Bypass,
 * caches, media identities, progress and preferences never cross a server boundary.
 */
interface ServerDirectory {
    val state: StateFlow<ServerDirectoryState>
    val removalState: StateFlow<ServerRemovalState>

    /** Switch the active server. Restores its authenticated session and rebinds Home/library/search. */
    fun selectServer(id: String)

    /**
     * Switch the active server addressed by its authenticated [HomeScope] rather than the
     * directory's own id. Used by Aggregated Search (#29) to route a selected result to the
     * correct server context — a [SearchResult] only carries [HomeScope]/[MediaIdentity]
     * provenance, never the directory id — before the shared gateway loads that result's details,
     * so identical item ids on different servers never collide. Returns false when no configured
     * server matches [scope] (e.g. it was removed since the search ran); callers still get a
     * normal per-server "not available" failure rather than a crash. No-op default (returns
     * false) preserves existing single-scope [ServerDirectory] fakes that predate #29.
     */
    fun activateScope(scope: HomeScope): Boolean = false

    fun rename(id: String, alias: String?)
    fun updateIcon(id: String, icon: ServerIcon)

    /** Move the server with [id] to [toIndex] in the ordered list. */
    fun reorder(id: String, toIndex: Int)

    /** Begin adding a server through the existing single-server add + authenticate flow. */
    fun beginAddServer()

    /** Update the displayed Server Address of an existing entry (metadata only). */
    fun editAddress(id: String, address: String)

    /**
     * Ask to remove [id]. Detection of unsynced work is asynchronous; the resulting
     * [RemovalConfirmation] is published on [removalState] as [ServerRemovalState.ConfirmationRequired].
     */
    fun requestRemove(id: String)
    fun confirmRemove(id: String)
    fun cancelRemove()
}
