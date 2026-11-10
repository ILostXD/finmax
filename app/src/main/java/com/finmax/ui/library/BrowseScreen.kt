package com.finmax.ui.library

import com.finmax.ui.components.Ic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.finmax.FinmaxApp
import com.finmax.data.FilterChoice
import com.finmax.data.MediaRepository
import com.finmax.data.SortChoice
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.components.CardStyle
import com.finmax.ui.components.MediaCard
import com.finmax.ui.components.MessagePanel
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.SkeletonRow
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.components.playKey
import com.finmax.ui.components.popoverBlur
import com.finmax.ui.entryState
import com.finmax.ui.home.open
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Paged, sortable, filterable grid over any part of the library. */
class BrowseState(
    private val repo: MediaRepository,
    val parentId: UUID?,
    val types: List<BaseItemKind>?,
    defaultFilter: FilterChoice = FilterChoice.ALL,
    genre: String? = null,
) : ScreenState() {
    val items = mutableStateListOf<BaseItemDto>()
    var total by mutableIntStateOf(-1)
    var sort by mutableStateOf(SortChoice.NAME)
    var filter by mutableStateOf(defaultFilter)
    var genre by mutableStateOf(genre)
    var genres by mutableStateOf<List<String>>(emptyList())
    var error by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    private var job: Job? = null

    init {
        reload()
        scope.launch { genres = runCatching { repo.genres(parentId).mapNotNull { it.name } }.getOrDefault(emptyList()) }
    }

    fun reload() {
        job?.cancel()
        items.clear()
        total = -1
        error = null
        scope.launch { runCatching { grid.scrollToItem(0) } }
        loadMore()
    }

    fun loadMore() {
        if (job?.isActive == true || (total >= 0 && items.size >= total)) return
        job = scope.launch {
            runCatching {
                repo.items(parentId = parentId, types = types, sort = sort, filter = filter, genre = genre, start = items.size, limit = 60)
            }.onSuccess {
                items += it.items
                total = it.totalRecordCount
            }.onFailure { error = friendlyError(it) }
        }
    }
}

object BrowseScreen {
    @Composable
    fun Movies() = Browse(Route.Movies, "Movies") { BrowseState(it, null, listOf(BaseItemKind.MOVIE)) }

    @Composable
    fun Shows() = Browse(Route.Shows, "TV Shows") { BrowseState(it, null, listOf(BaseItemKind.SERIES)) }

    @Composable
    fun MyList() = Browse(Route.MyList, "My List", emptyText = "Add movies and shows with the + button and they'll appear here.") {
        BrowseState(it, null, listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.BOX_SET), FilterChoice.FAVORITES)
    }

    @Composable
    fun Library(r: Route.Library) = Browse(null, r.name) {
        val types = when (r.type) {
            CollectionType.MOVIES -> listOf(BaseItemKind.MOVIE)
            CollectionType.TVSHOWS -> listOf(BaseItemKind.SERIES)
            CollectionType.BOXSETS -> listOf(BaseItemKind.BOX_SET)
            else -> listOf(BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.SERIES)
        }
        BrowseState(it, r.id, types)
    }

    @Composable
    fun Genre(r: Route.Genre) = Browse(null, r.name) {
        BrowseState(it, r.parentId, listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), genre = r.name)
    }
}

@Composable
private fun Browse(tab: Route.Tab?, title: String, emptyText: String = "Nothing matches these filters.", create: (MediaRepository) -> BrowseState) {
    val repo = FinmaxApp.instance.repo
    val state = entryState { create(repo) }
    val nav = LocalNav.current
    var pickingGenre by androidx.compose.runtime.remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().popoverBlur(pickingGenre)) {
        if (tab != null) TopBar(tab)
        LazyVerticalGrid(
            state = state.grid,
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(start = Fm.SafeH, end = Fm.SafeH, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
            verticalArrangement = Arrangement.spacedBy(Fm.CardGap + 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = if (tab == null) Fm.SafeV else 8.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                        Text(state.genre?.takeIf { tab != null } ?: title, style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.width(14.dp))
                        if (state.total >= 0) Text("${state.total} titles", color = Fm.TextFaint, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Sort: ${state.sort.label}", {
                            state.sort = SortChoice.entries[(state.sort.ordinal + 1) % SortChoice.entries.size]
                            state.reload()
                        }, icon = Ic.Sort, modifier = Modifier.focusMemory("sort", initial = true))
                        PillButton("Show: ${state.filter.label}", {
                            state.filter = FilterChoice.entries[(state.filter.ordinal + 1) % FilterChoice.entries.size]
                            state.reload()
                        }, icon = Ic.Filter)
                        if (state.genres.isNotEmpty()) {
                            PillButton("Genre: ${state.genre ?: "All"}", { pickingGenre = true }, icon = Ic.Category)
                        }
                    }
                }
            }
            itemsIndexed(state.items, key = { _, it -> it.id }) { i, item ->
                if (i >= state.items.size - 18) androidx.compose.runtime.LaunchedEffect(state.items.size) { state.loadMore() }
                MediaCard(
                    item, CardStyle.POSTER, onClick = { nav.open(item) },
                    modifier = Modifier.focusMemory("g/${item.id}").playKey(item, nav),
                )
            }
            when {
                state.error != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    MessagePanel("Couldn't load this", state.error!!, actionLabel = "Try again", onAction = { state.reload() })
                }
                state.total < 0 -> item(span = { GridItemSpan(maxLineSpan) }) { SkeletonRow(CardStyle.POSTER, 6) }
                state.total == 0 -> item(span = { GridItemSpan(maxLineSpan) }) { MessagePanel("Nothing here", emptyText) }
            }
        }
      }
        if (pickingGenre) {
            BackHandler { pickingGenre = false }
            SidePanel("Genre") {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(listOf<String?>(null) + state.genres) { g ->
                        com.finmax.ui.components.PopoverItem(g ?: "All genres", {
                            state.genre = g
                            pickingGenre = false
                            state.reload()
                        }, primary = g == state.genre, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Right-hand picker; see [com.finmax.ui.components.Popover] for the look. */
@Composable
fun SidePanel(title: String, width: androidx.compose.ui.unit.Dp = 360.dp, content: @Composable () -> Unit) =
    com.finmax.ui.components.Popover(title, width, content)
