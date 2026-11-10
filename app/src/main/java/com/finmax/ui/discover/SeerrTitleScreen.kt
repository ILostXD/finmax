package com.finmax.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.data.SeerrDetails
import com.finmax.data.SeerrItem
import com.finmax.data.SeerrPerson
import com.finmax.data.seerrStatusLabel
import com.finmax.data.withDashes
import com.finmax.ui.LocalNav
import com.finmax.ui.components.Badge
import com.finmax.ui.components.FocusTile
import com.finmax.ui.components.GhostButton
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.Ic
import com.finmax.ui.components.MetaLine
import com.finmax.ui.components.NoAutoScroll
import com.finmax.ui.components.Overline
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.PopoverItem
import com.finmax.ui.components.SectionTitle
import com.finmax.ui.components.Wordmark
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.components.popoverBlur
import com.finmax.ui.home.BigTitle
import com.finmax.ui.library.SidePanel
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.launch

/**
 * A Discover title's page, laid out like a movie's details page: the art full screen with the
 * facts, then Cast and More like this. Requesting always goes through a confirm panel (with a
 * season picker for shows), so a stray OK never asks Seerr for anything.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SeerrTitleScreen(item: SeerrItem, requestNow: Boolean = false) {
    val seerr = FinmaxApp.instance.seerr
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<SeerrDetails?>(null) }
    val similar = remember { mutableStateListOf<SeerrItem>() }
    var asking by remember { mutableStateOf(requestNow) }
    var done by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.key) {
        launch {
            // Fresh status (the list may predate a request made elsewhere) and the page's extra facts.
            runCatching { seerr.details(item) }.onSuccess {
                details = it
                if (it.status > 1) SeerrRequests.status[item.key] = it.status
            }
        }
        launch { runCatching { seerr.similar(item) }.onSuccess { similar += it.take(20) } }
    }
    val status = SeerrRequests.of(item)
    val d = details
    val list = rememberLazyListState()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    fun Modifier.snap(index: Int) = onFocusChanged { if (it.hasFocus) scope.launch { list.animateScrollToItem(index) } }
    val libraryId = (d?.jellyfinId ?: item.jellyfinId)?.let { runCatching { java.util.UUID.fromString(it.withDashes()) }.getOrNull() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().popoverBlur(asking, 460.dp)) {
            GlassBackdrop(item.backdrop, soft = item.backdropSmall)
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
                LazyColumn(state = list, contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = "head") {
                        Column(
                            Modifier.height(screenHeight).fillMaxWidth().padding(start = Fm.SafeH, end = Fm.SafeH, top = Fm.SafeV, bottom = 34.dp).snap(0),
                        ) {
                            Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Wordmark(20.dp, Modifier.padding(bottom = com.finmax.ui.components.LogoLift))
                                Spacer(Modifier.width(26.dp))
                                Overline(
                                    listOfNotNull("Discover", if (item.isMovie) "Movies" else "Shows", d?.genres?.firstOrNull()).joinToString("   /   "),
                                    color = Fm.TextDim,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Column(Modifier.width(640.dp)) {
                                BigTitle(item.title, maxLines = 2)
                                Spacer(Modifier.height(14.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetaLine(
                                        listOfNotNull(
                                            item.rating?.let { "★ %.1f".format(it) },
                                            item.year,
                                            d?.runtimeMin?.takeIf { item.isMovie }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                                            d?.seasons?.size?.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
                                        ) + d?.genres.orEmpty().take(2),
                                        color = Fm.Text,
                                    )
                                    d?.certification?.let { Badge(it) }
                                    seerrStatusLabel(status)?.let { Badge(it) }
                                }
                                d?.tagline?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Fm.Text, modifier = Modifier.padding(top = 12.dp)) }
                                Text(
                                    item.overview, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
                                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).width(580.dp),
                                )
                                val facts = listOfNotNull(
                                    d?.director?.let { "Director" to it },
                                    d?.cast?.takeIf { it.isNotEmpty() }?.let { c -> "Starring" to c.take(3).joinToString(" · ") { it.name } },
                                )
                                if (facts.isNotEmpty()) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                    facts.forEach { (label, value) ->
                                        Column(Modifier.widthIn(max = 380.dp)) {
                                            Overline(label)
                                            Text(value, style = MaterialTheme.typography.bodySmall, color = Fm.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(22.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                val first = Modifier.focusMemory("primary", initial = true)
                                when {
                                    libraryId != null && status >= 4 -> {
                                        PillButton("Watch now", { nav.push(com.finmax.ui.Route.Detail(libraryId)) }, icon = Ic.Play, primary = true, modifier = first)
                                        // A show that's only partly here can still ask for the missing seasons.
                                        if (status == 4 && !item.isMovie) GhostButton("Request more", { asking = true }, icon = Ic.Add)
                                    }
                                    status <= 1 -> PillButton(if (item.isMovie) "Request" else "Request…", { asking = true }, icon = Ic.Add, primary = true, modifier = first)
                                    else -> {
                                        PillButton(seerrStatusLabel(status) ?: "", {}, icon = if (status >= 4) Ic.CheckCircle else Ic.Clock, modifier = first)
                                        if (status == 4 && !item.isMovie) GhostButton("Request more", { asking = true }, icon = Ic.Add)
                                    }
                                }
                                val note = done ?: when (status) {
                                    2 -> "Waiting for approval in Seerr."
                                    3 -> "Approved and downloading. It appears in your library when it's done."
                                    else -> null
                                }
                                note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim) }
                            }
                        }
                    }
                    if (!d?.cast.isNullOrEmpty()) item(key = "cast") {
                        Column(Modifier.snap(1)) {
                            SectionTitle("Cast")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
                                modifier = Modifier.focusRestorer(),
                            ) { items(d!!.cast, key = { it.name + it.role }) { PersonCard(it) } }
                        }
                    }
                    if (similar.isNotEmpty()) item(key = "similar") {
                        Column(Modifier.snap(if (d?.cast.isNullOrEmpty()) 1 else 2)) {
                            SectionTitle("More like this")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
                                modifier = Modifier.focusRestorer(),
                            ) {
                                items(similar, key = { it.key }) { s ->
                                    SeerrCard(s, Modifier.width(128.dp).aspectRatio(2f / 3f).focusMemory("sim/${s.key}"), onFocus = {}) { nav.openSeerr(s) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (asking) {
            BackHandler { asking = false }
            RequestPanel(item, d, onCancel = { asking = false }) { message ->
                asking = false
                SeerrRequests.status[item.key] = 2
                done = message
            }
        }
    }
}

@Composable
private fun PersonCard(p: SeerrPerson) {
    Column(Modifier.width(108.dp)) {
        FocusTile(onClick = {}, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            p.photo?.let { AsyncImage(it, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Text(p.name.take(1), style = MaterialTheme.typography.displaySmall, color = Fm.TextFaint, modifier = Modifier.align(Alignment.Center))
        }
        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        p.role?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Fm.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * Confirms a request. Movies: one button. Shows: every season to tick (those already requested or
 * in the library are shown, not offered), with All seasons on top.
 */
