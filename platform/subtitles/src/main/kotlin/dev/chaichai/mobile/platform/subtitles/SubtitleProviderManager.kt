package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.SubtitleProviderBoundary
import dev.chaichai.mobile.core.contracts.SubtitleProviderConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import dev.chaichai.mobile.platform.proxy.ProxyCredentialVault
import java.util.UUID

/**
 * The real [SubtitleProviderBoundary] over the non-secret [SubtitleProviderConfigStore] and the
 * Keystore-protected [ProxyCredentialVault] (subtitle-namespaced). It owns add/name/enable/remove,
 * provider ACCOUNT credentials (keyed "auth:{id}") and per-provider proxy routing + PROXY credentials
 * (keyed "proxy:{id}"), keeping the surfaced `hasCredentials` flags reconciled with the vault so they
 * can never disagree with (or leak) the presence of a secret.
 *
 * Credentials only ever travel IN; nothing is ever surfaced back or echoed into any result text. The
 * whole provider path never touches `:platform:server`, so no provider control can reach a server's
 * Certificate Bypass.
 */
class SubtitleProviderManager(
    private val configStore: SubtitleProviderConfigStore,
    private val vault: ProxyCredentialVault,
    private val tester: SubtitleProviderTester,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : SubtitleProviderBoundary {

    override fun providers(): List<SubtitleProviderConfig> = configStore.load().providers.map {
        it.toConfig(
            hasCredentials = vault.load(SubtitleProviderHttpClients.accountCredentialKey(it.id)) != null,
            hasProxyCredentials = vault.load(SubtitleProviderHttpClients.proxyCredentialKey(it.id)) != null,
        )
    }

    override fun addProvider(name: String, baseUrl: String): String {
        val id = idFactory()
        val provider = SubtitleProvider(id = id, name = name.trim(), baseUrl = baseUrl.trim())
        configStore.setProviders(configStore.load().providers + provider)
        return id
    }

    override fun renameProvider(id: String, name: String) = mutate(id) { it.copy(name = name.trim()) }

    override fun updateBaseUrl(id: String, baseUrl: String) = mutate(id) { it.copy(baseUrl = baseUrl.trim()) }

    override fun setEnabled(id: String, enabled: Boolean) = mutate(id) { it.copy(enabled = enabled) }

    override fun removeProvider(id: String) {
        val remaining = configStore.load().providers.filterNot { it.id == id }
        vault.remove(SubtitleProviderHttpClients.accountCredentialKey(id))
        vault.remove(SubtitleProviderHttpClients.proxyCredentialKey(id))
        configStore.setProviders(remaining)
    }

    override fun updateCredentials(id: String, credentials: ProxyCredentials?) {
        val key = SubtitleProviderHttpClients.accountCredentialKey(id)
        if (credentials == null || (credentials.username.isEmpty() && credentials.password.isEmpty())) {
            vault.remove(key)
        } else {
            vault.save(key, credentials)
        }
    }

    override fun updateRouting(id: String, routing: SubtitleProviderRouting, credentials: ProxyCredentials?) {
        val key = SubtitleProviderHttpClients.proxyCredentialKey(id)
        val reconciled = when (routing) {
            is SubtitleProviderRouting.Direct -> {
                vault.remove(key)
                SubtitleProviderRouting.Direct
            }
            is SubtitleProviderRouting.Proxy -> {
                val config = routing.config
                if (!config.hasCredentials) {
                    vault.remove(key)
                } else if (credentials != null) {
                    vault.save(key, credentials)
                }
                routing
            }
        }
        mutate(id) { it.copy(routing = reconciled) }
    }

    override suspend fun testProvider(id: String): ProxyTestResult {
        val provider = configStore.load().providers.firstOrNull { it.id == id }
            ?: return ProxyTestResult.InvalidConfiguration
        return tester.test(provider)
    }

    private inline fun mutate(id: String, transform: (SubtitleProvider) -> SubtitleProvider) {
        val updated = configStore.load().providers.map { if (it.id == id) transform(it) else it }
        configStore.setProviders(updated)
    }
}
