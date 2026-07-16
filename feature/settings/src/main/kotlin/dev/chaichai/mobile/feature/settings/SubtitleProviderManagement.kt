package dev.chaichai.mobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderBoundary
import dev.chaichai.mobile.core.contracts.SubtitleProviderConfig
import dev.chaichai.mobile.core.contracts.SubtitleProviderRouting
import kotlinx.coroutines.launch

/**
 * Subtitle provider management (Subtitle Expansion, #32). Lists the user's providers (add / name /
 * enable / remove), each with PROTECTED account credentials (masked, Keystore-stored) and an optional
 * per-provider Proxy Routing override mirroring the per-server/danmaku proxy UI. Talks only to
 * [SubtitleProviderBoundary]; credentials are never echoed back into any result text, and no provider
 * control has any access to a server's Certificate Bypass. Font-file upload and cross-device sync are
 * intentionally absent.
 */
@Composable
fun SubtitleProvidersSection(
    boundary: SubtitleProviderBoundary,
    modifier: Modifier = Modifier,
) {
    var revision by remember { mutableIntStateOf(0) }
    val providers = remember(revision) { boundary.providers() }
    var newName by rememberSaveable { mutableStateOf("") }
    var newUrl by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Subtitle providers",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text("Add online subtitle providers to search during playback. Each provider connects directly unless you route it through a proxy.")

        if (providers.isEmpty()) {
            Text("No subtitle providers configured yet.")
        }
        providers.forEach { provider ->
            SubtitleProviderCard(
                provider = provider,
                onRename = { boundary.renameProvider(provider.id, it); revision++ },
                onUrl = { boundary.updateBaseUrl(provider.id, it); revision++ },
                onEnabled = { boundary.setEnabled(provider.id, it); revision++ },
                onRemove = { boundary.removeProvider(provider.id); revision++ },
                onCredentials = { boundary.updateCredentials(provider.id, it); revision++ },
                onRouting = { routing, creds -> boundary.updateRouting(provider.id, routing, creds); revision++ },
                test = { boundary.testProvider(provider.id).summary },
            )
        }

        HorizontalDivider()
        Text("Add a provider", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            singleLine = true,
            label = { Text("Provider name") },
            modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-new-name"),
        )
        OutlinedTextField(
            value = newUrl,
            onValueChange = { newUrl = it },
            singleLine = true,
            label = { Text("Provider base URL") },
            modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-new-url"),
        )
        Button(
            onClick = {
                if (newName.isNotBlank() && newUrl.isNotBlank()) {
                    boundary.addProvider(newName, newUrl)
                    newName = ""
                    newUrl = ""
                    revision++
                }
            },
            modifier = Modifier.testTag("subtitle-provider-add"),
        ) { Text("Add provider") }
    }
}

@Composable
private fun SubtitleProviderCard(
    provider: SubtitleProviderConfig,
    onRename: (String) -> Unit,
    onUrl: (String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onCredentials: (ProxyCredentials?) -> Unit,
    onRouting: (SubtitleProviderRouting, ProxyCredentials?) -> Unit,
    test: suspend () -> String,
) {
    val tag = provider.name
    Card(Modifier.fillMaxWidth().testTag("subtitle-provider-$tag")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                provider.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                provider.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("subtitle-provider-summary-url-$tag"),
            )

            var name by rememberSaveable(provider.id) { mutableStateOf(provider.name) }
            var url by rememberSaveable(provider.id) { mutableStateOf(provider.baseUrl) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-name-$tag"),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-url-$tag"),
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable this provider", Modifier.weight(1f))
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = onEnabled,
                    modifier = Modifier.testTag("subtitle-provider-enabled-$tag")
                        .semantics { contentDescription = if (provider.enabled) "Disable this subtitle provider" else "Enable this subtitle provider" },
                )
            }

            SubtitleProviderCredentials(provider = provider, onCredentials = onCredentials)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRename(name); onUrl(url) }, modifier = Modifier.testTag("subtitle-provider-save-$tag")) {
                    Text("Save")
                }
                TextButton(onClick = onRemove, modifier = Modifier.testTag("subtitle-provider-remove-$tag")) { Text("Remove") }
            }

            HorizontalDivider()
            SubtitleProviderRoutingControls(provider = provider, onRouting = onRouting, test = test)
        }
    }
}

/** Provider ACCOUNT credentials: masked, never echoed. Saving with both fields empty clears them. */
@Composable
private fun SubtitleProviderCredentials(
    provider: SubtitleProviderConfig,
    onCredentials: (ProxyCredentials?) -> Unit,
) {
    val tag = provider.name
    var username by rememberSaveable(provider.id) { mutableStateOf("") }
    var password by rememberSaveable(provider.id) { mutableStateOf("") }
    val saved = provider.hasCredentials
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Account credentials (optional)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            singleLine = true,
            label = { Text(if (saved && username.isEmpty()) "Username (saved)" else "Username") },
            modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-username-$tag"),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            label = { Text(if (saved && password.isEmpty()) "Password / API key (saved)" else "Password / API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-password-$tag"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onCredentials(ProxyCredentials(username, password)) },
                modifier = Modifier.testTag("subtitle-provider-save-credentials-$tag"),
            ) { Text("Save credentials") }
            TextButton(
                onClick = { username = ""; password = ""; onCredentials(null) },
                modifier = Modifier.testTag("subtitle-provider-clear-credentials-$tag"),
            ) { Text("Clear") }
        }
    }
}

