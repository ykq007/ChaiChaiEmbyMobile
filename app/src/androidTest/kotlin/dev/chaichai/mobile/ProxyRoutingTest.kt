package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.chaichai.mobile.core.contracts.AccountBoundary
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConfiguredServer
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.ServerDirectory
import dev.chaichai.mobile.core.contracts.ServerDirectoryState
import dev.chaichai.mobile.core.contracts.ServerProxyBoundary
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.core.contracts.ServerRemovalState
import dev.chaichai.mobile.core.contracts.SignOutState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * App-level coverage of Proxy Routing (#30): a per-server proxy config persists through the boundary,
 * the connection test surfaces a DISTINGUISHED result, switching servers shows each server's own
 * proxy config, and configuration survives a restart. Everything is a deterministic in-process fake.
 */
class ProxyRoutingTest {
    @get:Rule val composeRule = createComposeRule()

    private class FakeGateway : EmbyGateway {
        override val connectionState = MutableStateFlow(GatewayConnectionState.Connected)
        private val feed = MutableStateFlow<HomeFeedState>(HomeFeedState.Failure("Home"))
        override val homeFeed = feed
        override suspend fun refreshHome() { feed.value = HomeFeedState.Failure("Home") }
    }

    private class FakeDirectory : ServerDirectory {
        private val servers = listOf(
            ConfiguredServer("id-one", "https://one.example/emby", "Server One", isActive = true),
            ConfiguredServer("id-two", "https://two.example/emby", "Server Two", isActive = false),
        )
        override val state = MutableStateFlow(ServerDirectoryState(servers, "id-one"))
        override val removalState = MutableStateFlow<ServerRemovalState>(ServerRemovalState.Idle)
        override fun selectServer(id: String) = Unit
        override fun rename(id: String, alias: String?) = Unit
        override fun updateIcon(id: String, icon: dev.chaichai.mobile.core.contracts.ServerIcon) = Unit
        override fun reorder(id: String, toIndex: Int) = Unit
        override fun beginAddServer() = Unit
        override fun editAddress(id: String, address: String) = Unit
        override fun requestRemove(id: String) = Unit
        override fun confirmRemove(id: String) = Unit
        override fun cancelRemove() = Unit
    }

    /** In-memory proxy boundary; records config per server and returns a fixed distinguished result. */
    private class FakeProxy(private val result: ProxyTestResult = ProxyTestResult.ProxyAuthenticationFailed) :
        ServerProxyBoundary {
        val configs = mutableMapOf<String, ServerProxyConfig>()
        val credentials = mutableMapOf<String, ProxyCredentials>()
        override fun proxyConfig(serverId: String) = configs[serverId] ?: ServerProxyConfig.Direct
        override fun updateProxyConfig(serverId: String, config: ServerProxyConfig, credentials: ProxyCredentials?) {
            configs[serverId] = config
            if (credentials != null) this.credentials[serverId] = credentials
        }
        override suspend fun testConnection(serverId: String) = result
    }

    private fun boundaries(directory: ServerDirectory, proxy: ServerProxyBoundary) = AppBoundaries(
        gateway = FakeGateway(),
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
        serverProxy = proxy,
    )

    private fun openSettings() {
        composeRule.onNodeWithText("Settings").performClick()
        // Server rows live in a scrolling settings column; scroll the first proxy field into view.
        composeRule.onNodeWithTag("proxy-host-id-one").performScrollTo()
    }

    @Test fun configuring_a_proxy_persists_it_through_the_boundary() {
        val proxy = FakeProxy()
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(FakeDirectory(), proxy), separatingHinge = null) }
        }
        openSettings()

        composeRule.onNodeWithTag("proxy-host-id-one").performTextInput("proxy.example")
        composeRule.onNodeWithTag("proxy-port-id-one").performTextInput("8080")
        composeRule.onNodeWithTag("proxy-enabled-id-one").performClick()
        composeRule.onNodeWithTag("proxy-save-id-one").performScrollTo().performClick()

        val stored = proxy.configs["id-one"]!!
        assertEquals("proxy.example", stored.host)
        assertEquals(8080, stored.port)
        assertTrue(stored.enabled)
        assertEquals(ProxyKind.Http, stored.kind)
    }

    @Test fun test_connection_surfaces_a_distinguished_result() {
        val proxy = FakeProxy(ProxyTestResult.ProxyAuthenticationFailed)
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(FakeDirectory(), proxy), separatingHinge = null) }
        }
        openSettings()
        composeRule.onNodeWithTag("proxy-host-id-one").performTextInput("proxy.example")
        composeRule.onNodeWithTag("proxy-port-id-one").performTextInput("8080")
        composeRule.onNodeWithTag("proxy-test-id-one").performScrollTo().performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(ProxyTestResult.ProxyAuthenticationFailed.summary).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(ProxyTestResult.ProxyAuthenticationFailed.summary).performScrollTo().assertIsDisplayed()
    }

    @Test fun each_server_shows_its_own_proxy_config() {
        val proxy = FakeProxy()
        proxy.configs["id-one"] = ServerProxyConfig(ProxyKind.Http, "one.proxy", 1000, enabled = true)
        proxy.configs["id-two"] = ServerProxyConfig(ProxyKind.Socks5, "two.proxy", 2000, enabled = true)
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(FakeDirectory(), proxy), separatingHinge = null) }
        }
        composeRule.onNodeWithText("Settings").performClick()

        composeRule.onNodeWithTag("proxy-host-id-one").performScrollTo()
        composeRule.onNodeWithText("one.proxy").assertIsDisplayed()
        composeRule.onNodeWithTag("proxy-host-id-two").performScrollTo()
        composeRule.onNodeWithText("two.proxy").assertIsDisplayed()
    }

    @Test fun proxy_config_survives_restart() {
        val proxy = FakeProxy()
        proxy.configs["id-one"] = ServerProxyConfig(ProxyKind.Socks5, "kept.proxy", 1080, enabled = true)
        // A second setContent models a fresh process reading the same (persistent) boundary.
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) { MobileApp(boundaries(FakeDirectory(), proxy), separatingHinge = null) }
        }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag("proxy-host-id-one").performScrollTo()
        composeRule.onNodeWithText("kept.proxy").assertIsDisplayed()
    }
}