@Composable
private fun RequestPanel(item: SeerrItem, d: SeerrDetails?, onCancel: () -> Unit, onDone: (String) -> Unit) {
    val seerr = FinmaxApp.instance.seerr
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val open = d?.seasons.orEmpty().filter { it.status <= 1 }
    val picked = remember(d) { mutableStateListOf<Int>().apply { addAll(open.map { it.number }) } }
    fun send() {
        if (sending) return
        sending = true
        error = null
        scope.launch {
            val all = !item.isMovie && picked.size == open.size && open.size == d?.seasons?.size
            runCatching { seerr.request(item, if (item.isMovie || all) null else picked.sorted()) }
                .onSuccess {
                    onDone(
                        when {
                            item.isMovie -> "Requested. You'll find it in your library once it's downloaded."
                            picked.size == 1 -> "Season ${picked.first()} requested."
                            else -> "${picked.size} seasons requested."
                        },
                    )
                }
                .onFailure { error = friendlyError(it) }
            sending = false
        }
    }
    SidePanel(if (item.isMovie) "Request movie" else "Request seasons", width = 460.dp) {
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Box(Modifier.width(64.dp).aspectRatio(2f / 3f).background(Fm.Surface, com.finmax.ui.components.CardShape)) {
                        item.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Column {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(item.year, if (item.isMovie) "Movie" else "Series").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Fm.TextDim)
                    }
                }
            }
            if (!item.isMovie) {
                if (d == null) item { Text("Loading seasons…", style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim) }
                else {
                    item {
                        val allOn = picked.size == open.size && open.isNotEmpty()
                        PopoverItem(
                            "All seasons", { if (allOn) picked.clear() else { picked.clear(); picked.addAll(open.map { it.number }) } },
                            icon = if (allOn) Ic.CheckCircle else Ic.Square, modifier = Modifier.fillMaxWidth().focusMemory("req/all", initial = true),
                        )
                    }
                    items(d.seasons, key = { it.number }) { s ->
                        val taken = s.status > 1
                        val on = s.number in picked
                        PopoverItem(
                            listOfNotNull(s.name.ifBlank { "Season ${s.number}" }, "${s.episodes} episodes".takeIf { s.episodes > 0 }, seerrStatusLabel(s.status)).joinToString("  ·  "),
                            { if (on) picked.remove(s.number) else picked.add(s.number) },
                            icon = when { taken -> Ic.Check; on -> Ic.CheckCircle; else -> Ic.Square },
                            enabled = !taken, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val label = when {
                        sending -> "Requesting…"
                        item.isMovie -> "Request"
                        picked.isEmpty() -> "Pick seasons"
                        picked.size == open.size -> "Request all"
                        else -> "Request ${picked.size} season${if (picked.size == 1) "" else "s"}"
                    }
                    PillButton(
                        label, { if (item.isMovie || picked.isNotEmpty()) send() }, icon = Ic.Add, primary = true,
                        enabled = item.isMovie || picked.isNotEmpty(),
                        modifier = if (item.isMovie) Modifier.focusMemory("req/send", initial = true) else Modifier,
                    )
                    GhostButton("Cancel", onCancel)
                }
            }
            error?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyMedium, color = Fm.Error) } }
        }
    }
}
