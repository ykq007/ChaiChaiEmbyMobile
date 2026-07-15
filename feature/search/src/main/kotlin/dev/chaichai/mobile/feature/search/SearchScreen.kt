package dev.chaichai.mobile.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chaichai.mobile.design.system.EmptyDestination

@Composable
fun SearchScreen(modifier: Modifier = Modifier) = EmptyDestination(
    title = "Search",
    description = "Search across your configured server.",
    modifier = modifier,
)
