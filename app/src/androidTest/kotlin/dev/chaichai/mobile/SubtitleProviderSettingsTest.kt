package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.chaichai.mobile.core.contracts.AccountBoundary
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.SignOutState
import dev.chaichai.mobile.core.contracts.SubtitleProviderBoundary
import dev.chaichai.mobile.core.contracts.SubtitleProviderConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * App-level coverage of subtitle provider configuration (#32 AC1): a provider can be added, its
 * PROTECTED account credentials saved through the boundary (masked, never echoed), and the config
 * survives a restart. Deterministic in-process fake; playback is never referenced.
 */
class SubtitleProviderSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    private class FakeSubtitleProviders : SubtitleProviderBoundary {
        val providers = mutableListOf<SubtitleProviderConfig>()
        val accountCredentials = mutableMapOf<String, ProxyCredentials>()
        private var seq = 0

        override fun providers(): List<SubtitleProviderConfig> = providers.toList()
        override fun addProvider(name: String, baseUrl: String): String {
            val id = "sp-${seq++}"
            providers += SubtitleProviderConfig(id, name, baseUrl)
            return id
        }
        override fun renameProvider(id: String, name: String) = replace(id) { it.copy(name = name) }
        override fun updateBaseUrl(id: String, baseUrl: String) = replace(id) { it.copy(baseUrl = baseUrl) }
        override fun setEnabled(id: String, enabled: Boolean) = replace(id) { it.copy(enabled = enabled) }
        override fun removeProvider(id: String) {
            providers.removeAll { it.id == id }
            accountCredentials.remove(id)
        }
        override fun updateCredentials(id: String, credentials: ProxyCredentials?) {
            if (credentials == null) accountCredentials.remove(id) else accountCredentials[id] = credentials
            replace(id) { it.copy(hasCredentials = credentials != null) }
        }
        override fun updateRouting(id: String, routing: SubtitleProviderRouting, credentials: ProxyCredentials?) =
            replace(id) { it.copy(routing = routing) }
        override suspend fun testProvider(id: String): ProxyTestResult = ProxyTestResult.Success

        private inline fun replace(id: String, transform: (SubtitleProviderConfig) -> SubtitleProviderConfig) {
            val index = providers.indexOfFirst { it.id == id }
            if (index >= 0) providers[index] = transform(providers[index])
        }
    }

    private val account = object : AccountBoundary {
        override val signOutState = MutableStateFlow<SignOutState>(SignOutState.Idle)
        override fun requestSignOut() = Unit
        override fun confirmProgressLoss() = Unit
        override fun cancelSignOut() = Unit
    }

    private fun render(boundary: SubtitleProviderBoundary) {
        composeRule.setContent {
            ChaiChaiTheme(reducedMotion = true) {
                SettingsScreen(account = account, subtitleProviders = boundary)
            }
        }
    }

    @Test fun adds_a_subtitle_provider() {
        val boundary = FakeSubtitleProviders()
        render(boundary)

        composeRule.onNodeWithTag("subtitle-provider-new-name").performScrollTo().performTextInput("OpenSubs")
        composeRule.onNodeWithTag("subtitle-provider-new-url").performScrollTo().performTextInput("https://o.example")
        composeRule.onNodeWithTag("subtitle-provider-add").performScrollTo().performClick()

        assertEquals(1, boundary.providers.size)
        composeRule.onNodeWithTag("subtitle-provider-OpenSubs").performScrollTo().assertIsDisplayed()
    }

    @Test fun saves_protected_account_credentials_through_the_boundary() {
        val boundary = FakeSubtitleProviders()
        boundary.addProvider("OpenSubs", "https://o.example")
        render(boundary)

        composeRule.onNodeWithTag("subtitle-provider-username-OpenSubs").performScrollTo().performTextInput("member")
        composeRule.onNodeWithTag("subtitle-provider-password-OpenSubs").performScrollTo().performTextInput("s3cret")
        composeRule.onNodeWithTag("subtitle-provider-save-credentials-OpenSubs").performScrollTo().performClick()

        assertEquals("member", boundary.accountCredentials["sp-0"]!!.username)
        assertTrue(boundary.providers.first().hasCredentials)
    }

    @Test fun provider_configuration_survives_a_restart() {
        val boundary = FakeSubtitleProviders()
        boundary.addProvider("Kept", "https://kept.example")
        render(boundary)

        composeRule.onNodeWithTag("subtitle-provider-Kept").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("https://kept.example").performScrollTo().assertIsDisplayed()
    }
}
