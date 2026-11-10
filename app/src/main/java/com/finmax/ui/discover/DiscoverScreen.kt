package com.finmax.ui.discover

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.finmax.FinmaxApp
import com.finmax.data.SeerrGenre
import com.finmax.data.SeerrItem
import com.finmax.data.SeerrPage
import com.finmax.data.seerrStatusLabel
import com.finmax.data.withDashes
import com.finmax.ui.LocalNav
import com.finmax.ui.Navigator
import com.finmax.ui.Route
import com.finmax.ui.components.FocusTile
import com.finmax.ui.components.GhostButton
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.Ic
import com.finmax.ui.components.IconButton
import com.finmax.ui.components.MessagePanel
import com.finmax.ui.components.MetaLine
import com.finmax.ui.components.NoAutoScroll
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
import com.finmax.ui.home.BigTitle
import com.finmax.ui.home.GenreTile
import com.finmax.ui.home.HeroDots
import com.finmax.ui.home.glideTo
import com.finmax.ui.home.horizontalSlide
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Requests made in this session, so cards show them before Seerr's lists catch up. */
object SeerrRequests {
    val status = mutableStateMapOf<String, Int>()
    fun of(item: SeerrItem) = status[item.key] ?: item.status
}

/** Opens a Discover title: one already in the library goes to its finmax page, the rest to the Seerr page. */
fun Navigator.openSeerr(item: SeerrItem, requestNow: Boolean = false) {
    val inLibrary = item.jellyfinId?.let { runCatching { java.util.UUID.fromString(it.withDashes()) }.getOrNull() }
    if (inLibrary != null && SeerrRequests.of(item) == 5 && !requestNow) push(Route.Detail(inLibrary)) else push(Route.SeerrTitle(item, requestNow))
}

/** One Discover row. Poster rows page in more titles as you near their end. */
internal class DiscoverRow(
    val key: String,
    val title: String,
    val order: Int,
    private val load: (suspend (Int) -> SeerrPage)? = null,
    val genres: List<SeerrGenre> = emptyList(),
) {
    val items = mutableStateListOf<SeerrItem>()
    val list = LazyListState()
    private var page = 0
    private var hasMore = true
    private var loading = false

    suspend fun more() {
        val l = load ?: return
        if (loading || !hasMore) return
        loading = true
        runCatching { l(page + 1) }.onSuccess { p ->
            page++
            hasMore = p.hasMore
            val seen = items.map { it.key }.toHashSet()
            items += p.items.filter { seen.add(it.key) }
        }.onFailure { hasMore = false }
        loading = false
    }
}

private class DiscoverState : ScreenState() {
    val seerr = FinmaxApp.instance.seerr
    val rows = mutableStateListOf<DiscoverRow>()
    var hero by mutableStateOf<List<SeerrItem>>(emptyList())
    var index by mutableIntStateOf(0)
    var direction by mutableIntStateOf(1)
    var inRows by mutableStateOf(false)
    var focusedRow by mutableIntStateOf(0)
    var focused by mutableStateOf<SeerrItem?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    val list = LazyListState()

    val slide get() = hero.takeIf { it.isNotEmpty() }?.let { it[index.mod(it.size)] }

    init { if (seerr.isConfigured) refresh() }

    fun step(by: Int) {
        if (hero.size < 2) return
        direction = by
        index += by
    }

    private fun put(row: DiscoverRow) {
        if (row.items.isEmpty() && row.genres.isEmpty()) return
        rows.removeAll { it.key == row.key }
        val at = rows.indexOfFirst { it.order > row.order }.let { if (it < 0) rows.size else it }
        rows.add(at, row)
    }

