package com.finmax.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.finmax.FinmaxApp
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.components.CardStyle
import com.finmax.ui.components.ErrorPanel
import com.finmax.ui.components.Load
import com.finmax.ui.components.MediaCard
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.SkeletonRow
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.loadState
import com.finmax.ui.entryState
import com.finmax.ui.home.open
import com.finmax.ui.theme.Fm
import org.jellyfin.sdk.model.api.BaseItemDto

private class LibrariesState : ScreenState() {
    val views = loadState<List<BaseItemDto>>()
    init { refresh() }
    fun refresh() = load(views) { FinmaxApp.instance.repo.views() }
}

@Composable
fun LibrariesScreen() {
    val state = entryState { LibrariesState() }
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        TopBar(Route.Libraries)
        Text("Libraries", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(start = Fm.SafeH, top = 8.dp))
        when (val v = state.views.value) {
            Load.Loading -> SkeletonRow()
            is Load.Failed -> ErrorPanel(v.message, { state.refresh() })
            is Load.Ok -> LazyVerticalGrid(
                columns = GridCells.Adaptive(248.dp),
                contentPadding = PaddingValues(Fm.SafeH, 20.dp),
                horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
                verticalArrangement = Arrangement.spacedBy(Fm.CardGap),
            ) {
                items(v.value, key = { it.id }) { view ->
                    MediaCard(
                        view, CardStyle.LANDSCAPE, onClick = { nav.open(view) },
                        modifier = Modifier.focusMemory("lib/${view.id}", initial = view == v.value.first()),
                    )
                }
            }
        }
    }
}
