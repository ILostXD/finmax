package com.finmax.ui.home

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.finmax.ui.components.CardStyle
import com.finmax.ui.components.MediaCard
import com.finmax.ui.components.rememberEdgeScroll
import com.finmax.ui.components.NoAutoScroll
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.Crossfade
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.finmax.FinmaxApp
import com.finmax.data.MediaRepository
import com.finmax.ui.LocalEntry
import com.finmax.ui.LocalNav
import com.finmax.ui.Navigator
import com.finmax.ui.Route
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.GlassPanelBackground
import com.finmax.ui.components.BrandBackdrop
import com.finmax.ui.components.CardShape
import com.finmax.ui.components.GhostButton
import com.finmax.ui.components.Hairline
import com.finmax.ui.components.IconButton
import com.finmax.ui.components.MessagePanel
import com.finmax.ui.components.MetaLine
import com.finmax.ui.components.Overline
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.ProgressStripe
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.Wordmark
import com.finmax.ui.components.episodeLabel
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.components.playKey
import com.finmax.ui.components.progress
import com.finmax.ui.entryState
import com.finmax.ui.theme.DisplayFont
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

data class HomeSection(val order: Int, val key: String, val title: String, val items: List<BaseItemDto>)

class HomeState(private val repo: MediaRepository, autoLoad: Boolean = true) : ScreenState() {
    val sections = mutableStateListOf<HomeSection>()
    var featured by mutableStateOf<List<BaseItemDto>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    /** Position in the spotlight; grows without bound, read modulo [featured] size. */
    var index by mutableIntStateOf(0)

    /** Focus is in the rows (the spotlight's buttons step aside). */
    var inRows by mutableStateOf(false)
    var focused by mutableStateOf<BaseItemDto?>(null)
    var focusedSection by mutableStateOf<String?>(null)
    var focusedRow by mutableIntStateOf(0)
    val rows = LazyListState()
    private val rowStates = HashMap<String, LazyListState>()
    fun rowState(key: String) = rowStates.getOrPut(key) { LazyListState() }

    val slide: BaseItemDto? get() = featured.takeIf { it.isNotEmpty() }?.let { it[index.mod(it.size)] }

    init { if (autoLoad) refresh() }

    fun step(by: Int) {
        if (featured.size > 1) index += by
    }

    fun refresh() {
        error = null
        loading = sections.isEmpty()
        scope.launch {
            val s = FinmaxApp.instance.settings.current
            val views = runCatching { repo.views() }.getOrElse {
                error = friendlyError(it)
                loading = false
                return@launch
            }
            fun put(order: Int, key: String, title: String, items: List<BaseItemDto>) {
                sections.removeAll { it.key == key }
                if (items.isEmpty()) return
                val at = sections.indexOfFirst { it.order > order }.let { if (it < 0) sections.size else it }
                sections.add(at, HomeSection(order, key, title, items))
            }
            fun add(order: Int, key: String, title: String, block: suspend () -> List<BaseItemDto>) = async {
                put(order, key, title, runCatching { block() }.getOrDefault(emptyList()))
            }
            val jobs = buildList {
                if (s.homeContinue) add(add(0, "resume", "Continue Watching") { repo.resume() })
                if (s.homeNextUp) add(add(1, "nextup", "Next Up") { repo.nextUp() })
                if (s.homeLatest) views.filter { it.collectionType != CollectionType.BOXSETS }.forEachIndexed { i, v ->
                    add(add(10 + i, "latest/${v.id}", "New in ${v.name}") { repo.latest(v.id) })
                }
                if (s.homeFavorites) add(add(30, "fav", "My List") {
                    repo.items(types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), filter = com.finmax.data.FilterChoice.FAVORITES, sort = com.finmax.data.SortChoice.ADDED, limit = 30).items
                })
                add(async {
                    runCatching { repo.recommendations() }.getOrDefault(emptyList()).forEachIndexed { i, rec ->
                        val title = rec.baselineItemName?.let { "Because you watched $it" } ?: "Recommended for you"
                        put(40 + i, "rec/$i", title, rec.items.orEmpty())
                    }
                })
                if (s.homeCollections) add(add(50, "collections", "Collections") {
                    repo.items(types = listOf(BaseItemKind.BOX_SET), sort = com.finmax.data.SortChoice.NAME, limit = 30).items
                })
                if (s.homeGenres) {
                    val movieView = views.firstOrNull { it.collectionType == CollectionType.MOVIES }
                    if (movieView != null) add(async {
                        runCatching { repo.genres(movieView.id) }.getOrDefault(emptyList()).shuffled().take(3).forEachIndexed { i, g ->
                            val items = runCatching {
                                repo.items(parentId = movieView.id, types = listOf(BaseItemKind.MOVIE), genre = g.name, sort = com.finmax.data.SortChoice.RANDOM, limit = 20).items
                            }.getOrDefault(emptyList())
                            if (items.size >= 4) put(60 + i, "genre/${g.name}", "${g.name} picks", items)
                        }
                    })
                }
                add(add(70, "watched", "Watched Recently") { repo.recentlyWatched() })
                add(add(80, "libraries", "Your Libraries") { views })
            }
            featured = runCatching { repo.featured(8) }.getOrDefault(emptyList())
            loading = false
            jobs.forEach { it.await() }
            if (sections.isEmpty() && featured.isEmpty()) error = "Your libraries look empty. Add media in Jellyfin, then come back."
        }
    }
}

