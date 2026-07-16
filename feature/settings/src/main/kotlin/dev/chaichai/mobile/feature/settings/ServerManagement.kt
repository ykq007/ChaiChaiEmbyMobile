package dev.chaichai.mobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.chaichai.mobile.core.contracts.ConfiguredServer
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.ProxyKind
import dev.chaichai.mobile.core.contracts.ProxyTestResult
import dev.chaichai.mobile.core.contracts.ServerDirectory
import dev.chaichai.mobile.core.contracts.ServerIcon
import dev.chaichai.mobile.core.contracts.ServerIconShape
import dev.chaichai.mobile.core.contracts.ServerProxyBoundary
import dev.chaichai.mobile.core.contracts.ServerProxyConfig
import dev.chaichai.mobile.core.contracts.ServerRemovalState
import kotlinx.coroutines.launch

private val PresetColors: List<Int> = listOf(
    0xFF3F51B5.toInt(), // indigo
    0xFF2E7D32.toInt(), // green
    0xFFC62828.toInt(), // red
    0xFF00838F.toInt(), // teal
    0xFF6A1B9A.toInt(), // purple
    0xFFEF6C00.toInt(), // orange
)

private val PresetGlyphs: List<String> = listOf("🎬", "📺", "🏠", "⭐", "🍿", "📀")

@Composable
fun ServerManagementSection(
    directory: ServerDirectory,
    proxy: ServerProxyBoundary? = null,
    modifier: Modifier = Modifier,
) {
    val state by directory.state.collectAsState()
    val removal by directory.removalState.collectAsState()
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var iconTarget by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Servers",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text("Switch between your Emby servers. Each server keeps its own account, data, and settings.")
        if (state.servers.isEmpty()) {
            Text("No servers configured yet.")
        }
        state.servers.forEachIndexed { index, server ->
            ServerRow(
                server = server,
                canMoveUp = index > 0,
                canMoveDown = index < state.servers.lastIndex,
                onSelect = { directory.selectServer(server.id) },
                onRename = { renameTarget = server.id },
                onIcon = { iconTarget = server.id },
                onMoveUp = { directory.reorder(server.id, index - 1) },
                onMoveDown = { directory.reorder(server.id, index + 1) },
                onRemove = { directory.requestRemove(server.id) },
                proxy = proxy,
            )
        }
        Button(
            onClick = directory::beginAddServer,
            modifier = Modifier.testTag("add-server"),
        ) { Text("Add server") }
    }

    renameTarget?.let { id ->
        val server = state.servers.firstOrNull { it.id == id }
        if (server == null) {
            renameTarget = null
        } else {
            RenameDialog(
                server = server,
                onDismiss = { renameTarget = null },
                onConfirm = { alias ->
                    directory.rename(id, alias)
                    renameTarget = null
                },
            )
        }
    }

    iconTarget?.let { id ->
        val server = state.servers.firstOrNull { it.id == id }
        if (server == null) {
            iconTarget = null
        } else {
            IconPickerDialog(
                server = server,
                onDismiss = { iconTarget = null },
                onConfirm = { icon ->
                    directory.updateIcon(id, icon)
                    iconTarget = null
                },
            )
        }
    }

    (removal as? ServerRemovalState.ConfirmationRequired)?.let { required ->
        val confirmation = required.confirmation
        AlertDialog(
            onDismissRequest = directory::cancelRemove,
            title = { Text("Remove ${confirmation.serverName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(confirmation.message)
                    Text("This deletes on this device:", style = MaterialTheme.typography.labelLarge)
                    confirmation.affectedState.forEach { Text("• $it") }
                }
            },
            confirmButton = {
                Button(onClick = { directory.confirmRemove(confirmation.serverId) }) {
                    Text(if (confirmation.unsyncedWorkAtRisk) "Discard and remove" else "Remove server")
                }
            },
            dismissButton = { TextButton(onClick = directory::cancelRemove) { Text("Keep server") } },
        )
    }
}

@Composable
private fun ServerRow(
    server: ConfiguredServer,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onIcon: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    proxy: ServerProxyBoundary?,
) {
    val stateLabel = if (server.isActive) "Active server" else "Tap to switch to this server"
    Card(Modifier.fillMaxWidth().testTag("server-${server.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !server.isActive, onClick = onSelect)
                    .semantics { contentDescription = "${server.displayName}. $stateLabel" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ServerIconBadge(server.icon)
                Column(Modifier.weight(1f)) {
                    Text(server.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(server.address, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Non-color-only status: an explicit text label, not just the icon tint.
                    Text(
                        if (server.isActive) "Active" else "Not active",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSelect, enabled = !server.isActive) { Text("Use") }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onIcon) { Text("Icon") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("Move up") }
                TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("Move down") }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag("remove-${server.id}"),
                ) { Text("Remove") }
            }
            if (proxy != null) {
                HorizontalDivider()
                ProxySection(serverId = server.id, proxy = proxy)
            }
        }
    }
}

/**
 * Per-server Proxy Routing configuration: kind, host, port, optional credentials, enabled and LAN
 * bypass, plus a Test connection action surfacing the distinguished [ProxyTestResult]. No embedded
 * defaults are shown; credentials are never echoed into the result text.
 */
