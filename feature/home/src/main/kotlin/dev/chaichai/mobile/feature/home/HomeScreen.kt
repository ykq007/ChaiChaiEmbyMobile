package dev.chaichai.mobile.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.chaichai.mobile.core.contracts.ArtworkReference
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.HomeFeedState
import dev.chaichai.mobile.core.contracts.HomeMediaItem
import dev.chaichai.mobile.core.contracts.HomeSection
import dev.chaichai.mobile.core.contracts.HomeMediaAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import dev.chaichai.mobile.design.system.AuthenticatedArtwork

enum class HomeWindowClass { Compact, Medium, Expanded }

class HomeUiState internal constructor(private val shelfPositions: MutableMap<HomeSection, Int>) {
    internal fun position(section: HomeSection) = shelfPositions[section] ?: 0
    internal fun record(section: HomeSection, position: Int) { shelfPositions[section] = position }
}

@Composable
fun rememberHomeUiState(): HomeUiState = rememberSaveable(
    saver = listSaver(
        save = { state -> HomeSection.entries.map(state::position) },
        restore = { positions -> HomeUiState(HomeSection.entries.zip(positions).toMap().toMutableMap()) },
    ),
) { HomeUiState(mutableMapOf()) }

@Composable
fun HomeScreen(
    gateway: EmbyGateway,
    modifier: Modifier = Modifier,
    isHeightConstrained: Boolean,
    windowClass: HomeWindowClass,
    onMediaAction: (HomeMediaAction) -> Unit,
    uiState: HomeUiState = rememberHomeUiState(),
) {
    val state by gateway.homeFeed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(gateway, state is HomeFeedState.Loading) {
        if (gateway.homeFeed.value is HomeFeedState.Loading) gateway.refreshHome()
    }
    Box(
        modifier.fillMaxSize().semantics {
            contentDescription = "Home discovery content, ${windowClass.name.lowercase()} layout"
            isTraversalGroup = true
        },
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
                windowClass = windowClass,
                onMediaAction = onMediaAction,
                uiState = uiState,
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
    windowClass: HomeWindowClass,
    onMediaAction: (HomeMediaAction) -> Unit,
    uiState: HomeUiState,
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
                Modifier.fillMaxWidth().padding(horizontal = windowClass.metrics.horizontalPadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Home",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading(); traversalIndex = 0f },
                )
                OutlinedButton(
                    onClick = refresh,
                    enabled = !feed.isRefreshing,
                    modifier = Modifier.semantics { traversalIndex = .5f },
                ) {
                    Text(if (feed.isRefreshing) "Refreshing" else "Refresh")
                }
            }
        }
        if (hero != null && !isHeightConstrained) {
            item("spotlight-${hero.identity.itemId}") {
                Spotlight(gateway, hero, windowClass, onMediaAction)
            }
        }
        HomeSection.entries.forEach { section ->
            val content = feed.sections[section] ?: return@forEach
            if (content.items.isNotEmpty() || content.failureMessage != null) {
                item("heading-${section.name}") {
                    Column(Modifier.padding(top = 22.dp)) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = windowClass.metrics.horizontalPadding).semantics {
                                heading(); traversalIndex = 2f + section.ordinal * 10f
                            },
                        )
                        if (content.isStale) {
                            Text(
                                "Showing saved content",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = windowClass.metrics.horizontalPadding)
                                    .semantics { traversalIndex = 2.5f + section.ordinal * 10f },
                            )
                        }
                    }
                }
                if (content.items.isNotEmpty()) {
                    item("shelf-${section.name}") {
                        MediaShelf(gateway, content.items, section, windowClass, onMediaAction, uiState)
                    }
                }
                content.failureMessage?.let { message ->
                    item("error-${section.name}") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = windowClass.metrics.horizontalPadding, vertical = 8.dp)
                                .semantics { traversalIndex = 8f + section.ordinal * 10f },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(message, modifier = Modifier.fillMaxWidth(.68f), color = MaterialTheme.colorScheme.error)
                            Button(
                                onClick = { retry(section) },
                                modifier = Modifier.semantics { traversalIndex = 9f + section.ordinal * 10f },
                            ) { Text("Retry ${section.title}") }
                        }
                    }
                }
            }
        }
    }
}