fun Navigator.open(item: BaseItemDto) = when (item.type) {
    BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> push(Route.Library(item.id, item.name ?: "", item.collectionType))
    else -> push(Route.Detail(item.id))
}

private const val AUTO_ADVANCE_MS = 9_000L
private val CardWidth = 208.dp

/**
 * Home: the featured spotlight on top (text and actions on the left over frosted art, Left/Right on
 * the buttons pages through featured titles) and the content rows always peeking in underneath.
 * Down enters the rows; the backdrop and the info on the left then follow the focused card and the
 * focused row snaps up under it. Up from the first row (or Back) returns to the spotlight.
 */
@Composable
fun HomeScreen() {
    val repo = FinmaxApp.instance.repo
    HomeContent(entryState { HomeState(repo) }, repo)
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HomeContent(state: HomeState, repo: MediaRepository) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val playReq = remember { FocusRequester() }
    val topBarReq = remember { FocusRequester() }
    var topBarFocused by remember { mutableStateOf(false) }

    val slide = state.slide
    val active = if (state.inRows) state.focused ?: slide else slide
    // Follow focus with the artwork only once it settles, so fast scrolling doesn't load every backdrop.
    var art by remember { mutableStateOf(active) }
    LaunchedEffect(active?.id) {
        if (state.inRows) delay(260)
        art = active
    }

    // Warm the image cache so spotlight slides arrive with their artwork.
    LaunchedEffect(state.featured) {
        val loader = SingletonImageLoader.get(context)
        state.featured.forEach { f -> repo.backdrop(f)?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) } }
    }
    LaunchedEffect(state.index, state.inRows, topBarFocused, state.featured) {
        if (state.inRows || topBarFocused || state.featured.size < 2) return@LaunchedEffect
        delay(AUTO_ADVANCE_MS)
        state.step(1)
    }
    fun backToSpotlight() {
        state.inRows = false
        scope.launch { state.rows.animateScrollToItem(0) }
        scope.launch {
            repeat(10) {
                delay(40)
                if (runCatching { playReq.requestFocus() }.getOrDefault(false)) return@launch
            }
        }
    }
    // Rows load in any order; until the user enters them, keep the first row (Continue Watching) on top.
    LaunchedEffect(state.sections.size) { if (!state.inRows) state.rows.scrollToItem(0) }
    BackHandler(enabled = state.inRows) { backToSpotlight() }
    BackHandler(enabled = !state.inRows && !topBarFocused) { runCatching { topBarReq.requestFocus() } }

    val rowsTop by animateDpAsState(if (state.inRows) 262.dp else 372.dp, tween(450, easing = FastOutSlowInEasing), label = "rowsTop")
    val spotlight by animateFloatAsState(if (state.inRows) 0f else 1f, tween(300), label = "spotlight")

    Box(Modifier.fillMaxSize().background(Fm.Bg)) {
        Crossfade(art, animationSpec = tween(550), label = "art") { item ->
            if (item == null) BrandBackdrop() else GlassBackdrop(repo.backdrop(item), soft = repo.backdrop(item, 320))
        }
        TopBar(Route.Home, Modifier.focusRequester(topBarReq).onFocusChanged { topBarFocused = it.hasFocus })

        when {
            slide == null && state.error != null -> MessagePanel(
                "Nothing to show yet", state.error!!, Modifier.align(Alignment.Center),
                actionLabel = "Try again", onAction = { state.refresh() },
            )
            else -> Column(Modifier.align(Alignment.TopStart).padding(start = Fm.SafeH, top = 84.dp).width(480.dp)) {
                AnimatedContent(
                    targetState = active,
                    contentKey = { it?.id },
                    transitionSpec = { fadeIn(tween(350, 80)) togetherWith fadeOut(tween(200)) },
                    label = "info",
                ) { item ->
                    if (item != null) Info(item, repo, if (state.inRows) state.focusedSection else null)
                }
                if (slide != null) {
                    // Always composed (so Up can return to it), but stepped aside while browsing the rows.
                    Column(Modifier.graphicsLayer { alpha = spotlight }.focusProperties { canFocus = !state.inRows }) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpotlightActions(
                                item = slide,
                                repo = repo,
                                nav = nav,
                                playReq = playReq,
                                onStep = { state.step(it) },
                            )
                            Spacer(Modifier.width(20.dp))
                            SpotlightDots(state.index, state.featured.size)
                        }
                    }
                }
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
            LazyColumn(
                state = state.rows,
                contentPadding = PaddingValues(bottom = 320.dp),
                modifier = Modifier.fillMaxSize().padding(top = rowsTop)
                    .onFocusChanged { if (it.hasFocus) state.inRows = true }
                    .onPreviewKeyEvent { e ->
                        val up = e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        if (up && state.inRows && state.focusedRow == 0) { backToSpotlight(); true } else false
                    },
            ) {
                itemsIndexed(state.sections, key = { _, s -> s.key }) { i, section ->
                    HomeRow(
                        section = section,
                        listState = state.rowState(section.key),
                        onOpen = { nav.open(it) },
                        onFocused = { item ->
                            state.focused = item
                            state.focusedSection = section.title
                            if (state.focusedRow != i || state.rows.firstVisibleItemIndex != i) {
                                state.focusedRow = i
                                scope.launch { state.rows.animateScrollToItem(i) }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Overline, logo or title, facts and a short synopsis for the spotlight or the focused card. */
@Composable
private fun Info(item: BaseItemDto, repo: MediaRepository, section: String?) {
    val logo = remember(item.id) { if (item.type == BaseItemKind.EPISODE) null else repo.logo(item) }
    val browsing = section != null
    Column(Modifier.height(if (browsing) 170.dp else 226.dp), verticalArrangement = Arrangement.Bottom) {
        Overline(section ?: listOfNotNull("Featured", typeLabel(item)).joinToString("  ·  "))
        Spacer(Modifier.height(8.dp))
        if (item.type == BaseItemKind.EPISODE) {
            Text(item.seriesName ?: "", style = MaterialTheme.typography.titleMedium, color = Fm.TextDim, maxLines = 1)
        }
        if (logo != null) {
            AsyncImage(
                logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                modifier = Modifier.height(if (browsing) 60.dp else 84.dp).fillMaxWidth(0.8f),
            )
        } else {
            BigTitle(item.name ?: "", maxLines = if (browsing) 1 else 2)
        }
        Spacer(Modifier.height(10.dp))
        if (browsing) MetaLine(metaParts(item)) else RatingLine(item)
        Text(
            item.overview ?: "", style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
            maxLines = if (browsing) 2 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        if (browsing) item.progress?.let { p -> Box(Modifier.padding(top = 10.dp).width(220.dp).clip(CircleShape)) { ProgressStripe(p) } }
    }
}

/** Play / Details / My List for the current spotlight title. Left and Right at the ends page through titles. */
@Composable
private fun SpotlightActions(
    item: BaseItemDto,
    repo: MediaRepository,
    nav: Navigator,
    playReq: FocusRequester,
    onStep: (Int) -> Unit,
) {
    var fav by remember(item.id) { mutableStateOf(item.userData?.isFavorite == true) }
    var firstFocused by remember { mutableStateOf(false) }
    var lastFocused by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            when (e.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (firstFocused) { onStep(-1); true } else false
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (lastFocused) { onStep(1); true } else false
                else -> false
            }
        },
    ) {
        val resumable = (item.userData?.playbackPositionTicks ?: 0) > 0
        PillButton(
            if (resumable) "Resume" else "Play now",
            { if (item.type == BaseItemKind.SERIES) nav.push(Route.Detail(item.id)) else nav.push(Route.Player(item.id)) },
            icon = Icons.Filled.PlayArrow, primary = true,
            modifier = Modifier.focusRequester(playReq).onFocusChanged { firstFocused = it.isFocused }.focusMemory("hero/play", initial = true),
        )
        GhostButton("Details", { nav.push(Route.Detail(item.id)) }, modifier = Modifier.focusMemory("hero/details"))
        IconButton(
            if (fav) Icons.Filled.Check else Icons.Filled.Add,
            if (fav) "In My List" else "Add to My List",
            { FinmaxApp.instance.appScope.launch { runCatching { fav = repo.setFavorite(item.id, !fav) } } },
            active = fav,
            modifier = Modifier.onFocusChanged { lastFocused = it.isFocused }.focusMemory("hero/fav"),
        )
    }
}

/** Fixed-size dots in a soft dark pill, readable on any artwork and never shifting with the count. */
@Composable
private fun SpotlightDots(index: Int, count: Int) {
    if (count < 2) return
    val current = index.mod(count)
    Row(
        Modifier.clip(CircleShape).background(Fm.Bg.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 18.dp else 6.dp, tween(350), label = "dot")
            Box(Modifier.width(w).height(6.dp).clip(CircleShape).background(if (i == current) Fm.Accent else Fm.Mist.copy(alpha = 0.45f)))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeRow(
    section: HomeSection,
    listState: LazyListState,
    onOpen: (BaseItemDto) -> Unit,
    onFocused: (BaseItemDto) -> Unit,
) {
    val nav = LocalNav.current
    var focusedIndex by remember { mutableIntStateOf(-1) }
    Column(
        // Right past the last card stays put instead of jumping to whatever lies beyond (the top bar).
        Modifier.onPreviewKeyEvent { e ->
            e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                focusedIndex == section.items.lastIndex
        },
    ) {
        Text(section.title, style = MaterialTheme.typography.titleMedium, color = Fm.Text, modifier = Modifier.padding(start = Fm.SafeH, top = 6.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeScroll()) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
                modifier = Modifier.focusRestorer(),
            ) {
                itemsIndexed(section.items, key = { _, it -> it.id }) { i, item ->
                    MediaCard(
                        item = item,
                        style = CardStyle.LANDSCAPE,
                        width = CardWidth,
                        onClick = { onOpen(item) },
                        onFocused = { focusedIndex = i; onFocused(item) },
                        modifier = Modifier.focusMemory("${section.key}/${item.id}").playKey(item, nav),
                    )
                }
            }
        }
    }
}

/** Large expanded title for items without logo art; scales down for long names. */
@Composable
fun BigTitle(name: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    val style = when {
        name.length <= 10 -> MaterialTheme.typography.displayLarge
        name.length <= 22 -> MaterialTheme.typography.displayMedium
        else -> MaterialTheme.typography.displaySmall
    }
    Text(name.uppercase(), style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** ★★★½☆ 7.4  │  2h 35m  │  PG-13 */
@Composable
fun RatingLine(item: BaseItemDto, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val parts = mutableListOf<@Composable () -> Unit>()
        item.communityRating?.let { r ->
            parts += {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val halves = (r.toDouble()).let { Math.round(it) }.toInt() // 0..10 half-stars
                    repeat(5) { i ->
                        val icon = when {
                            halves >= (i + 1) * 2 -> Icons.Filled.Star
                            halves == i * 2 + 1 -> Icons.AutoMirrored.Filled.StarHalf
                            else -> Icons.Filled.StarBorder
                        }
                        Icon(icon, null, Modifier.size(13.dp), tint = Fm.Mist)
                    }
                    Text("  %.1f".format(r), style = MaterialTheme.typography.labelMedium, color = Fm.Text)
                }
            }
        }
        val runtime = item.runTimeTicks?.takeIf { it >= 600_000_000 }?.let { formatRuntime(it / 10_000) }
            ?: item.childCount?.takeIf { item.type == BaseItemKind.SERIES }?.let { "$it Season${if (it == 1) "" else "s"}" }
        runtime?.let { parts += { Text(it, style = MaterialTheme.typography.labelMedium, color = Fm.Text) } }
        item.officialRating?.let { parts += { com.finmax.ui.components.Badge(it) } }
        parts.forEachIndexed { i, p ->
            if (i > 0) Hairline(Modifier.height(12.dp), vertical = true, alpha = 0.4f)
            p()
        }
    }
}

/** The top of the recommendations list peeking in at the bottom right, hinting that Right opens it. */
private fun typeLabel(item: BaseItemDto) = when (item.type) {
    BaseItemKind.MOVIE -> "Movie"
    BaseItemKind.SERIES -> "Series"
    BaseItemKind.EPISODE -> "Episode"
    BaseItemKind.BOX_SET -> "Collection"
    else -> null
}

fun metaParts(item: BaseItemDto): List<String> = listOfNotNull(
    when (item.type) {
        BaseItemKind.MOVIE -> "Movie"
        BaseItemKind.SERIES -> "Series"
        BaseItemKind.EPISODE -> item.parentIndexNumber?.let { s -> "S$s:E${item.indexNumber ?: "?"}" }
        BaseItemKind.BOX_SET -> "Collection"
        else -> null
    },
    item.genres?.firstOrNull(),
    item.productionYear?.toString(),
    item.runTimeTicks?.takeIf { it >= 600_000_000 }?.let { formatRuntime(it / 10_000) },
    item.childCount?.takeIf { item.type == BaseItemKind.SERIES }?.let { "$it Season${if (it == 1) "" else "s"}" },
    item.officialRating,
    item.communityRating?.let { "★ %.1f".format(it) },
)

fun formatRuntime(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}
