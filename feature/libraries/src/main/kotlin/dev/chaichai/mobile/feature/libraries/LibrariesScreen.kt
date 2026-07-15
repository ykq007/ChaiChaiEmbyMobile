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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.MovieDetails
import dev.chaichai.mobile.core.contracts.MovieDetailsState
import dev.chaichai.mobile.core.contracts.MovieLibraryQuery
import dev.chaichai.mobile.core.contracts.MovieLibraryState
import dev.chaichai.mobile.core.contracts.MoviePlaybackRequest
import dev.chaichai.mobile.core.contracts.MoviePoster
import dev.chaichai.mobile.core.contracts.MovieSortField
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.SortDirection
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
) {
    val state by gateway.movieLibrary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val initialQuery = (gateway.movieLibrary.value as? MovieLibraryState.Ready)?.query ?: MovieLibraryQuery()
    var savedSort by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(initialQuery.sortField.ordinal) }
    var savedDirection by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(initialQuery.sortDirection.ordinal) }
    var savedGenre by rememberSaveable { androidx.compose.runtime.mutableStateOf(initialQuery.genre) }
    val savedQuery = MovieLibraryQuery(
        MovieSortField.entries[savedSort],
        SortDirection.entries[savedDirection],
        savedGenre,
    )
    var selected by rememberSaveable("movie-library-selection", stateSaver = MediaIdentitySaver) {
        androidx.compose.runtime.mutableStateOf(initialSelection)
    }
    LaunchedEffect(initialSelection) {
        if (initialSelection != null && selected != initialSelection) selected = initialSelection
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
    val requestQuery: (MovieLibraryQuery) -> Unit = { query ->
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
                Modifier.width(hingePanes.collectionWidth),
            )
            Spacer(Modifier.width(hingePanes.hingeWidth))
            selected?.let { identity ->
                MovieDetailsScreen(
                    gateway, identity, playback, LibraryWindowClass.Compact, isHeightConstrained,
                    Modifier.width(hingePanes.detailWidth).fillMaxHeight(),
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
            MovieCollection(gateway, state, windowClass, isHeightConstrained, open, requestQuery, Modifier.weight(0.56f))
            MovieDetailsScreen(
                gateway, selected!!, playback, LibraryWindowClass.Medium, isHeightConstrained,
                Modifier.weight(0.44f).fillMaxHeight(),
            )
        }
    } else {
        MovieCollection(gateway, state, windowClass, isHeightConstrained, open, requestQuery, modifier)
    }
}

@Composable
private fun MovieCollection(
    gateway: EmbyGateway,
    state: MovieLibraryState,
    windowClass: LibraryWindowClass,
    isHeightConstrained: Boolean,
    onOpenDetails: (MediaIdentity) -> Unit,
    onQuery: (MovieLibraryQuery) -> Unit,
    modifier: Modifier,
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
        ) { scope.launch { gateway.refreshMovies() } }
        is MovieLibraryState.EmptyFiltered -> Column(modifier.fillMaxSize()) {
            LibraryControls(state.query, state.availableGenres, onQuery)
            MessageState("No matching movies", "Try another genre or clear the filter.", "Clear filter") {
                onQuery(state.query.copy(genre = null))
            }
        }
        is MovieLibraryState.Ready -> ReadyMovieGrid(
            gateway, state, windowClass, isHeightConstrained, onOpenDetails, onQuery, modifier,
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
    onQuery: (MovieLibraryQuery) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val fontScale = density.fontScale
    LaunchedEffect(gridState, state.items.size, state.totalCount) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.items.lastIndex - 4 && state.items.size < state.totalCount && !state.isLoadingMore) {
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
    query: MovieLibraryQuery,
    genres: List<String>,
    onQuery: (MovieLibraryQuery) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LazyRow(Modifier.testTag("movie-sort-controls"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MovieSortField.entries) { sort ->
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
) {
    var retryAttempt by rememberSaveable(identity.serverId, identity.itemId) { androidx.compose.runtime.mutableIntStateOf(0) }
    var detailsState by androidx.compose.runtime.remember(identity.serverId, identity.itemId) {
        androidx.compose.runtime.mutableStateOf<MovieDetailsState?>(null)
    }
    LaunchedEffect(gateway, identity, retryAttempt) { detailsState = gateway.loadMovieDetails(identity) }
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
            if (details.hasMeaningfulResume) {
                Button(onClick = { playback.submit(MoviePlaybackRequest.Resume(details.identity, details.playbackPositionTicks)) }) {
                    Text("Resume from ${formatPosition(details.playbackPositionTicks)}")
                }
                OutlinedButton(onClick = { playback.submit(MoviePlaybackRequest.PlayFromBeginning(details.identity)) }) { Text("Play from beginning") }
            } else {
                Button(onClick = { playback.submit(MoviePlaybackRequest.PlayFromBeginning(details.identity)) }) { Text("Play") }
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

private val MediaIdentitySaver = Saver<MediaIdentity?, List<String>>(
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
