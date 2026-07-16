package dev.chaichai.mobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.AccountBoundary
import dev.chaichai.mobile.core.contracts.ServerDirectory
import dev.chaichai.mobile.core.contracts.ServerProxyBoundary
import dev.chaichai.mobile.core.contracts.SignOutState

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    account: AccountBoundary? = null,
    serverDirectory: ServerDirectory? = null,
    serverProxy: ServerProxyBoundary? = null,
) {
    if (account == null) {
        SettingsPlaceholder(modifier)
        return
    }
    val state by account.signOutState.collectAsState()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text("Account data and unsent watch progress stay scoped to this server and user.")
        if (serverDirectory != null) {
            ServerManagementSection(serverDirectory, serverProxy)
        }
        when (state) {
            SignOutState.Syncing -> CircularProgressIndicator()
            SignOutState.SignedOut -> Text("Signed out")
            else -> Button(onClick = account::requestSignOut) { Text("Sign out") }
        }
    }
    val confirmation = state as? SignOutState.ConfirmationRequired
    if (confirmation != null) {
        AlertDialog(
            onDismissRequest = account::cancelSignOut,
            title = { Text("Progress hasn't synced") },
            text = { Text(confirmation.message) },
            confirmButton = {
                Button(onClick = account::confirmProgressLoss) { Text("Discard progress and sign out") }
            },
            dismissButton = { TextButton(onClick = account::cancelSignOut) { Text("Keep account") } },
        )
    }
}

@Composable
private fun SettingsPlaceholder(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text("Add a server to begin the First Viewing Loop.")
    }
}
