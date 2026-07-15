package dev.chaichai.mobile.feature.libraries

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MovieDetails
import dev.chaichai.mobile.core.contracts.MovieDetailsState
import dev.chaichai.mobile.core.contracts.LibraryQuery
import dev.chaichai.mobile.core.contracts.MovieLibraryState
import dev.chaichai.mobile.core.contracts.MediaPlaybackRequest
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.LibrarySortField
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.SortDirection
import dev.chaichai.mobile.core.contracts.EpisodeDetails
import dev.chaichai.mobile.core.contracts.EpisodeDetailsState
import dev.chaichai.mobile.core.contracts.EpisodeSummary
import dev.chaichai.mobile.core.contracts.SeasonEpisodesState
import dev.chaichai.mobile.core.contracts.SeasonSummary
import dev.chaichai.mobile.core.contracts.SeriesDetails
import dev.chaichai.mobile.core.contracts.SeriesDetailsState
import dev.chaichai.mobile.core.contracts.SeriesLibraryState
import dev.chaichai.mobile.design.system.AuthenticatedArtwork
import kotlinx.coroutines.launch

enum class LibraryWindowClass { Compact, Medium, Expanded }

data class HingeListDetailPanes(
    val collectionWidth: Dp,
    val hingeWidth: Dp,
    val detailWidth: Dp,
)

@Composable
fun LibrariesScreen(
    gateway: EmbyGateway,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    supportsListDetail: Boolean,
    playback: PlaybackCoordinator,
    onOpenDetails: (MediaIdentity) -> Unit,
    modifier: Modifier = Modifier,
    hingePanes: HingeListDetailPanes? = null,
    initialSelection: MediaIdentity? = null,
    onSelectionChanged: (MediaIdentity?) -> Unit = {},
    detailsAuthenticationReturnDestination: String? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    initialCollection: LibraryCollection = LibraryCollection.Movies,
    selectedCollection: LibraryCollection? = null,
    onCollectionChanged: (LibraryCollection) -> Unit = {},
) {
    var savedCollection by rememberSaveable { androidx.compose.runtime.mutableStateOf(initialCollection) }
    val collection = selectedCollection ?: savedCollection
    val selectCollection: (LibraryCollection) -> Unit = { selected ->
        if (selected != collection) onSelectionChanged(null)
        savedCollection = selected
        onCollectionChanged(selected)
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(collection == LibraryCollection.Movies, { selectCollection(LibraryCollection.Movies) }, { Text("Movies") })
            FilterChip(collection == LibraryCollection.Shows, { selectCollection(LibraryCollection.Shows) }, { Text("Shows") })
        }
        if (collection == LibraryCollection.Movies) {
            MovieLibrariesScreen(
                gateway, windowClass, isHeightConstrained, supportsListDetail, playback, onOpenDetails,
                Modifier.weight(1f), hingePanes, initialSelection, onSelectionChanged,
                detailsAuthenticationReturnDestination, gridState,
            )
        } else {
            SeriesLibraryScreen(
                gateway, windowClass, isHeightConstrained, supportsListDetail, playback,
                Modifier.weight(1f), hingePanes, initialSelection, onSelectionChanged,
                detailsAuthenticationReturnDestination,
            )
        }
    }
}

enum class LibraryCollection { Movies, Shows }

