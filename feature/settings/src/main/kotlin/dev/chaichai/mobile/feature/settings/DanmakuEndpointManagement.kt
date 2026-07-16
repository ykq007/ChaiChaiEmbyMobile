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
import dev.chaichai.mobile.core.contracts.DanmakuEndpointBoundary
import dev.chaichai.mobile.core.contracts.DanmakuEndpointConfig
import dev.chaichai.mobile.core.contracts.DanmakuEndpointRouting
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import kotlinx.coroutines.launch

/**
 * Danmaku endpoint management + per-endpoint Proxy Routing (issue #31). Lists the user's endpoints
 * (add / name / remove) and, for each, a routing choice: inherit Direct routing, or an explicit proxy
 * override mirroring the per-server proxy UI. Talks only to [DanmakuEndpointBoundary]; credentials are
 * never echoed back into any result text, and no endpoint control has any access to a server's
 * Certificate Bypass.
 */
@Composable
fun DanmakuEndpointsSection(
    boundary: DanmakuEndpointBoundary,
    modifier: Modifier = Modifier,
) {
    var revision by remember { mutableIntStateOf(0) }
    val endpoints = remember(revision) { boundary.endpoints() }
    var newName by rememberSaveable { mutableStateOf("") }
    var newUrl by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Danmaku endpoints",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text("Add compatible comment endpoints. Each endpoint connects directly unless you route it through a proxy.")

        if (endpoints.isEmpty()) {
            Text("No danmaku endpoints configured yet.")
        }
        endpoints.forEach { endpoint ->
            DanmakuEndpointCard(
                endpoint = endpoint,
                onRename = { boundary.renameEndpoint(endpoint.id, it); revision++ },
                onUrl = { boundary.updateBaseUrl(endpoint.id, it); revision++ },
                onRemove = { boundary.removeEndpoint(endpoint.id); revision++ },
                onRouting = { routing, creds -> boundary.updateRouting(endpoint.id, routing, creds); revision++ },
                test = { boundary.testEndpoint(endpoint.id).summary },
            )
        }

        HorizontalDivider()
        Text("Add an endpoint", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            singleLine = true,
            label = { Text("Endpoint name") },
            modifier = Modifier.fillMaxWidth().testTag("danmaku-new-name"),
        )
        OutlinedTextField(
            value = newUrl,
            onValueChange = { newUrl = it },
            singleLine = true,
            label = { Text("Endpoint base URL") },
            modifier = Modifier.fillMaxWidth().testTag("danmaku-new-url"),
        )
        Button(
            onClick = {
                if (newName.isNotBlank() && newUrl.isNotBlank()) {
                    boundary.addEndpoint(newName, newUrl)
                    newName = ""
                    newUrl = ""
                    revision++
                }
            },
            modifier = Modifier.testTag("danmaku-add-endpoint"),
        ) { Text("Add endpoint") }
    }
}

@Composable
private fun DanmakuEndpointCard(
    endpoint: DanmakuEndpointConfig,
    onRename: (String) -> Unit,
    onUrl: (String) -> Unit,
    onRemove: () -> Unit,
    onRouting: (DanmakuEndpointRouting, ProxyCredentials?) -> Unit,
    test: suspend () -> String,
) {
    val tag = endpoint.name
    Card(Modifier.fillMaxWidth().testTag("danmaku-endpoint-$tag")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                endpoint.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                endpoint.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("danmaku-endpoint-url-$tag"),
            )

            var name by rememberSaveable(endpoint.id) { mutableStateOf(endpoint.name) }
            var url by rememberSaveable(endpoint.id) { mutableStateOf(endpoint.baseUrl) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().testTag("danmaku-name-$tag"),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth().testTag("danmaku-url-$tag"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRename(name); onUrl(url) }, modifier = Modifier.testTag("danmaku-save-$tag")) {
                    Text("Save")
                }
                TextButton(onClick = onRemove, modifier = Modifier.testTag("danmaku-remove-$tag")) { Text("Remove") }
            }

            HorizontalDivider()
            DanmakuRoutingControls(endpoint = endpoint, onRouting = onRouting, test = test)
        }
    }
}

