package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chaichai.mobile.core.contracts.AccountBoundary
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConfiguredServer
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.RemovalConfirmation
import dev.chaichai.mobile.core.contracts.ServerDirectory
import dev.chaichai.mobile.core.contracts.ServerDirectoryState
import dev.chaichai.mobile.core.contracts.ServerIcon
import dev.chaichai.mobile.core.contracts.ServerRemovalState
import dev.chaichai.mobile.core.contracts.SignOutState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * App-level coverage of Multiple Servers: switching the active server rebinds Home to the newly
 * active scope with no restart, and removal surfaces a confirmation describing affected local state.
 * Servers are deterministic fakes driven entirely in-process.
 */
class MultipleServersTest {
    @get:Rule val composeRule = createComposeRule()

    /** A fake gateway whose Home feed is re-pointed to the active server on every rebind. */
    private class SwitchableGateway : EmbyGateway {
        override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
        private val feed = MutableStateFlow<HomeFeedState>(HomeFeedState.Loading)
        override val homeFeed = feed
        var activeLabel: String = "Server One"
        var refreshes = 0

        override suspend fun refreshHome() {
            refreshes++
            // Distinguishable, always-composed content proving which scope Home is bound to.
            feed.value = HomeFeedState.Failure("Home for $activeLabel")
        }

        fun rebindTo(label: String) {
            activeLabel = label
            feed.value = HomeFeedState.Loading // re-triggers HomeScreen's refresh, as in production
        }
    }

    private class FakeDirectory(private val gateway: SwitchableGateway) : ServerDirectory {
        private val servers = mutableListOf(
            ConfiguredServer("id-one", "https://one.example/emby", "Server One", isActive = true, icon = ServerIcon(glyph = "🎬")),
            ConfiguredServer("id-two", "https://two.example/emby", "Server Two", isActive = false, icon = ServerIcon(glyph = "📺")),
        )
        override val state = MutableStateFlow(ServerDirectoryState(servers.toList(), "id-one"))
        override val removalState = MutableStateFlow<ServerRemovalState>(ServerRemovalState.Idle)

        private fun publish(activeId: String?) {
            state.value = ServerDirectoryState(
                servers.map { it.copy(isActive = it.id == activeId) },
                activeId,
            )
        }

        override fun selectServer(id: String) {
            val entry = servers.firstOrNull { it.id == id } ?: return
            publish(id)
            gateway.rebindTo(entry.serverName)
        }

        override fun rename(id: String, alias: String?) {
            val i = servers.indexOfFirst { it.id == id }
            if (i >= 0) servers[i] = servers[i].copy(alias = alias)
            publish(state.value.activeServerId)
        }

        override fun updateIcon(id: String, icon: ServerIcon) = Unit
        override fun reorder(id: String, toIndex: Int) = Unit
        override fun beginAddServer() = Unit
        override fun editAddress(id: String, address: String) = Unit

        override fun requestRemove(id: String) {
            val entry = servers.firstOrNull { it.id == id } ?: return
            removalState.value = ServerRemovalState.ConfirmationRequired(
                RemovalConfirmation(
                    serverId = id,
                    serverName = entry.serverName,
                    affectedState = listOf("Saved credentials for ${entry.serverName}", "Home, library and search caches"),
                    unsyncedWorkAtRisk = false,
                    message = "Removing ${entry.serverName} deletes all of its local data on this device.",
                ),
            )
        }

        override fun confirmRemove(id: String) {
            servers.removeAll { it.id == id }
            publish(state.value.activeServerId.takeIf { active -> servers.any { it.id == active } } ?: servers.firstOrNull()?.id)
            removalState.value = ServerRemovalState.Removed(id)
        }

        override fun cancelRemove() { removalState.value = ServerRemovalState.Idle }
    }

    private fun boundaries(gateway: SwitchableGateway, directory: ServerDirectory) = AppBoundaries(
        gateway = gateway,
        playback = object : NoOpPlaybackCoordinator() {},
        clock = AppClock { Instant.EPOCH },
        connectivity = object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true) },
        account = object : AccountBoundary {
            override val signOutState = MutableStateFlow<SignOutState>(SignOutState.Idle)
            override fun requestSignOut() = Unit
            override fun confirmProgressLoss() = Unit
            override fun cancelSignOut() = Unit
        },
        serverDirectory = directory,
    )

    @Test
    fun switching_active_server_rebinds_home_and_lists_both_servers() {
        val gateway = SwitchableGateway()
        val directory = FakeDirectory(gateway)
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(gateway, directory), separatingHinge = null) }
        }

        // Home starts bound to the first server.
        waitForText("Home for Server One")

        // Manage servers from the app shell (Settings) and switch to the second server.
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNode(hasText("Servers") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Server Two").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Use").fetchSemanticsNodes() // both rows expose an action
        composeRule.onNodeWithText("Server Two").performScrollTo()
        composeRule.onAllNodesWithText("Use")[1].performClick()

        // Home rebinds to the newly active server with no restart.
        composeRule.onNodeWithText("Home").performClick()
        waitForText("Home for Server Two")
    }

    @Test
    fun removing_a_server_requires_a_confirmation_describing_local_state() {
        val gateway = SwitchableGateway()
        val directory = FakeDirectory(gateway)
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(gateway, directory), separatingHinge = null) }
        }
        waitForText("Home for Server One")

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Server Two").performScrollTo()
        composeRule.onAllNodesWithText("Remove")[1].performScrollTo().performClick()

        // Confirmation describes affected local state before anything is deleted.
        waitForText("Remove Server Two?")
        composeRule.onNodeWithText("Home, library and search caches").assertIsDisplayed()
        composeRule.onNodeWithText("Remove server").performClick()

        // The removed server is gone from the list.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Server Two").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
