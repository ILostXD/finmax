package com.finmax.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.data.SeerrItem
import com.finmax.ui.Route
import com.finmax.ui.components.FocusTile
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.popoverBlur
import com.finmax.ui.components.Overline
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.SectionTitle
import com.finmax.ui.components.SkeletonRow
import com.finmax.ui.components.CardStyle
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.entryState
import com.finmax.ui.library.SidePanel
import com.finmax.ui.login.FmTextField
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.launch

private data class DiscoverRow(val key: String, val title: String, val items: List<SeerrItem>)

private class DiscoverState : ScreenState() {
    val seerr = FinmaxApp.instance.seerr
    val rows = mutableStateListOf<DiscoverRow>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var focused by mutableStateOf<SeerrItem?>(null)
    var selected by mutableStateOf<SeerrItem?>(null)

    init { if (seerr.isConfigured) refresh() }

    fun refresh() {
        rows.clear()
        error = null
        loading = true
        scope.launch {
            val sources = listOf(
                Triple("trending", "Trending", suspend { seerr.trending() }),
                Triple("movies", "Popular movies", suspend { seerr.popularMovies() }),
                Triple("tv", "Popular shows", suspend { seerr.popularShows() }),
                Triple("umovies", "Coming soon to cinemas", suspend { seerr.upcomingMovies() }),
                Triple("utv", "Upcoming shows", suspend { seerr.upcomingShows() }),
            )
            for ((key, title, load) in sources) {
                runCatching { load() }
                    .onSuccess { if (it.isNotEmpty()) rows += DiscoverRow(key, title, it) }
                    .onFailure { if (rows.isEmpty()) error = friendlyError(it) }
            }
            loading = false
        }
    }
}

/**
 * Discover (work in progress): browse what's trending via Seerr (Jellyseerr/Overseerr) and request
 * titles that aren't in the library yet, like Wholphin's Seerr integration.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DiscoverScreen() {
    val state = entryState { DiscoverState() }
    var configured by remember { mutableStateOf(state.seerr.isConfigured) }
    val bg = state.focused

    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().popoverBlur(state.selected != null, 440.dp)) {
        Crossfade(bg?.tmdbId, animationSpec = tween(500), label = "bg") {
            val item = bg?.takeIf { b -> b.tmdbId == it }
            GlassBackdrop(item?.backdrop, soft = item?.backdropSmall)
        }
        Column(Modifier.fillMaxSize()) {
            TopBar(Route.Discover)
            if (!configured) {
                SetupSeerr { configured = true; state.refresh() }
                return@Column
            }
            FocusedInfo(bg, Modifier.padding(start = Fm.SafeH, top = 8.dp).height(150.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.fillMaxSize()) {
                if (state.loading && state.rows.isEmpty()) item { SkeletonRow(CardStyle.POSTER, 7) }
                state.error?.let { e -> item { Text(e, color = Fm.Error, modifier = Modifier.padding(start = Fm.SafeH, top = 20.dp)) } }
                items(state.rows, key = { it.key }) { row ->
                    Column {
                        SectionTitle(row.title)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(row.items, key = { "${row.key}/${it.mediaType}/${it.tmdbId}" }) { item ->
                                FocusTile(
                                    onClick = { state.selected = item },
                                    onFocused = { state.focused = item },
                                    modifier = Modifier.width(128.dp).aspectRatio(2f / 3f)
                                        .focusMemory("${row.key}/${item.tmdbId}", initial = row == state.rows.first() && item == row.items.first()),
                                ) {
                                    item.poster?.let { AsyncImage(it, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                    item.statusLabel?.let { StatusPill(it, Modifier.align(Alignment.TopEnd).padding(6.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
      }
        state.selected?.let { item ->
            BackHandler { state.selected = null }
            RequestPanel(item, state.seerr, onClose = { state.selected = null })
        }
    }
}

@Composable
private fun FocusedInfo(item: SeerrItem?, modifier: Modifier) {
    Column(modifier.width(560.dp), verticalArrangement = Arrangement.Bottom) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Overline("Discover")
            StatusPill("Beta")
        }
        if (item == null) {
            Text("Find something new", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 8.dp))
            Text("Trending titles from Seerr. Request anything that isn't in your library yet.", color = Fm.TextDim, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(item.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(
            listOfNotNull(if (item.mediaType == "movie") "Movie" else "Series", item.year, item.rating?.let { "★ %.1f".format(it) }, item.statusLabel).joinToString("  ·  "),
            style = MaterialTheme.typography.labelMedium, color = Fm.TextDim, modifier = Modifier.padding(top = 4.dp),
        )
        Text(item.overview, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = Fm.Text,
        modifier = modifier.background(Fm.Amethyst, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun RequestPanel(item: SeerrItem, seerr: com.finmax.data.SeerrClient, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember(item) { mutableStateOf<String?>(null) }
    var done by remember(item) { mutableStateOf(false) }
    SidePanel(item.title, width = 440.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item.backdrop?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Fm.Surface, com.finmax.ui.components.CardShape)) }
            Text(item.overview, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 6, overflow = TextOverflow.Ellipsis)
            when {
                done -> Text("Requested. Seerr will add it to your library once it's approved and downloaded.", color = Fm.Success)
                !item.requestable -> Text("${item.statusLabel}: nothing to request.", color = Fm.TextDim)
                else -> PillButton(
                    if (item.mediaType == "tv") "Request all seasons" else "Request",
                    {
                        status = "Sending request…"
                        scope.launch {
                            runCatching { seerr.request(item) }
                                .onSuccess { done = true; status = null }
                                .onFailure { status = friendlyError(it) }
                        }
                    },
                    icon = Icons.Filled.Add, primary = true, modifier = Modifier.focusMemory("request/${item.tmdbId}", initial = true),
                )
            }
            status?.let { Text(it, color = if (it.startsWith("Sending")) Fm.TextDim else Fm.Error) }
            Spacer(Modifier.height(4.dp))
            PillButton("Close", onClose, modifier = if (item.requestable && !done) Modifier else Modifier.focusMemory("request/close/${item.tmdbId}", initial = true))
        }
    }
}

@Composable
private fun SetupSeerr(onConnected: () -> Unit) {
    val app = FinmaxApp.instance
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(app.settings.current.seerrUrl) }
    var key by remember { mutableStateOf(app.settings.seerrApiKey.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(start = Fm.SafeH, top = 40.dp).width(640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Overline("Discover")
            StatusPill("Beta")
        }
        Text("Connect Seerr", style = MaterialTheme.typography.displaySmall)
        Text(
            "Discover uses Jellyseerr or Overseerr to show what's trending and let you request titles. " +
                "Find the API key in Seerr → Settings → General.",
            style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
        )
        FmTextField(url, { url = it }, "Seerr address (e.g. http://192.168.1.20:5055)", Modifier.width(460.dp), requestFocus = true)
        FmTextField(key, { key = it }, "API key", Modifier.width(460.dp), password = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Connect", {
                app.settings.update { it.copy(seerrUrl = url.trim()) }
                app.settings.seerrApiKey = key.trim()
                status = "Connecting…"
                scope.launch {
                    runCatching { app.seerr.version() }
                        .onSuccess { onConnected() }
                        .onFailure { status = "Failed: ${friendlyError(it)}" }
                }
            }, primary = true)
            status?.let { Text(it, color = if (it.startsWith("Failed")) Fm.Error else Fm.TextDim) }
        }
    }
}