    fun refresh() {
        error = null
        loading = true
        scope.launch {
            // The carousel first: trending titles with artwork.
            val trending = DiscoverRow("trending", "Trending today", 1, { seerr.trending(it) })
            runCatching { seerr.version() }.onFailure { error = friendlyError(it) }
            trending.more()
            hero = trending.items.filter { it.backdropPath != null }.take(7)
            put(trending)
            loading = false
            fun poster(key: String, title: String, order: Int, load: suspend (Int) -> SeerrPage) = launch {
                put(DiscoverRow(key, title, order, load).apply { more() })
            }
            launch {
                val reqs = runCatching { seerr.requests() }.getOrDefault(emptyList())
                put(DiscoverRow("requests", "Your requests", 0).apply { items += reqs })
            }
            poster("pmovies", "Popular movies", 3) { seerr.popularMovies(it) }
            poster("umovies", "Coming soon to cinemas", 4) { seerr.upcomingMovies(it) }
            poster("pshows", "Popular shows", 5) { seerr.popularShows(it) }
            poster("ushows", "Upcoming shows", 7) { seerr.upcomingShows(it) }
            launch { runCatching { seerr.genres("movie") }.onSuccess { put(DiscoverRow("mgenres", "Movies by genre", 2, genres = it)) } }
            launch { runCatching { seerr.genres("tv") }.onSuccess { put(DiscoverRow("tgenres", "Shows by genre", 6, genres = it)) } }
        }
    }
}

private const val AUTO_ADVANCE_MS = 9_000
private val RowsTop = 232.dp

