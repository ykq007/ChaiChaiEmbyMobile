package dev.chaichai.mobile.feature.libraries

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chaichai.mobile.design.system.EmptyDestination

@Composable
fun LibrariesScreen(modifier: Modifier = Modifier) = EmptyDestination(
    title = "Libraries",
    description = "Movies and shows will be organized here.",
    modifier = modifier,
)
