package dev.chaichai.mobile.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chaichai.mobile.design.system.EmptyDestination

@Composable
fun HomeScreen(modifier: Modifier = Modifier, isHeightConstrained: Boolean, status: String) {
    EmptyDestination(
        title = "Home",
        description = if (isHeightConstrained) "Your library will appear here.\n$status" else "Your quiet screening room is ready for a server.\n$status",
        modifier = modifier,
    )
}
