package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.chaichai.mobile.core.contracts.AccountBoundary
import dev.chaichai.mobile.core.contracts.DanmakuEndpointBoundary
import dev.chaichai.mobile.core.contracts.DanmakuEndpointConfig
import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.SignOutState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * App-level coverage of danmaku endpoint management + per-endpoint Proxy Routing (#31): an endpoint
 * can be added, named and removed, a per-endpoint proxy override persists through the boundary, and the
 * configuration survives a restart. Everything is a deterministic in-process fake. Playback is never
 * referenced by the endpoint boundary, so route changes cannot interrupt it.
 */
class DanmakuEndpointManagementTest {
    @get:Rule val composeRule = createComposeRule()

    /** In-memory endpoint boundary modelling persistence (a shared list survives "restart"). */
    private class FakeDanmakuEndpoints(
        private val result: ProxyTestResult = ProxyTestResult.Success,
    ) : DanmakuEndpointBoundary {
        val endpoints = mutableListOf<DanmakuEndpointConfig>()
        val credentials = mutableMapOf<String, ProxyCredentials>()
        private var seq = 0

        override fun endpoints(): List<DanmakuEndpointConfig> = endpoints.toList()

        override fun addEndpoint(name: String, baseUrl: String): String {
            val id = "ep-${seq++}"
            endpoints += DanmakuEndpointConfig(id, name, baseUrl)
            return id
        }

        override fun renameEndpoint(id: String, name: String) = replace(id) { it.copy(name = name) }
        override fun updateBaseUrl(id: String, baseUrl: String) = replace(id) { it.copy(baseUrl = baseUrl) }
        override fun removeEndpoint(id: String) {
            endpoints.removeAll { it.id == id }
            credentials.remove(id)
        }

        override fun updateRouting(id: String, routing: DanmakuEndpointRouting, credentials: ProxyCredentials?) {
            if (routing is DanmakuEndpointRouting.Proxy && routing.config.hasCredentials && credentials != null) {
                this.credentials[id] = credentials
            } else if (routing is DanmakuEndpointRouting.Direct) {
                this.credentials.remove(id)
            }
            replace(id) { it.copy(routing = routing) }
        }

        override suspend fun testEndpoint(id: String): ProxyTestResult = result

        private inline fun replace(id: String, transform: (DanmakuEndpointConfig) -> DanmakuEndpointConfig) {
            val index = endpoints.indexOfFirst { it.id == id }
            if (index >= 0) endpoints[index] = transform(endpoints[index])
        }
    }

    private val account = object : AccountBoundary {
        override val signOutState = MutableStateFlow<SignOutState>(SignOutState.Idle)
        override fun requestSignOut() = Unit
        override fun confirmProgressLoss() = Unit
        override fun cancelSignOut() = Unit
    }

    private fun render(boundary: DanmakuEndpointBoundary) {
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) {
                SettingsScreen(account = account, danmakuEndpoints = boundary)
            }
        }
    }

    @Test fun adds_names_and_removes_a_danmaku_endpoint() {
        val boundary = FakeDanmakuEndpoints()
        render(boundary)

        composeRule.onNodeWithTag("danmaku-new-name").performScrollTo().performTextInput("Community")
        composeRule.onNodeWithTag("danmaku-new-url").performScrollTo().performTextInput("https://c.example")
        composeRule.onNodeWithTag("danmaku-add-endpoint").performScrollTo().performClick()

        assertEquals(1, boundary.endpoints.size)
        assertEquals("Community", boundary.endpoints.first().name)
        composeRule.onNodeWithTag("danmaku-endpoint-Community").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("danmaku-remove-Community").performScrollTo().performClick()
        assertTrue(boundary.endpoints.isEmpty())
    }

    @Test fun sets_a_per_endpoint_proxy_override_that_persists_through_the_boundary() {
        val boundary = FakeDanmakuEndpoints()
        boundary.addEndpoint("Community", "https://c.example")
        render(boundary)

        composeRule.onNodeWithTag("danmaku-proxy-enabled-Community").performScrollTo().performClick()
        composeRule.onNodeWithTag("danmaku-proxy-host-Community").performScrollTo().performTextInput("proxy.example")
        composeRule.onNodeWithTag("danmaku-proxy-port-Community").performScrollTo().performTextInput("8080")
        composeRule.onNodeWithTag("danmaku-proxy-active-Community").performScrollTo().performClick()
        composeRule.onNodeWithTag("danmaku-proxy-save-Community").performScrollTo().performClick()

        val routing = boundary.endpoints.first().routing
        assertTrue(routing is DanmakuEndpointRouting.Proxy)
        val config = (routing as DanmakuEndpointRouting.Proxy).config
        assertEquals("proxy.example", config.host)
        assertEquals(8080, config.port)
        assertTrue(config.enabled)
    }

    @Test fun endpoint_configuration_survives_a_restart() {
        val boundary = FakeDanmakuEndpoints()
        boundary.addEndpoint("Kept", "https://kept.example")
        // A fresh setContent models a new process reading the same (persistent) boundary.
        render(boundary)

        composeRule.onNodeWithTag("danmaku-endpoint-Kept").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("https://kept.example").performScrollTo().assertIsDisplayed()
    }

    @Test fun test_endpoint_surfaces_a_distinguished_result() {
        val boundary = FakeDanmakuEndpoints(ProxyTestResult.ProxyAuthenticationFailed)
        boundary.addEndpoint("Community", "https://c.example")
        render(boundary)

        composeRule.onNodeWithTag("danmaku-proxy-enabled-Community").performScrollTo().performClick()
        composeRule.onNodeWithTag("danmaku-proxy-test-Community").performScrollTo().performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("danmaku-proxy-result-Community").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(ProxyTestResult.ProxyAuthenticationFailed.summary).performScrollTo().assertIsDisplayed()
    }
}