private fun spotlightItem(feed: HomeFeedState.Ready): HomeMediaItem? {
    val resumable = feed.sections[HomeSection.ContinueWatching]?.items?.firstOrNull {
        it.hasMeaningfulResume
    }
    return resumable ?: feed.sections[HomeSection.NextUp]?.items?.firstOrNull()
}

@Composable
private fun Spotlight(
    gateway: EmbyGateway,
    item: HomeMediaItem,
    windowClass: HomeWindowClass,
    onMediaAction: (HomeMediaAction) -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().height(windowClass.metrics.heroHeight).testTag("home-spotlight")
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        item.backdrop?.let { HomeArtwork(gateway, it, item.title, Modifier.fillMaxSize()) }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(windowClass.metrics.heroTextWidthFraction)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .82f)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Spotlight", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(item.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Button(modifier = Modifier.semantics { traversalIndex = 1f }, onClick = {
                onMediaAction(
                    if (item.hasMeaningfulResume) HomeMediaAction.Resume(item.identity, item.playbackPositionTicks)
                    else HomeMediaAction.OpenDetails(item.identity),
                )
            }) {
                Text(if (item.hasMeaningfulResume) "Resume ${formatTicks(item.playbackPositionTicks)}" else "View details")
            }
        }
    }
}

@Composable
private fun MediaShelf(
    gateway: EmbyGateway,
    media: List<HomeMediaItem>,
    section: HomeSection,
    windowClass: HomeWindowClass,
    onMediaAction: (HomeMediaAction) -> Unit,
    uiState: HomeUiState,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = uiState.position(section))
    LaunchedEffect(state, section) {
        snapshotFlow { state.firstVisibleItemIndex }.collectLatest { uiState.record(section, it) }
    }
    LazyRow(
        state = state,
        modifier = Modifier.testTag("shelf-${section.name}"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = windowClass.metrics.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(windowClass.metrics.shelfGap),
    ) {
        items(media, key = { "${it.identity.serverId}:${it.identity.itemId}" }) { item ->
            Column(Modifier.width(if (section == HomeSection.ContinueWatching || section == HomeSection.NextUp) {
                windowClass.metrics.landscapeWidth
            } else windowClass.metrics.posterWidth).testTag("media-${item.identity.serverId}:${item.identity.itemId}").semantics {
                contentDescription = "${item.title}, ${item.mediaType}"
                traversalIndex = 3f + section.ordinal * 10f + media.indexOf(item) / 100f
            }.clickable {
                onMediaAction(HomeMediaAction.OpenDetails(item.identity))
            }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(
                        if (section == HomeSection.ContinueWatching || section == HomeSection.NextUp) 16f / 9f else 2f / 3f,
                    ).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    item.artwork?.let { HomeArtwork(gateway, it, item.title, Modifier.fillMaxSize()) }
                }
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun HomeArtwork(gateway: EmbyGateway, reference: ArtworkReference, title: String, modifier: Modifier) =
    AuthenticatedArtwork(
        cacheIdentity = "${reference.identity.serverId}:${reference.identity.itemId}:${reference.kind}:${reference.imageTag}",
        contentDescription = "$title artwork",
        load = { gateway.loadArtwork(reference) },
        modifier = modifier,
    )

@Composable
private fun StateMessage(message: String, action: String? = null, onAction: () -> Unit = {}, showProgress: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showProgress) CircularProgressIndicator(Modifier.size(36.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        action?.let { Button(onClick = onAction) { Text(it) } }
    }
}

private fun formatTicks(ticks: Long): String {
    val seconds = ticks / HomeMediaItem.TicksPerSecond
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private data class HomeMetrics(
    val horizontalPadding: androidx.compose.ui.unit.Dp,
    val heroHeight: androidx.compose.ui.unit.Dp,
    val heroTextWidthFraction: Float,
    val shelfGap: androidx.compose.ui.unit.Dp,
    val landscapeWidth: androidx.compose.ui.unit.Dp,
    val posterWidth: androidx.compose.ui.unit.Dp,
)

private val HomeWindowClass.metrics get() = when (this) {
    HomeWindowClass.Compact -> HomeMetrics(16.dp, 240.dp, .88f, 12.dp, 252.dp, 144.dp)
    HomeWindowClass.Medium -> HomeMetrics(22.dp, 280.dp, .78f, 14.dp, 280.dp, 158.dp)
    HomeWindowClass.Expanded -> HomeMetrics(28.dp, 320.dp, .58f, 16.dp, 320.dp, 176.dp)
}
