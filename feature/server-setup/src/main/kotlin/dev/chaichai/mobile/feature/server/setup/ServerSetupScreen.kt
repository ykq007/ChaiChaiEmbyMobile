package dev.chaichai.mobile.feature.server.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.ServerSetupBoundary
import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.core.contracts.SetupFailure

@Composable
fun ServerSetupScreen(boundary: ServerSetupBoundary, modifier: Modifier = Modifier) {
    val state by boundary.state.collectAsState()
    SetupContent(state, boundary, modifier)
}

@Composable
private fun SetupContent(state: ServerSetupState, boundary: ServerSetupBoundary, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "Connect to Emby",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        when (state) {
            ServerSetupState.Restoring -> Progress("Restoring your server session…")
            is ServerSetupState.EnterAddress -> AddressEntry(state, boundary)
            is ServerSetupState.CleartextRisk -> CleartextRisk(state, boundary)
            is ServerSetupState.Probing -> Progress("Checking ${state.address}…")
            is ServerSetupState.CertificateRisk -> CertificateRisk(state, boundary)
            is ServerSetupState.ConfirmServer -> ConfirmServer(state, boundary)
            is ServerSetupState.SignIn -> SignIn(state, boundary)
            is ServerSetupState.Authenticating -> Progress("Signing in to ${state.serverName}…")
            is ServerSetupState.Authenticated -> Progress("Connected to ${state.serverName}")
            is ServerSetupState.Failure -> Failure(state, boundary)
        }
    }
}

@Composable
private fun AddressEntry(state: ServerSetupState.EnterAddress, boundary: ServerSetupBoundary) {
    var address by rememberSaveable(state.address) { mutableStateOf(state.address) }
    val guidance = state.guidance
    Text("Enter a hostname or full HTTP(S) Server Address. Deployment paths are preserved.")
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("Server Address") },
        supportingText = if (guidance == null) null else { { Text(guidance) } },
        isError = state.guidance != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = { boundary.submitAddress(address) }) { Text("Check server") }
}

@Composable
private fun CleartextRisk(state: ServerSetupState.CleartextRisk, boundary: ServerSetupBoundary) {
    Text("HTTP sends your sign-in data without transport encryption. ChaiChai will not rewrite or upgrade this address silently.")
    Text(state.address, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    Button(onClick = boundary::acceptCleartextRisk) { Text("I understand, continue with HTTP") }
}

@Composable
private fun CertificateRisk(state: ServerSetupState.CertificateRisk, boundary: ServerSetupBoundary) {
    Text("Android could not verify this server certificate or hostname.")
    Text("Certificate Bypass weakens verification only for ${state.address} and never transfers to redirects or other services.")
    Spacer(Modifier.height(16.dp))
    Button(onClick = boundary::acceptCertificateBypass) { Text("Enable Certificate Bypass for this server") }
}

@Composable
private fun ConfirmServer(state: ServerSetupState.ConfirmServer, boundary: ServerSetupBoundary) {
    Text("Confirm final Server Address", style = MaterialTheme.typography.titleLarge)
    Text(state.finalAddress)
    if (state.enteredAddress != state.finalAddress) Text("Redirected from ${state.enteredAddress}")
    Text("${state.serverName} • Emby ${state.version}")
    state.compatibilityWarning?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    Spacer(Modifier.height(16.dp))
    Button(onClick = boundary::confirmServer) { Text("Confirm and sign in") }
}

@Composable
private fun SignIn(state: ServerSetupState.SignIn, boundary: ServerSetupBoundary) {
    var username by rememberSaveable(state.address) { mutableStateOf(state.username) }
    var password by remember(state.address) { mutableStateOf("") }
    LaunchedEffect(state.error) { if (state.error != null) password = "" }
    Text("Sign in to ${state.serverName}", style = MaterialTheme.typography.titleLarge)
    Text(state.address, style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(it.actionableMessage(), color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("Username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = { boundary.authenticate(username.trim(), password) }) { Text("Sign in") }
}

@Composable
private fun Failure(state: ServerSetupState.Failure, boundary: ServerSetupBoundary) {
    Text(state.reason.actionableMessage(), color = MaterialTheme.colorScheme.error)
    Text(state.guidance)
    Text(state.address, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    Button(onClick = boundary::retry) { Text("Retry") }
}

@Composable
private fun Progress(label: String) {
    CircularProgressIndicator()
    Spacer(Modifier.height(12.dp))
    Text(label)
}

private fun SetupFailure.actionableMessage() = when (this) {
    SetupFailure.InvalidAddress -> "That Server Address is not valid."
    SetupFailure.Timeout -> "The server timed out."
    SetupFailure.Tls -> "The certificate could not be verified."
    SetupFailure.TransportPolicy -> "The server redirect was blocked for safety."
    SetupFailure.IncompatibleServer -> "This Emby server is incompatible."
    SetupFailure.InvalidCredentials -> "The username or password was rejected. Enter the password again."
    SetupFailure.InsufficientAccess -> "This account does not have permission to play media."
    SetupFailure.Unreachable -> "The server is unreachable."
    SetupFailure.InvalidResponse -> "The address did not return a valid Emby response."
}
