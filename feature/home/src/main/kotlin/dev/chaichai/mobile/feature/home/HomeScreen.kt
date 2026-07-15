package dev.chaichai.mobile.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chaichai.mobile.design.system.EmptyDestination

@Composable
fun HomeScreen(modifier: Modifier = Modifier, isHeightConstrained: Boolean) {
    EmptyDestination(
        title = "Home",
        description = if (isHeightConstrained) "Your library will appear here." else "Your quiet screening room is ready for a server.",
        modifier = modifier,
    )
}