@Composable
private fun MovieLibrariesScreen(
    gateway: EmbyGateway,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    supportsListDetail: Boolean,
    playback: PlaybackCoordinator,
    onOpenDetails: (MediaIdentity) -> Unit,
    modifier: Modifier,
    hingePanes: HingeListDetailPanes?,
    initialSelection: MediaIdentity?,
    onSelectionChanged: (MediaIdentity?) -> Unit,
    detailsAuthenticationReturnDestination: String?,
    gridState: LazyGridState,
) {
    val state by gateway.movieLibrary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val initialQuery = (gateway.movieLibrary.value as? MovieLibraryState.Ready)?.query ?: LibraryQuery()
    var savedSort by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(initialQuery.sortField.ordinal) }
    var savedDirection by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(initialQuery.sortDirection.ordinal) }
    var savedGenre by rememberSaveable { androidx.compose.runtime.mutableStateOf(initialQuery.genre) }
    val savedQuery = LibraryQuery(
        LibrarySortField.entries[savedSort],
        SortDirection.entries[savedDirection],
        savedGenre,
    )
    var selected by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(initialSelection)
    }
    val activeServerId = when (val snapshot = state) {
        is MovieLibraryState.Ready -> snapshot.scope.serverId
        is MovieLibraryState.EmptyLibrary -> snapshot.scope.serverId
        is MovieLibraryState.EmptyFiltered -> snapshot.scope.serverId
        is MovieLibraryState.Failure -> snapshot.scope?.serverId
        MovieLibraryState.Loading -> null
    }
    LaunchedEffect(initialSelection) {
        if (selected != initialSelection) selected = initialSelection
    }
    LaunchedEffect(activeServerId, selected) {
        if (activeServerId != null && selected?.serverId != null && selected?.serverId != activeServerId) {
            selected = null
            onSelectionChanged(null)
        }
    }
    LaunchedEffect(gateway) {
        if (gateway.movieLibrary.value is MovieLibraryState.Loading) gateway.refreshMovies(savedQuery)
    }
    LaunchedEffect(supportsListDetail, selected) {
        if (!supportsListDetail && selected != null) {
            val identity = selected!!
            selected = null
            onSelectionChanged(null)
            onOpenDetails(identity)
        }
    }
    val requestQuery: (LibraryQuery) -> Unit = { query ->
        savedSort = query.sortField.ordinal
        savedDirection = query.sortDirection.ordinal
        savedGenre = query.genre
        scope.launch { gateway.refreshMovies(query) }
    }
    val open: (MediaIdentity) -> Unit = { identity ->
        if (supportsListDetail) {
            selected = identity
            onSelectionChanged(identity)
        } else onOpenDetails(identity)
    }
    if (supportsListDetail && hingePanes != null) {
        Row(modifier.fillMaxSize()) {
            MovieCollection(
                gateway, state, LibraryWindowClass.Compact, isHeightConstrained, open, requestQuery,
                Modifier.width(hingePanes.collectionWidth), gridState,
            )
            Spacer(Modifier.width(hingePanes.hingeWidth))
            selected?.let { identity ->
                MovieDetailsScreen(
                    gateway, identity, playback, LibraryWindowClass.Compact, isHeightConstrained,
                    Modifier.width(hingePanes.detailWidth).fillMaxHeight(),
                    detailsAuthenticationReturnDestination,
                )
            } ?: Box(
                Modifier.width(hingePanes.detailWidth).fillMaxHeight().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Select a movie to view details")
            }
        }
    } else if (supportsListDetail && selected != null) {
        Row(modifier.fillMaxSize()) {
            MovieCollection(
                gateway, state, windowClass, isHeightConstrained, open, requestQuery,
                Modifier.weight(0.56f), gridState,
            )
            MovieDetailsScreen(
                gateway, selected!!, playback, LibraryWindowClass.Medium, isHeightConstrained,
                Modifier.weight(0.44f).fillMaxHeight(),
                detailsAuthenticationReturnDestination,
            )
        }
    } else {
        MovieCollection(gateway, state, windowClass, isHeightConstrained, open, requestQuery, modifier, gridState)
    }
}