@Composable
private fun ProxySection(serverId: String, proxy: ServerProxyBoundary) {
    val initial = remember(serverId) { proxy.proxyConfig(serverId) }
    var enabled by rememberSaveable(serverId) { mutableStateOf(initial.enabled) }
    var socks by rememberSaveable(serverId) { mutableStateOf(initial.kind == ProxyKind.Socks5) }
    var host by rememberSaveable(serverId) { mutableStateOf(initial.host) }
    var port by rememberSaveable(serverId) { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    var username by rememberSaveable(serverId) { mutableStateOf("") }
    var password by rememberSaveable(serverId) { mutableStateOf("") }
    var hadCredentials by rememberSaveable(serverId) { mutableStateOf(initial.hasCredentials) }
    var lanBypass by rememberSaveable(serverId) { mutableStateOf(initial.lanBypass) }
    var resultText by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var testing by rememberSaveable(serverId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun currentConfig() = ServerProxyConfig(
        kind = if (socks) ProxyKind.Socks5 else ProxyKind.Http,
        host = host.trim(),
        port = port.trim().toIntOrNull() ?: 0,
        enabled = enabled,
        lanBypass = lanBypass,
        // Credentials exist if a new one is being entered, or one was already stored and not cleared.
        hasCredentials = password.isNotEmpty() || (hadCredentials && username.isEmpty() && password.isEmpty()) ||
            username.isNotEmpty(),
    )

    fun persist() {
        val config = currentConfig()
        val creds = if (username.isNotEmpty() || password.isNotEmpty()) {
            ProxyCredentials(username, password)
        } else {
            null
        }
        proxy.updateProxyConfig(serverId, config, creds)
        hadCredentials = config.hasCredentials
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Proxy Routing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Route this server through a proxy", Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it; persist() },
                modifier = Modifier
                    .testTag("proxy-enabled-$serverId")
                    .semantics { contentDescription = "Route this server through a proxy" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { socks = false },
                modifier = Modifier.testTag("proxy-kind-http-$serverId"),
            ) { Text(if (!socks) "● HTTP" else "HTTP") }
            TextButton(
                onClick = { socks = true },
                modifier = Modifier.testTag("proxy-kind-socks-$serverId"),
            ) { Text(if (socks) "● SOCKS5" else "SOCKS5") }
        }
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            singleLine = true,
            label = { Text("Proxy host") },
            modifier = Modifier.fillMaxWidth().testTag("proxy-host-$serverId"),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit) },
            singleLine = true,
            label = { Text("Proxy port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("proxy-port-$serverId"),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            singleLine = true,
            label = { Text(if (hadCredentials && username.isEmpty()) "Proxy username (saved)" else "Proxy username (optional)") },
            modifier = Modifier.fillMaxWidth().testTag("proxy-username-$serverId"),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            label = { Text(if (hadCredentials && password.isEmpty()) "Proxy password (saved)" else "Proxy password (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("proxy-password-$serverId"),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Bypass proxy for local (LAN) addresses", Modifier.weight(1f))
            Switch(
                checked = lanBypass,
                onCheckedChange = { lanBypass = it; persist() },
                modifier = Modifier
                    .testTag("proxy-lanbypass-$serverId")
                    .semantics { contentDescription = "Bypass proxy for local addresses" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { persist() },
                modifier = Modifier.testTag("proxy-save-$serverId"),
            ) { Text("Save proxy") }
            Button(
                onClick = {
                    persist()
                    testing = true
                    resultText = null
                    scope.launch {
                        val result = proxy.testConnection(serverId)
                        resultText = result.summary
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.testTag("proxy-test-$serverId"),
            ) { Text(if (testing) "Testing…" else "Test connection") }
        }
        resultText?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .testTag("proxy-result-$serverId")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun ServerIconBadge(icon: ServerIcon) {
    val shape = when (icon.shape) {
        ServerIconShape.Circle -> CircleShape
        ServerIconShape.Rounded -> RoundedCornerShape(12.dp)
        ServerIconShape.Shield -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
    }
    Box(
        Modifier.size(40.dp).background(Color(icon.colorArgb), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            icon.glyph.ifBlank { "●" },
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun RenameDialog(
    server: ConfiguredServer,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var text by rememberSaveable(server.id) { mutableStateOf(server.alias ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename server") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Alias (blank uses \"${server.serverName}\")") },
                modifier = Modifier.testTag("rename-field"),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IconPickerDialog(
    server: ConfiguredServer,
    onDismiss: () -> Unit,
    onConfirm: (ServerIcon) -> Unit,
) {
    var color by rememberSaveable(server.id) { mutableStateOf(server.icon.colorArgb) }
    var glyph by rememberSaveable(server.id) { mutableStateOf(server.icon.glyph) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an icon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Colour")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetColors.forEach { preset ->
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(Color(preset), CircleShape)
                                .clickable { color = preset }
                                .semantics { contentDescription = if (preset == color) "Selected colour" else "Colour option" },
                        )
                    }
                }
                Text("Symbol")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetGlyphs.forEach { preset ->
                        Box(
                            Modifier.size(40.dp).clickable { glyph = preset },
                            contentAlignment = Alignment.Center,
                        ) { Text(preset, style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(ServerIcon(glyph = glyph, colorArgb = color, shape = server.icon.shape)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
