package com.finmax.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.finmax.FinmaxApp
import com.finmax.data.SeerrGenre
import com.finmax.data.SeerrItem
import com.finmax.data.SeerrPage
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.components.holdUpStaysInside
import com.finmax.ui.entryState
import com.finmax.ui.home.BigTitle
import com.finmax.ui.search.SearchKeyboard
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A paged Seerr list for a grid: [more] fetches the next page; a new [reset] drops the old one. */
private class PagedGrid : ScreenState() {
    val items = mutableStateListOf<SeerrItem>()
    val grid = LazyGridState()
    var message by mutableStateOf<String?>(null)
    var focused by mutableStateOf<SeerrItem?>(null)
    private var load: (suspend (Int) -> SeerrPage)? = null
    private var page = 0
    private var hasMore = false
    private var job: Job? = null

    fun reset(newLoad: (suspend (Int) -> SeerrPage)?, debounceMs: Long = 0, emptyText: String = "Nothing here yet.") {
        job?.cancel()
        load = newLoad
        page = 0
        hasMore = newLoad != null
        items.clear()
        message = null
        if (newLoad == null) return
        job = scope.launch {
            delay(debounceMs)
            fetch()
            if (items.isEmpty() && message == null) message = emptyText
            runCatching { grid.scrollToItem(0) }
        }
    }

    fun more() {
        if (job?.isActive == true || !hasMore) return
        job = scope.launch { fetch() }
    }

    private suspend fun fetch() {
        val l = load ?: return
        runCatching { l(page + 1) }
            .onSuccess { p ->
                page++
                hasMore = p.hasMore
                val seen = items.map { it.key }.toHashSet()
                items += p.items.filter { seen.add(it.key) }
            }
            .onFailure { hasMore = false; message = friendlyError(it) }
    }
}

/** Seerr search: the Search page's keyboard on the left, posters on the right; trending until you type. */
@Composable
fun SeerrSearchScreen() {
    val seerr = FinmaxApp.instance.seerr
    val state = entryState("seerrSearch") { PagedGrid().apply { reset({ seerr.trending(it) }) } }
    val q = entryState("seerrQuery") { QueryHolder() }
    Box(Modifier.fillMaxSize()) {
        Backdrop(state.focused)
        Column(Modifier.fillMaxSize()) {
            TopBar(Route.Discover)
            Row(Modifier.fillMaxSize().padding(start = Fm.SafeH, top = 8.dp).holdUpStaysInside()) {
                SearchKeyboard(q.text, { t ->
                    q.text = t
                    if (t.isBlank()) state.reset({ seerr.trending(it) })
                    else state.reset({ seerr.search(t.trim(), it) }, debounceMs = 400, emptyText = "Nothing on Seerr for \"$t\".")
                }, "Search movies & shows")
                Grid(state, if (q.text.isBlank()) "Trending now" else "Results", Modifier.weight(1f), takeFocus = false)
            }
        }
    }
}

private class QueryHolder { var text by mutableStateOf("") }

/** Every title in one genre, as a poster grid that keeps loading as you go down. */
@Composable
fun SeerrGenreScreen(genre: SeerrGenre) {
    val seerr = FinmaxApp.instance.seerr
    val state = entryState("seerrGenre/${genre.mediaType}/${genre.id}") { PagedGrid().apply { reset({ seerr.genre(genre, it) }) } }
    Box(Modifier.fillMaxSize()) {
        Backdrop(state.focused)
        Column(Modifier.fillMaxSize()) {
            TopBar(Route.Discover)
            Grid(
                state, "${genre.name} · ${if (genre.mediaType == "movie") "Movies" else "Shows"}",
                Modifier.padding(start = Fm.SafeH - 32.dp).holdUpStaysInside(),
                big = true,
            )
        }
    }
}

@Composable
private fun Backdrop(item: SeerrItem?) {
    androidx.compose.animation.Crossfade(item?.key, animationSpec = androidx.compose.animation.core.tween(450), label = "bg") { k ->
        val i = item?.takeIf { it.key == k }
        GlassBackdrop(i?.backdropMedium, soft = i?.backdropSmall, dim = 0.35f)
    }
}

@Composable
private fun Grid(state: PagedGrid, title: String, modifier: Modifier, big: Boolean = false, takeFocus: Boolean = true) {
    val nav = LocalNav.current
    LazyVerticalGrid(
        state = state.grid,
        columns = GridCells.Adaptive(132.dp),
        contentPadding = PaddingValues(start = 32.dp, end = Fm.SafeH, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier,
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 4.dp)) {
                if (big) BigTitle(title, maxLines = 1) else Text(title, style = MaterialTheme.typography.titleLarge)
                state.focused?.let { f ->
                    Text(listOfNotNull(f.title, f.year).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 1)
                }
            }
        }
        state.message?.let { m ->
            item(span = { GridItemSpan(maxLineSpan) }) { Text(m, style = MaterialTheme.typography.bodyLarge, color = Fm.TextDim) }
        }
        itemsIndexed(state.items, key = { _, it -> it.key }) { i, item ->
            SeerrCard(
                item, Modifier.aspectRatio(2f / 3f).focusMemory("g/${item.key}", initial = takeFocus && i == 0),
                onFocus = {
                    state.focused = item
                    if (i >= state.items.size - 12) state.more()
                },
            ) { nav.openSeerr(item) }
        }
    }
}