/**
 * Discover, through Seerr (Jellyseerr/Overseerr), built like Home: a carousel of what's trending,
 * then rows that rise in on Down (only the focused one lit), genre tiles that open a full grid,
 * and Search. Every title shows where it stands (requested, downloading, in your library).
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen() {
    val state = entryState { DiscoverState() }
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val heroRight = FinmaxApp.instance.settings.current.heroRight

    if (!state.seerr.isConfigured) {
        Box(Modifier.fillMaxSize()) {
            com.finmax.ui.components.BrandBackdrop()
            Column {
                TopBar(Route.Discover)
                MessagePanel(
                    "Discover needs Seerr",
                    "Connect Jellyseerr or Overseerr to see what's trending and request titles that aren't in your library.",
                    Modifier.padding(top = 60.dp),
                    actionLabel = "Set up in Settings",
                    onAction = {
                        com.finmax.ui.settings.SettingsLink.section = com.finmax.ui.settings.Section.DISCOVER
                        nav.tab(Route.Settings)
                    },
                )
            }
        }
        return
    }

    val primaryReq = remember { FocusRequester() }
    val rowsReq = remember { FocusRequester() }
    var topBarFocused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var glide by remember { mutableStateOf<Job?>(null) }
    val slide = state.slide
    val browsing = state.inRows || (slide == null && !state.loading)

    LaunchedEffect(state.hero) {
        val loader = SingletonImageLoader.get(context)
        state.hero.forEach { h -> h.backdrop?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) } }
    }
    val dwell = remember { Animatable(0f) }
    val paused = state.inRows || topBarFocused || state.hero.size < 2
    LaunchedEffect(state.index, paused) {
        dwell.snapTo(0f)
        if (paused) return@LaunchedEffect
        dwell.animateTo(1f, tween(AUTO_ADVANCE_MS, easing = LinearEasing))
        state.step(1)
    }
    fun focusSoon(req: FocusRequester) = scope.launch {
        repeat(15) {
            delay(30)
            if (runCatching { req.requestFocus() }.getOrDefault(false)) return@launch
        }
    }
    fun enterRows() {
        if (state.rows.isEmpty()) return
        state.inRows = true
        state.focusedRow = 0
        scope.launch { state.list.scrollToItem(0) }
        focusSoon(rowsReq)
    }
    fun backToHero() {
        if (slide == null) return
        state.inRows = false
        state.focusedRow = 0
        scope.launch { state.list.scrollToItem(0) }
        focusSoon(primaryReq)
    }
    BackHandler(enabled = state.inRows && slide != null) { backToHero() }

    val rowsIn by animateFloatAsState(if (browsing) 1f else 0f, tween(380, easing = FastOutSlowInEasing), label = "rows")

    Box(Modifier.fillMaxSize().background(Fm.Bg)) {
        // ---- Art: the carousel's slide, then the focused title once in the rows ----
        if (!browsing && slide != null) {
            AnimatedContent(slide, contentKey = { it.key }, transitionSpec = { horizontalSlide(state.direction) }, label = "art") { item ->
                GlassBackdrop(
                    item.backdrop, soft = item.backdropSmall, blur = 64.dp, reach = 0.12f,
                    modifier = if (heroRight) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
                )
            }
        } else {
            Crossfade(state.focused?.key, animationSpec = tween(450), label = "rowsArt") { k ->
                val item = state.focused?.takeIf { it.key == k }
                GlassBackdrop(item?.backdropMedium, soft = item?.backdropSmall, dim = 0.25f)
            }
        }

        TopBar(Route.Discover, Modifier.onFocusChanged { topBarFocused = it.hasFocus })

        // ---- Hero ----
        if (slide != null) Column(
            (if (heroRight) Modifier.align(Alignment.TopEnd).padding(end = Fm.SafeH, top = 68.dp) else Modifier.padding(start = Fm.SafeH, top = 68.dp))
                .width(580.dp)
                .graphicsLayer { alpha = 1f - rowsIn; translationY = -rowsIn * 60.dp.toPx() },
            horizontalAlignment = if (heroRight) Alignment.End else Alignment.Start,
        ) {
            AnimatedContent(
                slide, contentKey = { it.key }, transitionSpec = { horizontalSlide(state.direction, delayMs = 90) },
                label = "hero", modifier = Modifier.height(322.dp),
            ) { item -> HeroInfo(item, heroRight) }
            Spacer(Modifier.height(20.dp))
            HeroActions(slide, nav, primaryReq, onStep = { state.step(it) }, onDown = ::enterRows)
            Spacer(Modifier.height(18.dp))
            HeroDots(state.index, state.hero.size, { dwell.value }, Modifier.padding(start = 6.dp))
        } else if (state.error != null) {
            MessagePanel("Seerr isn't answering", state.error!!, Modifier.align(Alignment.Center), actionLabel = "Try again", onAction = { state.refresh() })
        }

        // ---- What the focused card is, above the rows ----
        if (browsing) RowsHeader(state.focused, Modifier.padding(start = Fm.SafeH, top = 70.dp).graphicsLayer { alpha = rowsIn })

        // ---- Rows ----
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
            LazyColumn(
                state = state.list,
                contentPadding = PaddingValues(bottom = 360.dp),
                modifier = Modifier.fillMaxSize().padding(top = RowsTop)
                    .graphicsLayer { alpha = rowsIn; translationY = (1f - rowsIn) * size.height }
                    .focusRequester(rowsReq)
                    .onFocusChanged { if (it.hasFocus) state.inRows = true }
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        val vertical = k.keyCode == KeyEvent.KEYCODE_DPAD_UP || k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        if (vertical && k.action == KeyEvent.ACTION_DOWN) held = k.repeatCount > 0
                        val up = k.keyCode == KeyEvent.KEYCODE_DPAD_UP && k.action == KeyEvent.ACTION_DOWN
                        val down = k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && k.action == KeyEvent.ACTION_DOWN
                        when {
                            vertical && k.action == KeyEvent.ACTION_DOWN && k.repeatCount > 0 && glide?.isActive == true -> true
                            down && state.focusedRow == state.rows.lastIndex -> true
                            // Holding Up stops at the first row; a fresh press goes back to the carousel.
                            up && state.focusedRow == 0 && k.repeatCount > 0 -> true
                            up && state.focusedRow == 0 && slide != null -> { backToHero(); true }
                            else -> false
                        }
                    },
            ) {
                if (state.loading && state.rows.isEmpty()) item { SkeletonRow(CardStyle.POSTER, 7) }
                itemsIndexed(state.rows, key = { _, r -> r.key }) { i, row ->
                    val active by remember(i) { derivedStateOf { state.focusedRow == i } }
                    val lit by animateFloatAsState(if (active) 1f else 0.32f, tween(240), label = "lit")
                    Column(Modifier.graphicsLayer { alpha = lit }) {
                        SectionTitle(row.title)
                        DiscoverRowContent(row, nav) { item ->
                            if (item != null) state.focused = item
                            if (state.focusedRow != i || state.list.firstVisibleItemIndex != i) {
                                state.focusedRow = i
                                glide = scope.launch { state.list.glideTo(i, held) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DiscoverRowContent(row: DiscoverRow, nav: Navigator, onFocused: (SeerrItem?) -> Unit) {
    val scope = rememberCoroutineScope()
    LazyRow(
        state = row.list,
        contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
        modifier = Modifier.focusRestorer(),
    ) {
        if (row.genres.isNotEmpty()) {
            itemsIndexed(row.genres, key = { _, g -> g.id }) { i, g ->
                GenreTile(g.name, g.backdrop, i, onClick = { nav.push(Route.SeerrGenre(g)) }, onFocused = { onFocused(null) },
                    modifier = Modifier.focusMemory("${row.key}/${g.id}"))
            }
        } else {
            itemsIndexed(row.items, key = { _, it -> it.key }) { i, item ->
                SeerrCard(
                    item,
                    Modifier.width(128.dp).aspectRatio(2f / 3f).focusMemory("${row.key}/${item.key}"),
                    onFocus = {
                        onFocused(item)
                        if (i >= row.items.size - 6) scope.launch { row.more() }
                    },
                ) { nav.openSeerr(item) }
            }
        }
    }
}

/** Request (or where it stands) / More info / Search. Left on the first and Right on the last page the carousel. */
@Composable
private fun HeroActions(item: SeerrItem, nav: Navigator, primaryReq: FocusRequester, onStep: (Int) -> Unit, onDown: () -> Unit) {
    var firstFocused by remember { mutableStateOf(false) }
    var lastFocused by remember { mutableStateOf(false) }
    val status = SeerrRequests.of(item)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            when (e.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (firstFocused) { onStep(-1); true } else false
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (lastFocused) { onStep(1); true } else false
                KeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                else -> false
            }
        },
    ) {
        val primary = Modifier.focusRequester(primaryReq).onFocusChanged { firstFocused = it.isFocused }.focusMemory("hero/primary", initial = true)
        // One button whose label changes with the slide: swapping buttons would drop the focus.
        val (label, icon) = when {
            status >= 5 && item.jellyfinId != null -> "Watch now" to Ic.Play
            status <= 1 -> "Request" to Ic.Add
            else -> (seerrStatusLabel(status) ?: "") to (if (status >= 4) Ic.CheckCircle else Ic.Clock)
        }
        PillButton(label, { nav.openSeerr(item, requestNow = status <= 1) }, icon = icon, primary = true, modifier = primary)
        GhostButton("More info", { nav.push(Route.SeerrTitle(item)) }, modifier = Modifier.focusMemory("hero/info"))
        IconButton(Ic.Search, "Search", { nav.push(Route.SeerrSearch) }, modifier = Modifier.onFocusChanged { lastFocused = it.isFocused }.focusMemory("hero/search"))
    }
}

