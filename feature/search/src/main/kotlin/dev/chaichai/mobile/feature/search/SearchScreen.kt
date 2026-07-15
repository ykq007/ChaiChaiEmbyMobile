package dev.chaichai.mobile.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.SearchMediaType
import dev.chaichai.mobile.core.contracts.SearchResult
import dev.chaichai.mobile.core.contracts.SearchResultGroup
import dev.chaichai.mobile.core.contracts.SearchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SearchHingePanes(
    val collectionWidth: Dp,
    val hingeWidth: Dp,
    val detailWidth: Dp,
)

@Composable
fun SearchScreen(
    gateway: EmbyGateway,
    supportsListDetail: Boolean,
    onOpenDetails: (SearchResult) -> Unit,
    detailContent: @Composable (SearchResult, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    hingePanes: SearchHingePanes? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable(stateSaver = SearchResultSaver) { mutableStateOf<SearchResult?>(null) }
    val state by gateway.searchState.collectAsStateWithLifecycle()
    val normalizedQuery = query.trim()
    val retryScope = rememberCoroutineScope()

    LaunchedEffect(normalizedQuery, gateway) {
        if (normalizedQuery.length >= MinimumQueryLength) {
            delay(SearchDebounceMillis)
            gateway.search(normalizedQuery)
        }
    }
    LaunchedEffect(state, normalizedQuery) {
        if (state.queryOrNull() == normalizedQuery && state !is SearchState.Searching) {
            val available = resultGroups(state).flatMap { it.items }
            selected = selected?.takeIf { current -> available.any { it.identity == current.identity } }
        }
    }
    val collection: @Composable (Modifier) -> Unit = { collectionModifier ->
        SearchCollection(
            query = query,
            onQueryChanged = {
                query = it
                selected = null
            },
            normalizedQuery = normalizedQuery,
            state = state,
            onRetry = { gatewayQuery ->
                gatewayQuery.takeIf { it.length >= MinimumQueryLength }?.let { retry ->
                    retryScope.launch { gateway.search(retry) }
                }
            },
            onSelect = { result ->
                if (supportsListDetail) selected = result else onOpenDetails(result)
            },
            listState = listState,
            modifier = collectionModifier,
        )
    }

    if (supportsListDetail && hingePanes != null) {
        Row(modifier.fillMaxSize()) {
            collection(Modifier.width(hingePanes.collectionWidth))
            Spacer(Modifier.width(hingePanes.hingeWidth))
            SearchDetail(selected, detailContent, Modifier.width(hingePanes.detailWidth).fillMaxHeight())
        }
    } else if (supportsListDetail) {
        Row(modifier.fillMaxSize()) {
            collection(Modifier.weight(0.52f))
            SearchDetail(selected, detailContent, Modifier.weight(0.48f).fillMaxHeight())
        }
    } else {
        collection(modifier)
    }
}

@Composable
private fun SearchCollection(
    query: String,
    onQueryChanged: (String) -> Unit,
    normalizedQuery: String,
    state: SearchState,
    onRetry: (String) -> Unit,
    onSelect: (SearchResult) -> Unit,
    listState: LazyListState,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().testTag("search-collection").padding(horizontal = 16.dp)) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            label = { Text("Search media") },
            supportingText = { Text("Movies, series, seasons, and episodes") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("search-field"),
        )
        val matchingState = state.takeIf { it.queryOrNull() == normalizedQuery }
        when {
            normalizedQuery.isEmpty() -> SearchMessage(
                "Find something to watch",
                "Enter at least 2 characters to search your configured server.",
            )
            normalizedQuery.length < MinimumQueryLength -> SearchMessage(
                "Keep typing",
                "Enter at least 2 non-whitespace characters.",
            )
            matchingState == null -> SearchingMessage(normalizedQuery)
            matchingState is SearchState.Searching -> {
                if (matchingState.restoredGroups.any { it.items.isNotEmpty() }) {
                    SearchResults(matchingState.restoredGroups, onSelect, listState, true)
                } else SearchingMessage(normalizedQuery)
            }
            matchingState is SearchState.Results -> SearchResults(matchingState.groups, onSelect, listState, false)
            matchingState is SearchState.Empty -> SearchMessage(
                "No results for “${matchingState.query}”",
                "Try a different title or fewer words.",
            )
            matchingState is SearchState.Failure -> Column(Modifier.fillMaxSize()) {
                if (matchingState.restoredGroups.any { it.items.isNotEmpty() }) {
                    Text(
                        "Saved results — refresh failed",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(matchingState.message)
                    Button({ onRetry(matchingState.query) }, Modifier.heightIn(min = 48.dp)) { Text("Retry search") }
                    SearchResults(matchingState.restoredGroups, onSelect, listState, false)
                } else SearchFailure(matchingState, onRetry)
            }
            else -> SearchingMessage(normalizedQuery)
        }
    }
}

