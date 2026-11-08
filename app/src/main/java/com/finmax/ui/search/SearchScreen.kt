package com.finmax.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.finmax.FinmaxApp
import com.finmax.data.MediaRepository
import com.finmax.data.SortChoice
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.components.CardStyle
import com.finmax.ui.components.MediaCard
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.components.playKey
import com.finmax.ui.entryState
import com.finmax.ui.home.open
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

private class SearchState(private val repo: MediaRepository) : ScreenState() {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<BaseItemDto>>(emptyList())
    var suggestions by mutableStateOf<List<BaseItemDto>>(emptyList())
    var message by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    private var job: Job? = null
    private val types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.BOX_SET)

    init {
        scope.launch {
            suggestions = runCatching {
                repo.items(types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), sort = SortChoice.RANDOM, limit = 24).items
            }.getOrDefault(emptyList())
        }
    }

    fun set(q: String) {
        query = q
        job?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            message = null
            return
        }
        job = scope.launch {
            delay(350) // debounce typing
            runCatching { repo.items(types = types, search = q.trim(), sort = SortChoice.NAME, limit = 60).items }
                .onSuccess {
                    // Movies/shows first, then collections, then individual episodes.
                    results = it.sortedBy { i -> types.indexOf(i.type) }
                    message = if (it.isEmpty()) "No matches for \"$q\"." else null
                }
                .onFailure { message = friendlyError(it) }
        }
    }
}

private val keys = ("abcdefghijklmnopqrstuvwxyz1234567890").map { it.toString() }

@Composable
fun SearchScreen() {
    val state = entryState { SearchState(FinmaxApp.instance.repo) }
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        TopBar(Route.Search)
        Row(Modifier.fillMaxSize().padding(start = Fm.SafeH, top = 8.dp)) {
            Column(Modifier.width(276.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(48.dp).background(Fm.Surface, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        state.query.ifEmpty { "Search titles" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.query.isEmpty()) Fm.TextFaint else Fm.Text,
                        maxLines = 1,
                    )
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key(Modifier.width(135.dp), "space", icon = Icons.Filled.SpaceBar) { state.set(state.query + " ") }
                    Key(Modifier.width(135.dp), "delete", icon = Icons.AutoMirrored.Filled.Backspace) { state.set(state.query.dropLast(1)) }
                }
                keys.chunked(6).forEachIndexed { r, row ->
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            Key(Modifier.size(40.dp).focusMemory("k/$k", initial = r == 0 && k == "a"), k) { state.set(state.query + k) }
                        }
                    }
                }
                if (state.query.isNotEmpty()) {
                    Key(Modifier.padding(top = 10.dp).width(276.dp), "Clear") { state.set("") }
                }
            }
            val showing = if (state.query.isBlank()) state.suggestions else state.results
            LazyVerticalGrid(
                state = state.grid,
                columns = GridCells.Adaptive(132.dp),
                contentPadding = PaddingValues(start = 32.dp, end = Fm.SafeH, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        state.message ?: if (state.query.isBlank()) "Explore something new" else "Results",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (state.message != null) Fm.TextDim else Fm.Text,
                    )
                }
                items(showing, key = { it.id }) { item ->
                    MediaCard(
                        item, CardStyle.POSTER, onClick = { nav.open(item) }, width = 132.dp,
                        modifier = Modifier.focusMemory("r/${item.id}").playKey(item, nav),
                    )
                }
            }
        }
    }
}

@Composable
private fun Key(modifier: Modifier, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Fm.Surface, contentColor = Fm.TextDim,
            focusedContainerColor = Fm.Gold, focusedContentColor = Fm.Bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, label, Modifier.size(20.dp)) else Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