/**
 * Per-endpoint routing: a Direct/Proxy toggle plus, when Proxy is chosen, the kind/host/port/
 * credentials/enabled/LAN-bypass fields, a Save action and a Test action that surfaces the
 * distinguished result in a polite live region. Mirrors the per-server proxy UI. Credentials are never
 * echoed into the result text.
 */
@Composable
private fun DanmakuRoutingControls(
    endpoint: DanmakuEndpointConfig,
    onRouting: (DanmakuEndpointRouting, ProxyCredentials?) -> Unit,
    test: suspend () -> String,
) {
    val tag = endpoint.name
    val initialProxy = endpoint.routing as? DanmakuEndpointRouting.Proxy
    val initialConfig = initialProxy?.config

    var useProxy by rememberSaveable(endpoint.id) { mutableStateOf(initialProxy != null) }
    var socks by rememberSaveable(endpoint.id) { mutableStateOf(initialConfig?.kind == ProxyKind.Socks5) }
    var host by rememberSaveable(endpoint.id) { mutableStateOf(initialConfig?.host ?: "") }
    var port by rememberSaveable(endpoint.id) {
        mutableStateOf(initialConfig?.port?.takeIf { it > 0 }?.toString() ?: "")
    }
    var username by rememberSaveable(endpoint.id) { mutableStateOf("") }
    var password by rememberSaveable(endpoint.id) { mutableStateOf("") }
    var hadCredentials by rememberSaveable(endpoint.id) { mutableStateOf(initialConfig?.hasCredentials == true) }
    var lanBypass by rememberSaveable(endpoint.id) { mutableStateOf(initialConfig?.lanBypass ?: false) }
    var enabled by rememberSaveable(endpoint.id) { mutableStateOf(initialConfig?.enabled ?: false) }
    var resultText by rememberSaveable(endpoint.id) { mutableStateOf<String?>(null) }
    var testing by rememberSaveable(endpoint.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun currentRouting(): DanmakuEndpointRouting {
        if (!useProxy) return DanmakuEndpointRouting.Direct
        return DanmakuEndpointRouting.Proxy(
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
        hadCredentials = (routing as? DanmakuEndpointRouting.Proxy)?.config?.hasCredentials == true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Route this endpoint through a proxy", Modifier.weight(1f))
            Switch(
                checked = useProxy,
                onCheckedChange = { useProxy = it; persist() },
                modifier = Modifier
                    .testTag("danmaku-proxy-enabled-$tag")
                    .semantics { contentDescription = "Route this danmaku endpoint through a proxy" },
            )
        }
        if (useProxy) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { socks = false }, modifier = Modifier.testTag("danmaku-proxy-http-$tag")) {
                    Text(if (!socks) "● HTTP" else "HTTP")
                }
                TextButton(onClick = { socks = true }, modifier = Modifier.testTag("danmaku-proxy-socks-$tag")) {
                    Text(if (socks) "● SOCKS5" else "SOCKS5")
                }
            }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                singleLine = true,
                label = { Text("Proxy host") },
                modifier = Modifier.fillMaxWidth().testTag("danmaku-proxy-host-$tag"),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text("Proxy port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("danmaku-proxy-port-$tag"),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text(if (hadCredentials && username.isEmpty()) "Proxy username (saved)" else "Proxy username (optional)") },
                modifier = Modifier.fillMaxWidth().testTag("danmaku-proxy-username-$tag"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text(if (hadCredentials && password.isEmpty()) "Proxy password (saved)" else "Proxy password (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("danmaku-proxy-password-$tag"),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable this proxy", Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; persist() },
                    modifier = Modifier
                        .testTag("danmaku-proxy-active-$tag")
                        .semantics { contentDescription = "Enable this danmaku proxy" },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bypass proxy for local (LAN) addresses", Modifier.weight(1f))
                Switch(
                    checked = lanBypass,
                    onCheckedChange = { lanBypass = it; persist() },
                    modifier = Modifier
                        .testTag("danmaku-proxy-lanbypass-$tag")
                        .semantics { contentDescription = "Bypass proxy for local addresses" },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { persist() }, modifier = Modifier.testTag("danmaku-proxy-save-$tag")) {
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
                    modifier = Modifier.testTag("danmaku-proxy-test-$tag"),
                ) { Text(if (testing) "Testing…" else "Test endpoint") }
            }
        }
        resultText?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .testTag("danmaku-proxy-result-$tag")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