@Composable
private fun MovieCollection(
    gateway: EmbyGateway,
    state: MovieLibraryState,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    onOpenDetails: (MediaIdentity) -> Unit,
    onQuery: (LibraryQuery) -> Unit,
    modifier: Modifier,
    gridState: LazyGridState,
) {
    val scope = rememberCoroutineScope()
    when (state) {
        MovieLibraryState.Loading -> Column(modifier.fillMaxSize().padding(24.dp)) {
            Text("Libraries", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        is MovieLibraryState.Failure -> MessageState("Movies unavailable", state.message, "Retry") {
            scope.launch { gateway.refreshMovies(state.query) }
        }
        is MovieLibraryState.EmptyLibrary -> MessageState(
            "No movies in this library",
            "Movies added on your Emby server will appear here.",
            "Retry",
        ) { scope.launch { gateway.refreshMovies(state.query) } }
        is MovieLibraryState.EmptyFiltered -> Column(modifier.fillMaxSize()) {
            LibraryControls(state.query, state.availableGenres, onQuery)
            MessageState("No matching movies", "Try another genre or clear the filter.", "Clear filter") {
                onQuery(state.query.copy(genre = null))
            }
        }
        is MovieLibraryState.Ready -> ReadyMovieGrid(
            gateway, state, windowClass, isHeightConstrained, onOpenDetails, onQuery, modifier, gridState,
        )
    }
}

@Composable
private fun ReadyMovieGrid(
    gateway: EmbyGateway,
    state: MovieLibraryState.Ready,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    onOpenDetails: (MediaIdentity) -> Unit,
    onQuery: (LibraryQuery) -> Unit,
    modifier: Modifier,
    gridState: LazyGridState,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val fontScale = density.fontScale
    LaunchedEffect(gridState, state.items.size, state.totalCount, state.isRefreshing) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (!state.isRefreshing && last >= state.items.lastIndex - 4 &&
                state.items.size < state.totalCount && !state.isLoadingMore
            ) {
                gateway.loadNextMoviePage()
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = movieGridColumnCount(windowClass, maxWidth.value, fontScale)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("movie-grid"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isHeightConstrained) 8.dp else 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Movies", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    LibraryControls(state.query, state.availableGenres, onQuery)
                }
            }
            items(state.items, key = { "${it.identity.serverId}:${it.identity.itemId}" }) { movie ->
                MoviePosterCard(gateway, movie, onOpenDetails)
            }
            if (state.isLoadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.refreshFailureMessage?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { scope.launch { gateway.refreshMovies(state.query) } }) { Text("Retry refresh") }
                    }
                }
            }
            state.pageFailureMessage?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { scope.launch { gateway.retryMoviePage() } }) { Text("Retry page") }
                    }
                }
            }
        }
    }
}

internal fun movieGridColumnCount(
    windowClass: LibraryWindowClass,
    usableWidthDp: Float,
    fontScale: Float,
): Int {
    val (minimumWidth, range) = when (windowClass) {
        LibraryWindowClass.Compact -> 132f to 2..3
        LibraryWindowClass.Medium -> 150f to 4..6
        LibraryWindowClass.Expanded -> 164f to 6..8
    }
    val scaledMinimum = minimumWidth * fontScale.coerceIn(1f, 1.35f)
    return (usableWidthDp / scaledMinimum).toInt().coerceAtLeast(1).coerceAtMost(range.last)
}

@Composable
private fun LibraryControls(
    query: LibraryQuery,
    genres: List<String>,
    onQuery: (LibraryQuery) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LazyRow(Modifier.testTag("movie-sort-controls"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibrarySortField.entries) { sort ->
                FilterChip(selected = query.sortField == sort, onClick = { onQuery(query.copy(sortField = sort)) }, label = { Text(sort.label) })
            }
            item {
                FilterChip(
                    selected = query.sortDirection == SortDirection.Descending,
                    onClick = {
                        onQuery(query.copy(sortDirection = if (query.sortDirection == SortDirection.Ascending) SortDirection.Descending else SortDirection.Ascending))
                    },
                    label = { Text(query.sortDirection.label) },
                )
            }
        }
        if (genres.isNotEmpty()) LazyRow(Modifier.testTag("movie-genre-controls"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = query.genre == null, onClick = { onQuery(query.copy(genre = null)) }, label = { Text("All genres") }) }
            items(genres) { genre ->
                FilterChip(selected = query.genre == genre, onClick = { onQuery(query.copy(genre = genre)) }, label = { Text(genre) })
            }
        }
    }
}