@Composable
private fun SearchResults(
    groups: List<SearchResultGroup>,
    onSelect: (SearchResult) -> Unit,
    listState: LazyListState,
    isRefreshing: Boolean,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("search-results"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isRefreshing) item("refreshing") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.width(24.dp))
                Text("Refreshing saved results")
            }
        }
        groups.filter { it.items.isNotEmpty() }.forEach { group ->
            val traversalBase = group.mediaType.ordinal * 1_000
            item("heading-${group.mediaType.name}") {
                Text(
                    group.mediaType.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 12.dp).semantics {
                        heading()
                        traversalIndex = traversalBase.toFloat()
                    },
                )
            }
            itemsIndexed(
                group.items,
                key = { _, result -> "${result.identity.serverId}:${result.identity.itemId}" },
            ) { index, result ->
                SearchResultRow(result, traversalBase + index + 1, onSelect)
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, traversalOrder: Int, onSelect: (SearchResult) -> Unit) {
    val subtitle = when (result.mediaType) {
        SearchMediaType.Movie, SearchMediaType.Series -> result.year?.toString()
        SearchMediaType.Season -> listOfNotNull(result.seriesName, result.seasonNumber?.let { "Season $it" }).joinToString(" • ")
        SearchMediaType.Episode -> listOfNotNull(
            result.seriesName,
            result.seasonNumber?.let { "S$it" },
            result.episodeNumber?.let { "E$it" },
        ).joinToString(" • ")
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(result) }.padding(vertical = 8.dp)
            .semantics { traversalIndex = traversalOrder.toFloat() },
    ) {
        Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchDetail(
    selected: SearchResult?,
    detailContent: @Composable (SearchResult, Modifier) -> Unit,
    modifier: Modifier,
) {
    selected?.let { detailContent(it, modifier) } ?: Box(
        modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Select a result to view details")
    }
}

@Composable
private fun SearchingMessage(query: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text("Searching for “$query”", modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun SearchMessage(title: String, description: String) = Box(
    Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(description)
    }
}

@Composable
private fun SearchFailure(state: SearchState.Failure, onRetry: (String) -> Unit) = Box(
    Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Search unavailable", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(state.message)
        Button({ onRetry(state.query) }, Modifier.heightIn(min = 48.dp)) { Text("Retry search") }
    }
}

private fun resultGroups(state: SearchState): List<SearchResultGroup> = when (state) {
    is SearchState.Results -> state.groups
    is SearchState.Searching -> state.restoredGroups
    is SearchState.Failure -> state.restoredGroups
    else -> emptyList()
}

private fun SearchState.queryOrNull(): String? = when (this) {
    SearchState.Initial -> null
    is SearchState.Searching -> query
    is SearchState.Results -> query
    is SearchState.Empty -> query
    is SearchState.Failure -> query
}

private val SearchResultSaver = Saver<SearchResult?, List<String>>(
    save = { result ->
        result?.let {
            listOf(
                it.scope.serverId,
                it.scope.userId,
                it.identity.itemId,
                it.mediaType.name,
                it.title,
                it.seriesIdentity?.itemId.orEmpty(),
                it.seasonIdentity?.itemId.orEmpty(),
            )
        } ?: emptyList()
    },
    restore = { values ->
        values.takeIf { it.size == 7 }?.let {
            val scope = dev.chaichai.mobile.core.contracts.HomeScope(it[0], it[1])
            SearchResult(
                scope,
                dev.chaichai.mobile.core.contracts.MediaIdentity(scope.serverId, it[2]),
                SearchMediaType.valueOf(it[3]),
                it[4],
                seriesIdentity = it[5].takeIf(String::isNotEmpty)?.let { id ->
                    dev.chaichai.mobile.core.contracts.MediaIdentity(scope.serverId, id)
                },
                seasonIdentity = it[6].takeIf(String::isNotEmpty)?.let { id ->
                    dev.chaichai.mobile.core.contracts.MediaIdentity(scope.serverId, id)
                },
            )
        }
    },
)

private const val MinimumQueryLength = 2
private const val SearchDebounceMillis = 300L