/** Per-provider routing, mirroring the danmaku/per-server proxy UI. Credentials never echoed. */
@Composable
private fun SubtitleProviderRoutingControls(
    provider: SubtitleProviderConfig,
    onRouting: (SubtitleProviderRouting, ProxyCredentials?) -> Unit,
    test: suspend () -> String,
) {
    val tag = provider.name
    val initialProxy = provider.routing as? SubtitleProviderRouting.Proxy
    val initialConfig = initialProxy?.config

    var useProxy by rememberSaveable(provider.id) { mutableStateOf(initialProxy != null) }
    var socks by rememberSaveable(provider.id) { mutableStateOf(initialConfig?.kind == ProxyKind.Socks5) }
    var host by rememberSaveable(provider.id) { mutableStateOf(initialConfig?.host ?: "") }
    var port by rememberSaveable(provider.id) {
        mutableStateOf(initialConfig?.port?.takeIf { it > 0 }?.toString() ?: "")
    }
    var username by rememberSaveable(provider.id) { mutableStateOf("") }
    var password by rememberSaveable(provider.id) { mutableStateOf("") }
    var hadCredentials by rememberSaveable(provider.id) { mutableStateOf(initialConfig?.hasCredentials == true) }
    var lanBypass by rememberSaveable(provider.id) { mutableStateOf(initialConfig?.lanBypass ?: false) }
    var enabled by rememberSaveable(provider.id) { mutableStateOf(initialConfig?.enabled ?: false) }
    var resultText by rememberSaveable(provider.id) { mutableStateOf<String?>(null) }
    var testing by rememberSaveable(provider.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun currentRouting(): SubtitleProviderRouting {
        if (!useProxy) return SubtitleProviderRouting.Direct
        return SubtitleProviderRouting.Proxy(
            ServerProxyConfig(
                kind = if (socks) ProxyKind.Socks5 else ProxyKind.Http,
                host = host.trim(),
                port = port.trim().toIntOrNull() ?: 0,
                enabled = enabled,
                lanBypass = lanBypass,
                hasCredentials = password.isNotEmpty() || username.isNotEmpty() ||
                    (hadCredentials && username.isEmpty() && password.isEmpty()),
            ),
        )
    }

    fun persist() {
        val creds = if (username.isNotEmpty() || password.isNotEmpty()) ProxyCredentials(username, password) else null
        val routing = currentRouting()
        onRouting(routing, creds)
        hadCredentials = (routing as? SubtitleProviderRouting.Proxy)?.config?.hasCredentials == true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Route this provider through a proxy", Modifier.weight(1f))
            Switch(
                checked = useProxy,
                onCheckedChange = { useProxy = it; persist() },
                modifier = Modifier.testTag("subtitle-provider-proxy-enabled-$tag")
                    .semantics { contentDescription = "Route this subtitle provider through a proxy" },
            )
        }
        if (useProxy) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { socks = false }, modifier = Modifier.testTag("subtitle-provider-proxy-http-$tag")) {
                    Text(if (!socks) "● HTTP" else "HTTP")
                }
                TextButton(onClick = { socks = true }, modifier = Modifier.testTag("subtitle-provider-proxy-socks-$tag")) {
                    Text(if (socks) "● SOCKS5" else "SOCKS5")
                }
            }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                singleLine = true,
                label = { Text("Proxy host") },
                modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-proxy-host-$tag"),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text("Proxy port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-proxy-port-$tag"),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text(if (hadCredentials && username.isEmpty()) "Proxy username (saved)" else "Proxy username (optional)") },
                modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-proxy-username-$tag"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text(if (hadCredentials && password.isEmpty()) "Proxy password (saved)" else "Proxy password (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("subtitle-provider-proxy-password-$tag"),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable this proxy", Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; persist() },
                    modifier = Modifier.testTag("subtitle-provider-proxy-active-$tag")
                        .semantics { contentDescription = "Enable this subtitle provider proxy" },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bypass proxy for local (LAN) addresses", Modifier.weight(1f))
                Switch(
                    checked = lanBypass,
                    onCheckedChange = { lanBypass = it; persist() },
                    modifier = Modifier.testTag("subtitle-provider-proxy-lanbypass-$tag")
                        .semantics { contentDescription = "Bypass proxy for local addresses" },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { persist() }, modifier = Modifier.testTag("subtitle-provider-proxy-save-$tag")) {
                    Text("Save proxy")
                }
                Button(
                    onClick = {
                        persist()
                        testing = true
                        resultText = null
                        scope.launch {
                            resultText = test()
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.testTag("subtitle-provider-proxy-test-$tag"),
                ) { Text(if (testing) "Testing…" else "Test provider") }
            }
        }
        resultText?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("subtitle-provider-proxy-result-$tag")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