@Composable
private fun MoviePosterCard(gateway: EmbyGateway, movie: MoviePoster, onClick: (MediaIdentity) -> Unit) {
    Column(Modifier.clickable { onClick(movie.identity) }.padding(bottom = 4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            movie.artwork?.let { artwork ->
                AuthenticatedArtwork(artwork, "Poster for ${movie.title}", { gateway.loadArtwork(artwork) }, Modifier.fillMaxSize())
            }
        }
        Text(movie.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        movie.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun MovieDetailsScreen(
    gateway: EmbyGateway,
    identity: MediaIdentity,
    playback: PlaybackCoordinator,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    modifier: Modifier = Modifier,
    authenticationReturnDestination: String? = null,
) {
    var retryAttempt by rememberSaveable(identity.serverId, identity.itemId) { androidx.compose.runtime.mutableIntStateOf(0) }
    var detailsState by androidx.compose.runtime.remember(identity.serverId, identity.itemId) {
        androidx.compose.runtime.mutableStateOf<MovieDetailsState?>(null)
    }
    LaunchedEffect(gateway, identity, retryAttempt, authenticationReturnDestination) {
        detailsState = gateway.loadMovieDetails(identity, authenticationReturnDestination)
    }
    when (val state = detailsState) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is MovieDetailsState.Failure -> MessageState("Details unavailable", state.message, "Retry") { retryAttempt += 1 }
        is MovieDetailsState.Ready -> MovieDetailsContent(
            gateway, state.details, playback, windowClass, isHeightConstrained, modifier,
        )
    }
}

@Composable
private fun MovieDetailsContent(
    gateway: EmbyGateway,
    details: MovieDetails,
    playback: PlaybackCoordinator,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    modifier: Modifier,
) {
    val metadata: @Composable (Modifier) -> Unit = { metadataModifier ->
        Column(metadataModifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(details.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            listOfNotNull(
                details.year?.toString(),
                details.runtimeTicks?.let(::formatRuntime),
                details.communityRating?.let { "★ $it" },
                details.criticRating?.let { "Critics $it" },
            ).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("  •  ")) }
            if (details.genres.isNotEmpty()) Text(details.genres.joinToString("  •  "))
            details.overview?.takeIf { it.isNotBlank() }?.let { Text(it) }
            val runtimeTicks = details.runtimeTicks
            if (details.playbackPositionTicks > 0 && runtimeTicks != null) {
                val progress = (details.playbackPositionTicks.toFloat() / runtimeTicks).coerceIn(0f, 1f)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().semantics {
                        progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(progress, 0f..1f)
                    },
                )
            }
            if (details.tracks.audioTracks > 0) Text("${details.tracks.audioTracks} audio track${if (details.tracks.audioTracks == 1) "" else "s"}")
            if (details.tracks.subtitleTracks > 0) Text("${details.tracks.subtitleTracks} subtitle track${if (details.tracks.subtitleTracks == 1) "" else "s"}")
            val scope = details.scope
            if (details.hasMeaningfulResume && scope != null) {
                Button(onClick = { playback.submit(MediaPlaybackRequest.Resume(details.identity, details.playbackPositionTicks, scope, details.title)) }) {
                    Text("Resume from ${formatPosition(details.playbackPositionTicks)}")
                }
                OutlinedButton(onClick = { playback.submit(MediaPlaybackRequest.PlayFromBeginning(details.identity, scope, details.title)) }) { Text("Play from beginning") }
            } else if (scope != null) {
                Button(onClick = { playback.submit(MediaPlaybackRequest.PlayFromBeginning(details.identity, scope, details.title)) }) { Text("Play") }
            }
        }
    }
    val art: @Composable () -> Unit = {
        (details.backdrop ?: details.artwork)?.let { artwork ->
            AuthenticatedArtwork(artwork, "Artwork for ${details.title}", { gateway.loadArtwork(artwork) }, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
    }
    if (windowClass == LibraryWindowClass.Expanded && !isHeightConstrained) Row(modifier.fillMaxSize()) {
        Box(Modifier.weight(1.1f)) { art() }
        Box(Modifier.weight(0.9f)) {
            metadata(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()))
        }
    } else Column(modifier.fillMaxSize().testTag("movie-details-scroll").verticalScroll(rememberScrollState())) {
        art()
        metadata(Modifier.fillMaxWidth())
    }
}

@Composable
private fun SeriesLibraryScreen(
    gateway: EmbyGateway,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    supportsListDetail: Boolean,
    playback: PlaybackCoordinator,
    modifier: Modifier,
    hingePanes: HingeListDetailPanes?,
    initialSelection: MediaIdentity?,
    onSelectionChanged: (MediaIdentity?) -> Unit,
    authenticationReturnDestination: String?,
) {
    val state by gateway.seriesLibrary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selectedSeriesId by rememberSaveable { androidx.compose.runtime.mutableStateOf(initialSelection?.itemId) }
    var selectedScope by rememberSaveable(stateSaver = HomeScopeSaver) { androidx.compose.runtime.mutableStateOf<HomeScope?>(null) }
    var compactDetails by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val selectedSeries = selectedSeriesId?.let { MediaIdentity(seriesStateScope(state)?.serverId ?: return@let null, it) }
    val initialQuery = (state as? SeriesLibraryState.Ready)?.query ?: LibraryQuery()
    var sort by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(initialQuery.sortField.ordinal) }
    var direction by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(initialQuery.sortDirection.ordinal) }
    var genre by rememberSaveable { androidx.compose.runtime.mutableStateOf(initialQuery.genre) }
    val query = LibraryQuery(LibrarySortField.entries[sort], SortDirection.entries[direction], genre)
    val requestQuery: (LibraryQuery) -> Unit = {
        sort = it.sortField.ordinal
        direction = it.sortDirection.ordinal
        genre = it.genre
        scope.launch { gateway.refreshSeries(it) }
    }
    LaunchedEffect(gateway) {
        if (gateway.seriesLibrary.value is SeriesLibraryState.Loading) gateway.refreshSeries(query)
    }
    LaunchedEffect(initialSelection, state) {
        val activeScope = seriesStateScope(state)
        if (initialSelection != null && activeScope?.serverId == initialSelection.serverId && selectedSeriesId != initialSelection.itemId) {
            selectedSeriesId = initialSelection.itemId
            selectedScope = activeScope
        }
    }
    LaunchedEffect(seriesStateScope(state), selectedSeriesId) {
        val activeScope = seriesStateScope(state)
        if (selectedScope != null && activeScope != null && selectedScope != activeScope) {
            selectedSeriesId = null
            selectedScope = null
            compactDetails = false
        } else if (activeScope == null && state !is SeriesLibraryState.Loading) {
            selectedSeriesId = null
            selectedScope = null
            compactDetails = false
        }
    }
    LaunchedEffect(supportsListDetail) {
        if (!supportsListDetail) compactDetails = false
    }
    val collection: @Composable (Modifier) -> Unit = { collectionModifier ->
        when (val snapshot = state) {
            SeriesLibraryState.Loading -> Column(collectionModifier.fillMaxSize().padding(24.dp)) {
                Text("Shows", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            is SeriesLibraryState.Failure -> MessageState("Shows unavailable", snapshot.message, "Retry") {
                scope.launch { gateway.refreshSeries(snapshot.query) }
            }
            is SeriesLibraryState.EmptyLibrary -> MessageState(
                "No shows in this library", "Shows added on your Emby server will appear here.", "Retry",
            ) { scope.launch { gateway.refreshSeries(snapshot.query) } }
            is SeriesLibraryState.EmptyFiltered -> Column(collectionModifier.fillMaxSize()) {
                LibraryControls(snapshot.query, snapshot.availableGenres, requestQuery)
                MessageState("No matching shows", "Try another genre or clear the filter.", "Clear filter") {
                    requestQuery(snapshot.query.copy(genre = null))
                }
            }
            is SeriesLibraryState.Ready -> ReadySeriesGrid(
                gateway, snapshot, windowClass, isHeightConstrained,
                {
                    selectedSeriesId = it.itemId
                    selectedScope = snapshot.scope
                    onSelectionChanged(it)
                    compactDetails = !supportsListDetail
                }, requestQuery, collectionModifier,
            )
        }
    }
    if (supportsListDetail && hingePanes != null) {
        Row(modifier.fillMaxSize()) {
            collection(Modifier.width(hingePanes.collectionWidth))
            Spacer(Modifier.width(hingePanes.hingeWidth))
            selectedSeries?.let {
                SeriesDetailsPane(
                    gateway, it, playback, isHeightConstrained,
                    Modifier.width(hingePanes.detailWidth).fillMaxHeight(), authenticationReturnDestination,
                )
            } ?: Box(Modifier.width(hingePanes.detailWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Select a show to view seasons")
            }
        }
    } else if (supportsListDetail) {
        Row(modifier.fillMaxSize()) {
            collection(Modifier.weight(0.5f))
            selectedSeries?.let {
                SeriesDetailsPane(gateway, it, playback, isHeightConstrained, Modifier.weight(0.5f), authenticationReturnDestination)
            } ?: Box(Modifier.weight(0.5f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Select a show to view seasons")
            }
        }
    } else if (selectedSeries != null && compactDetails) {
        Column(modifier.fillMaxSize()) {
            OutlinedButton(onClick = { compactDetails = false }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Back to shows") }
            SeriesDetailsPane(gateway, selectedSeries, playback, isHeightConstrained, Modifier.weight(1f), authenticationReturnDestination)
        }
    } else collection(modifier)
}

private fun seriesStateScope(state: SeriesLibraryState) = when (state) {
    is SeriesLibraryState.Ready -> state.scope
    is SeriesLibraryState.EmptyLibrary -> state.scope
    is SeriesLibraryState.EmptyFiltered -> state.scope
    is SeriesLibraryState.Failure -> state.scope
    SeriesLibraryState.Loading -> null
}

@Composable
private fun ReadySeriesGrid(
    gateway: EmbyGateway,
    state: SeriesLibraryState.Ready,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    onSelect: (MediaIdentity) -> Unit,
    onQuery: (LibraryQuery) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(gridState, state.items.size, state.totalCount, state.isRefreshing) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (!state.isRefreshing && last >= state.items.lastIndex - 4 && state.items.size < state.totalCount && !state.isLoadingMore) {
                gateway.loadNextSeriesPage()
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Fixed(movieGridColumnCount(windowClass, maxWidth.value, fontScale)),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("series-grid"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isHeightConstrained) 8.dp else 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Shows", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    LibraryControls(state.query, state.availableGenres, onQuery)
                }
            }
            items(state.items, key = { "${it.identity.serverId}:${it.identity.itemId}" }) { show ->
                MoviePosterCard(gateway, show, onSelect)
            }
            if (state.isLoadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.refreshFailureMessage?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f))
                    OutlinedButton({ scope.launch { gateway.refreshSeries(state.query) } }) { Text("Retry refresh") }
                }
            } }
            state.pageFailureMessage?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f))
                    OutlinedButton({ scope.launch { gateway.retrySeriesPage() } }) { Text("Retry page") }
                }
            } }
        }
    }
}

