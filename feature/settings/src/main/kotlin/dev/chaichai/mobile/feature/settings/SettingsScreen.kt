package dev.chaichai.mobile.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chaichai.mobile.design.system.EmptyDestination

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) = EmptyDestination(
    title = "Settings",
    description = "Add a server to begin the First Viewing Loop.",
    modifier = modifier,
)