@Composable
private fun HeroInfo(item: SeerrItem, right: Boolean) {
    Column(Modifier.height(322.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
        Overline(listOf("Trending", if (item.isMovie) "Movie" else "Series").joinToString("  ·  "))
        Spacer(Modifier.height(10.dp))
        BigTitle(item.title, maxLines = 2, textAlign = if (right) androidx.compose.ui.text.style.TextAlign.End else null)
        Spacer(Modifier.height(12.dp))
        MetaLine(
            listOfNotNull(item.rating?.let { "★ %.1f".format(it) }, item.year),
            color = Fm.Text,
            badge = seerrStatusLabel(SeerrRequests.of(item)),
        )
        Text(
            item.overview, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis,
            textAlign = if (right) androidx.compose.ui.text.style.TextAlign.End else null, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun RowsHeader(item: SeerrItem?, modifier: Modifier) {
    Column(modifier.width(640.dp).height(150.dp), verticalArrangement = Arrangement.Bottom) {
        if (item == null) {
            BigTitle("Discover", maxLines = 1)
            Text("Pick a genre, or anything that catches your eye.", style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim)
            return@Column
        }
        BigTitle(item.title, maxLines = 1)
        MetaLine(
            listOfNotNull(if (item.isMovie) "Movie" else "Series", item.year, item.rating?.let { "★ %.1f".format(it) }),
            Modifier.padding(top = 6.dp), color = Fm.Text, badge = seerrStatusLabel(SeerrRequests.of(item)),
        )
        Text(item.overview, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Poster card; a round badge in the corner says it's requested (clock) or in the library (check). */
@Composable
internal fun SeerrCard(item: SeerrItem, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    FocusTile(onClick = onClick, onFocused = onFocus, modifier = modifier) {
        item.poster?.let { AsyncImage(it, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.Center).padding(8.dp))
        StatusBadge(SeerrRequests.of(item), Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
internal fun StatusBadge(status: Int, modifier: Modifier = Modifier) {
    val icon = when (status) {
        5, 4 -> Ic.CheckCircle
        3, 2 -> Ic.Clock
        else -> return
    }
    Box(modifier.size(22.dp).background(Fm.Noir.copy(alpha = 0.78f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, seerrStatusLabel(status), Modifier.size(15.dp), tint = Fm.Accent)
    }
}