@Composable
fun SeriesDetailsScreen(
    gateway: EmbyGateway,
    identity: MediaIdentity,
    playback: PlaybackCoordinator,
    isHeightConstrained: Boolean,
    modifier: Modifier = Modifier,
    authenticationReturnDestination: String? = null,
    initialSeasonIdentity: MediaIdentity? = null,
) = SeriesDetailsPane(
    gateway,
    identity,
    playback,
    isHeightConstrained,
    modifier,
    authenticationReturnDestination,
    initialSeasonIdentity,
)

@Composable
private fun SeriesDetailsPane(
    gateway: EmbyGateway,
    identity: MediaIdentity,
    playback: PlaybackCoordinator,
    isHeightConstrained: Boolean,
    modifier: Modifier,
    authenticationReturnDestination: String?,
    initialSeasonIdentity: MediaIdentity? = null,
) {
    var retry by rememberSaveable(identity.serverId, identity.itemId) { androidx.compose.runtime.mutableIntStateOf(0) }
    var detailState by androidx.compose.runtime.remember(identity) { androidx.compose.runtime.mutableStateOf<SeriesDetailsState?>(null) }
    var selectedSeasonId by rememberSaveable(identity.serverId, identity.itemId) {
        androidx.compose.runtime.mutableStateOf(initialSeasonIdentity?.itemId)
    }
    var selectedEpisodeId by rememberSaveable(identity.serverId, identity.itemId) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var episodesState by androidx.compose.runtime.remember(identity, selectedSeasonId) { androidx.compose.runtime.mutableStateOf<SeasonEpisodesState?>(null) }
    LaunchedEffect(gateway, identity, retry) { detailState = gateway.loadSeriesDetails(identity, authenticationReturnDestination) }
    val details = (detailState as? SeriesDetailsState.Ready)?.details
    val selectedSeason = details?.seasons?.firstOrNull { it.identity.itemId == selectedSeasonId }
    LaunchedEffect(gateway, identity, selectedSeason) {
        episodesState = selectedSeason?.let { gateway.loadSeasonEpisodes(identity, it.identity, authenticationReturnDestination) }
    }
    when (val snapshot = detailState) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is SeriesDetailsState.Failure -> MessageState("Series unavailable", snapshot.message, "Retry") { retry += 1 }
        is SeriesDetailsState.Ready -> {
            val episodeIdentity = selectedEpisodeId?.let { MediaIdentity(identity.serverId, it) }
            if (episodeIdentity != null) EpisodeDetailsPane(
                gateway, episodeIdentity, playback, modifier, authenticationReturnDestination,
                onBack = { selectedEpisodeId = null },
            ) else LazyColumn(
                modifier.fillMaxSize().testTag("series-details").padding(if (isHeightConstrained) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    (snapshot.details.backdrop ?: snapshot.details.artwork)?.let { artwork ->
                        AuthenticatedArtwork(
                            artwork, "Artwork for ${snapshot.details.title}", { gateway.loadArtwork(artwork) },
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        )
                    }
                    Text(snapshot.details.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    listOfNotNull(snapshot.details.year?.toString(), snapshot.details.genres.takeIf { it.isNotEmpty() }?.joinToString(" • "))
                        .takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("  •  ")) }
                    snapshot.details.overview?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
                if (snapshot.details.seasons.isEmpty()) item {
                    Text("No seasons available", style = MaterialTheme.typography.titleMedium)
                    Text("This show does not have season information from the server.")
                } else items(snapshot.details.seasons, key = { it.identity.itemId }) { season ->
                    FilterChip(
                        selected = season.identity.itemId == selectedSeasonId,
                        onClick = { selectedSeasonId = season.identity.itemId; selectedEpisodeId = null },
                        label = { Text(season.name) },
                    )
                }
                when (val episodeSnapshot = episodesState) {
                    null -> if (selectedSeason != null) item { CircularProgressIndicator() }
                    is SeasonEpisodesState.Failure -> item { Text(episodeSnapshot.message) }
                    is SeasonEpisodesState.Empty -> item {
                        Text("No episodes in ${selectedSeason?.name ?: "this season"}", style = MaterialTheme.typography.titleMedium)
                    }
                    is SeasonEpisodesState.Ready -> itemsIndexed(
                        episodeSnapshot.episodes,
                        key = { _, episode -> episode.identity.itemId },
                    ) { index, episode ->
                        EpisodeRow(gateway, episode, index + 1) { selectedEpisodeId = episode.identity.itemId }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(gateway: EmbyGateway, episode: EpisodeSummary, traversalOrder: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 8.dp)
            .semantics { traversalIndex = traversalOrder.toFloat() },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        episode.artwork?.let { artwork ->
            AuthenticatedArtwork(artwork, "Still for ${episode.title}", { gateway.loadArtwork(artwork) }, Modifier.width(120.dp).aspectRatio(16f / 9f))
        }
        Column(Modifier.weight(1f)) {
            Text(episodeLabel(episode), style = MaterialTheme.typography.titleMedium)
            episode.runtimeTicks?.let { Text(formatRuntime(it), style = MaterialTheme.typography.bodySmall) }
            episode.overview?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            val runtimeTicks = episode.runtimeTicks
            if (episode.playbackPositionTicks > 0 && runtimeTicks != null) {
                val progress = (episode.playbackPositionTicks.toFloat() / runtimeTicks).coerceIn(0f, 1f)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().semantics {
                        progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(progress, 0f..1f)
                    },
                )
            }
        }
    }
}

@Composable
fun EpisodeDetailsScreen(
    gateway: EmbyGateway,
    identity: MediaIdentity,
    playback: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    authenticationReturnDestination: String? = null,
) = EpisodeDetailsPane(
    gateway,
    identity,
    playback,
    modifier,
    authenticationReturnDestination,
    onBack = {},
    showBack = false,
)

@Composable
private fun EpisodeDetailsPane(
    gateway: EmbyGateway,
    identity: MediaIdentity,
    playback: PlaybackCoordinator,
    modifier: Modifier,
    authenticationReturnDestination: String?,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    var retry by rememberSaveable(identity.serverId, identity.itemId) { androidx.compose.runtime.mutableIntStateOf(0) }
    var state by androidx.compose.runtime.remember(identity) { androidx.compose.runtime.mutableStateOf<EpisodeDetailsState?>(null) }
    LaunchedEffect(gateway, identity, retry) { state = gateway.loadEpisodeDetails(identity, authenticationReturnDestination) }
    when (val snapshot = state) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is EpisodeDetailsState.Failure -> MessageState("Episode unavailable", snapshot.message, "Retry") { retry += 1 }
        is EpisodeDetailsState.Ready -> EpisodeDetailsContent(gateway, snapshot.details, playback, modifier, onBack, showBack)
    }
}

@Composable
private fun EpisodeDetailsContent(
    gateway: EmbyGateway,
    details: EpisodeDetails,
    playback: PlaybackCoordinator,
    modifier: Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val episode = details.episode
    Column(
        modifier.fillMaxSize().testTag("episode-details").verticalScroll(rememberScrollState()).padding(20.dp)
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showBack) OutlinedButton(onClick = onBack) { Text("Back to episodes") }
        (details.backdrop ?: episode.artwork)?.let { artwork ->
            AuthenticatedArtwork(
                artwork, "Artwork for ${episode.title}", { gateway.loadArtwork(artwork) },
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
        episode.seriesName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(episodeLabel(episode), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        listOfNotNull(
            episode.runtimeTicks?.let(::formatRuntime),
            details.communityRating?.let { "★ $it" },
            details.criticRating?.let { "Critics $it" },
        ).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("  •  ")) }
        episode.overview?.takeIf { it.isNotBlank() }?.let { Text(it) }
        if (details.genres.isNotEmpty()) Text(details.genres.joinToString(" • "))
        if (details.tracks.audioTracks > 0) Text("${details.tracks.audioTracks} audio track${if (details.tracks.audioTracks == 1) "" else "s"}")
        if (details.tracks.subtitleTracks > 0) Text("${details.tracks.subtitleTracks} subtitle track${if (details.tracks.subtitleTracks == 1) "" else "s"}")
        if (episode.hasMeaningfulResume) {
            Button({ playback.submit(MediaPlaybackRequest.Resume(episode.identity, episode.playbackPositionTicks, details.scope, episode.title)) }) {
                Text("Resume from ${formatPosition(episode.playbackPositionTicks)}")
            }
            OutlinedButton({ playback.submit(MediaPlaybackRequest.PlayFromBeginning(episode.identity, details.scope, episode.title)) }) { Text("Play from beginning") }
        } else Button({ playback.submit(MediaPlaybackRequest.PlayFromBeginning(episode.identity, details.scope, episode.title)) }) { Text("Play") }
    }
}

private fun episodeLabel(episode: EpisodeSummary): String {
    val number = listOfNotNull(episode.seasonNumber?.let { "S$it" }, episode.episodeNumber?.let { "E$it" }).joinToString(" ")
    return listOf(number, episode.title).filter { it.isNotBlank() }.joinToString("  ")
}

private val HomeScopeSaver = Saver<HomeScope?, List<String>>(
    save = { scope -> scope?.let { listOf(it.serverId, it.userId) } ?: emptyList() },
    restore = { values -> values.takeIf { it.size == 2 }?.let { HomeScope(it[0], it[1]) } },
)

val MovieLibrarySelectionSaver = Saver<MediaIdentity?, List<String>>(
    save = { identity -> identity?.let { listOf(it.serverId, it.itemId) } ?: emptyList() },
    restore = { values -> values.takeIf { it.size == 2 }?.let { MediaIdentity(it[0], it[1]) } },
)

@Composable
private fun MessageState(title: String, description: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(description, Modifier.padding(vertical = 12.dp))
        Button(onClick = onClick) { Text(action) }
    }
}

private fun formatRuntime(ticks: Long): String = "${ticks / 10_000_000L / 60} min"
private fun formatPosition(ticks: Long): String {
    val seconds = ticks / 10_000_000L
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
