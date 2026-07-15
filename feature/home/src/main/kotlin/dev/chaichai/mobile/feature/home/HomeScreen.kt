package dev.chaichai.mobile.feature.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.chaichai.mobile.core.contracts.ArtworkReference
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.HomeSection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    gateway: EmbyGateway,
    modifier: Modifier = Modifier,
    isHeightConstrained: Boolean,
    isExpanded: Boolean,
) {
    val state by gateway.homeFeed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(gateway) {
        if (gateway.homeFeed.value is HomeFeedState.Loading) gateway.refreshHome()
    }
    Box(
        modifier.fillMaxSize().semantics { contentDescription = "Home discovery content" },
        contentAlignment = Alignment.Center,
    ) {
        if (state !is HomeFeedState.Ready) {
            Text(
                "Home",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp).semantics { heading() },
            )
        }
        when (val feed = state) {
            HomeFeedState.Loading -> StateMessage("Loading Home", showProgress = true)
            is HomeFeedState.Failure -> StateMessage(feed.message, "Retry", onAction = { scope.launch { gateway.refreshHome() } })
            is HomeFeedState.Empty -> StateMessage("Your Home is empty", "Refresh", onAction = { scope.launch { gateway.refreshHome() } })
            is HomeFeedState.Ready -> HomeContent(
                gateway = gateway,
                feed = feed,
                isHeightConstrained = isHeightConstrained,
                isExpanded = isExpanded,
                refresh = { scope.launch { gateway.refreshHome() } },
                retry = { scope.launch { gateway.retryHomeSection(it) } },
            )
        }
    }
}

@Composable
private fun HomeContent(
    gateway: EmbyGateway,
    feed: HomeFeedState.Ready,
    isHeightConstrained: Boolean,
    isExpanded: Boolean,
    refresh: () -> Unit,
    retry: (HomeSection) -> Unit,
) {
    val listState = rememberLazyListState()
    val hero = spotlightItem(feed)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
    ) {
        item("home-header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (isExpanded) 28.dp else 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Home", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                OutlinedButton(onClick = refresh, enabled = !feed.isRefreshing) {
                    Text(if (feed.isRefreshing) "Refreshing" else "Refresh")
                }
            }
        }
        if (hero != null && !isHeightConstrained) {
            item("spotlight-${hero.itemId}") { Spotlight(gateway, hero, isExpanded) }
        }
        HomeSection.entries.forEach { section ->
            val content = feed.sections[section] ?: return@forEach
            if (content.items.isNotEmpty() || content.failureMessage != null) {
                item("heading-${section.name}") {
                    Column(Modifier.padding(top = 22.dp)) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = if (isExpanded) 28.dp else 16.dp).semantics { heading() },
                        )
                        if (content.isStale) {
                            Text(
                                "Showing saved content",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = if (isExpanded) 28.dp else 16.dp),
                            )
                        }
                    }
                }
                if (content.items.isNotEmpty()) {
                    item("shelf-${section.name}") {
                        MediaShelf(gateway, content.items, section, isExpanded)
                    }
                }
                content.failureMessage?.let { message ->
                    item("error-${section.name}") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = if (isExpanded) 28.dp else 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(message, modifier = Modifier.fillMaxWidth(.68f), color = MaterialTheme.colorScheme.error)
                            Button(onClick = { retry(section) }) { Text("Retry ${section.title}") }
                        }
                    }
                }
            }
        }
    }
}

private fun spotlightItem(feed: HomeFeedState.Ready): HomeMediaItem? {
    val resumable = feed.sections[HomeSection.ContinueWatching]?.items?.firstOrNull {
        val runtime = it.runtimeTicks
        it.playbackPositionTicks >= 100_000_000 &&
            (runtime == null || runtime - it.playbackPositionTicks >= 600_000_000)
    }
    return resumable ?: feed.sections[HomeSection.NextUp]?.items?.firstOrNull()
}

@Composable
private fun Spotlight(gateway: EmbyGateway, item: HomeMediaItem, isExpanded: Boolean) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 220.dp, max = if (isExpanded) 300.dp else 280.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        item.backdrop?.let { AuthenticatedArtwork(gateway, it, item.title, Modifier.fillMaxSize()) }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(if (isExpanded) .62f else .88f)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .82f)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Spotlight", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(item.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Button(onClick = { }) {
                Text(if (item.playbackPositionTicks >= 100_000_000) "Resume ${formatTicks(item.playbackPositionTicks)}" else "View details")
            }
        }
    }
}

@Composable
private fun MediaShelf(gateway: EmbyGateway, media: List<HomeMediaItem>, section: HomeSection, isExpanded: Boolean) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        modifier = Modifier.testTag("shelf-${section.name}"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (isExpanded) 28.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isExpanded) 16.dp else 12.dp),
    ) {
        items(media, key = { "${it.serverId}:${it.itemId}" }) { item ->
            Column(Modifier.width(if (section == HomeSection.ContinueWatching || section == HomeSection.NextUp) {
                if (isExpanded) 300.dp else 252.dp
            } else if (isExpanded) 172.dp else 144.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(
                        if (section == HomeSection.ContinueWatching || section == HomeSection.NextUp) 16f / 9f else 2f / 3f,
                    ).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    item.artwork?.let { AuthenticatedArtwork(gateway, it, item.title, Modifier.fillMaxSize()) }
                }
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun AuthenticatedArtwork(gateway: EmbyGateway, reference: ArtworkReference, title: String, modifier: Modifier) {
    var bytes by remember(reference) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(gateway, reference) { bytes = gateway.loadArtwork(reference) }
    bytes?.let { encoded ->
        val bitmap = remember(encoded) { BitmapFactory.decodeByteArray(encoded, 0, encoded.size)?.asImageBitmap() }
        bitmap?.let { Image(it, contentDescription = "$title artwork", contentScale = ContentScale.Crop, modifier = modifier) }
    }
}

@Composable
private fun StateMessage(message: String, action: String? = null, onAction: () -> Unit = {}, showProgress: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showProgress) CircularProgressIndicator(Modifier.size(36.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        action?.let { Button(onClick = onAction) { Text(it) } }
    }
}

private fun formatTicks(ticks: Long): String {
    val seconds = ticks / 10_000_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
