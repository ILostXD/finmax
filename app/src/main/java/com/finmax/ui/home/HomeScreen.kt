package com.finmax.ui.home

import com.finmax.ui.components.Ic

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.finmax.FinmaxApp
import com.finmax.R
import com.finmax.data.MediaRepository
import com.finmax.ui.LocalNav
import com.finmax.ui.Navigator
import com.finmax.ui.Route
import com.finmax.ui.components.AmbientVideo
import com.finmax.ui.components.BrandBackdrop
import com.finmax.ui.components.GhostButton
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.Hairline
import com.finmax.ui.components.IconButton
import com.finmax.ui.components.MessagePanel
import com.finmax.ui.components.MetaLine
import com.finmax.ui.components.NoAutoScroll
import com.finmax.ui.components.Overline
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.ProgressStripe
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.episodeLabel
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.components.playKey
import com.finmax.ui.components.progress
import com.finmax.ui.components.rememberEdgeScroll
import com.finmax.ui.entryState
import com.finmax.ui.theme.DisplayFont
import com.finmax.ui.theme.Fm
import com.finmax.ui.theme.WideFont
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType

/** How a Home row draws its items: genre tiles, posters that widen on focus, or landscape cards. */
enum class RowKind { GENRES, POSTER, LANDSCAPE }

data class HomeSection(
    val order: Int,
    val key: String,
    val title: String,
    val items: List<BaseItemDto>,
    val kind: RowKind = RowKind.LANDSCAPE,
)

class HomeState(private val repo: MediaRepository, autoLoad: Boolean = true) : ScreenState() {
    val sections = mutableStateListOf<HomeSection>()
    var featured by mutableStateOf<List<BaseItemDto>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    /** Backdrop shown on each genre tile, by genre name. */
    val genreArt = mutableStateMapOf<String, String>()

    /** Position in the carousel; grows without bound, read modulo [featured] size. */
    var index by mutableIntStateOf(0)
    /** +1 when the carousel moved to the next title, -1 to the previous. Drives the slide direction. */
    var direction by mutableIntStateOf(1)

    /** Focus is in the rows: the hero slides away and the rows rise under the top bar. */
    var inRows by mutableStateOf(false)
    var focused by mutableStateOf<BaseItemDto?>(null)
    var focusedRow by mutableIntStateOf(0)
    val rows = LazyListState()
    private val rowStates = HashMap<String, LazyListState>()
    // Rows prefetch a screenful of cards ahead of scrolling into view, not the default two, so the
    // frame a row arrives in doesn't have to build most of it.
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    fun rowState(key: String) = rowStates.getOrPut(key) {
        LazyListState(prefetchStrategy = androidx.compose.foundation.lazy.LazyListPrefetchStrategy(nestedPrefetchItemCount = 8))
    }

    val slide: BaseItemDto? get() = featured.takeIf { it.isNotEmpty() }?.let { it[index.mod(it.size)] }

    init { if (autoLoad) refresh() }

    /** The Home-row settings the rows were built with; a change (Settings → Home) rebuilds them. */
    private var builtWith: Any? = null
    fun rowSettings(key: Any) {
        if (builtWith != null && builtWith != key) {
            sections.clear() // rows switched off must go, not just the ones rebuilt
            refresh()
        }
        builtWith = key
    }

    fun step(by: Int) {
        if (featured.size < 2) return
        direction = by
        index += by
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
            fun put(order: Int, key: String, title: String, items: List<BaseItemDto>, kind: RowKind) {
                sections.removeAll { it.key == key }
                if (items.isEmpty()) return
                val at = sections.indexOfFirst { it.order > order }.let { if (it < 0) sections.size else it }
                sections.add(at, HomeSection(order, key, title, items, kind))
            }
            fun add(order: Int, key: String, title: String, kind: RowKind, block: suspend () -> List<BaseItemDto>) = async {
                put(order, key, title, runCatching { block() }.getOrDefault(emptyList()), kind)
            }
            // Each group's place comes from the user's order (Settings → Home rows); rows within a group follow it.
            val place = com.finmax.data.HomeRows.normalized(s.homeOrder).withIndex().associate { (i, k) -> k to i * 10 }
            fun at(group: String, sub: Int = 0) = place.getValue(group) + sub
            val jobs = buildList {
                if (s.rowOn("genres")) add(async {
                    val genres = runCatching { repo.genres(null) }.getOrDefault(emptyList())
                    put(at("genres"), "genres", "Genres", genres, RowKind.GENRES)
                    // One top-rated title's art per genre, avoiding repeats where a title spans genres.
                    val used = HashSet<java.util.UUID>()
                    genres.take(40).forEach { g ->
                        launch {
                            val name = g.name ?: return@launch
                            val picks = runCatching {
                                repo.items(types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), genre = name, sort = com.finmax.data.SortChoice.RATING, limit = 12).items
                            }.getOrDefault(emptyList()).filter { !it.backdropImageTags.isNullOrEmpty() }
                            val pick = picks.firstOrNull { it.id !in used } ?: picks.firstOrNull() ?: return@launch
                            used += pick.id
                            repo.backdrop(pick, 480)?.let { genreArt[name] = it }
                        }
                    }
                })
                if (s.rowOn("nextwatch")) add(add(at("nextwatch"), "nextwatch", "Your Next Watch", RowKind.POSTER) {
                    val recs = runCatching { repo.recommendations() }.getOrDefault(emptyList())
                        .flatMap { it.items.orEmpty() }.distinctBy { it.id }.take(24)
                    recs.ifEmpty {
                        repo.items(types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), sort = com.finmax.data.SortChoice.RANDOM, limit = 24).items
                    }
                })
                if (s.homeMerge && (s.homeContinue || s.homeNextUp)) add(add(at("resume"), "resume", "Continue Watching", RowKind.LANDSCAPE) {
                    // Half-watched first, then the next episode of shows not already in progress.
                    coroutineScope {
                        val resume = async { if (s.homeContinue) repo.resume() else emptyList() }
                        val next = async { if (s.homeNextUp) repo.nextUp() else emptyList() }
                        val r = resume.await()
                        val busy = r.mapNotNull { it.seriesId }.toSet()
                        (r + next.await().filter { it.seriesId !in busy }).distinctBy { it.id }
                    }
                }) else {
                    if (s.homeContinue) add(add(at("resume"), "resume", "Continue Watching", RowKind.LANDSCAPE) { repo.resume() })
                    if (s.homeNextUp) add(add(at("nextup"), "nextup", "Next Up", RowKind.LANDSCAPE) { repo.nextUp() })
                }
                if (s.homeLatest) views.filter { it.collectionType != CollectionType.BOXSETS }.forEachIndexed { i, v ->
                    add(add(at("latest", i), "latest/${v.id}", "New in ${v.name}", RowKind.POSTER) { repo.latest(v.id) })
                }
                if (s.homeFavorites) add(add(at("fav"), "fav", "My List", RowKind.POSTER) {
                    repo.items(types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), filter = com.finmax.data.FilterChoice.FAVORITES, sort = com.finmax.data.SortChoice.ADDED, limit = 30).items
                })
                if (s.homePlaylists) add(add(at("playlists"), "playlists", "Playlists", RowKind.LANDSCAPE) { repo.playlists() })
                if (s.homeCollections) add(add(at("collections"), "collections", "Collections", RowKind.POSTER) {
                    repo.items(types = listOf(BaseItemKind.BOX_SET), sort = com.finmax.data.SortChoice.NAME, limit = 30).items
                })
                if (s.homeGenres) {
                    val movieView = views.firstOrNull { it.collectionType == CollectionType.MOVIES }
                    if (movieView != null) add(async {
                        runCatching { repo.genres(movieView.id) }.getOrDefault(emptyList()).shuffled().take(3).forEachIndexed { i, g ->
                            val items = runCatching {
                                repo.items(parentId = movieView.id, types = listOf(BaseItemKind.MOVIE), genre = g.name, sort = com.finmax.data.SortChoice.RANDOM, limit = 20).items
                            }.getOrDefault(emptyList())
                            if (items.size >= 4) put(at("genrepicks", i), "genre/${g.name}", "${g.name} picks", items, RowKind.POSTER)
                        }
                    })
                }
                if (s.rowOn("watched")) add(add(at("watched"), "watched", "Watched Recently", RowKind.POSTER) { repo.recentlyWatched() })
                if (s.rowOn("libraries")) add(add(at("libraries"), "libraries", "Your Libraries", RowKind.LANDSCAPE) { views })
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

private const val AUTO_ADVANCE_MS = 10_000L
private val RowsTop = 64.dp
private val HomeCardShape = RoundedCornerShape(8.dp)

/** One timing for the Home motions that must land together: the row glide and the card resize. */
private const val MOVE_MS = 240
/** How long a card must hold focus before its video starts. */
private const val VIDEO_DWELL_MS = 1_500L
// Creating the preview's player blocks the main thread for 100-450 ms on the Chromecast: arm it only
// after the row glide (460 ms) and card resize have finished, or the glide stalls and then jumps.
private const val VIDEO_ARM_MS = 700L

/**
 * The one card preview on Home. It lives in a layer over the whole page (not inside the card), so
 * on hand-off it can grow from the card to full screen while it keeps playing.
 */
@androidx.compose.runtime.Stable
private class PreviewHost {
    var item by mutableStateOf<BaseItemDto?>(null)
        private set
    var resumeMs: Long? = null
        private set
    var reveal by mutableStateOf(false)
    /** Set when the card is opened while its preview plays: the details page carries the video on. */
    var carry = false
    /** The focused card's bounds in the window. */
    var bounds by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    val grow = Animatable(0f)

    suspend fun show(item: BaseItemDto, resumeMs: Long?) {
        grow.snapTo(0f)
        reveal = false
        carry = false
        this.resumeMs = resumeMs
        this.item = item
    }

    fun clear(id: java.util.UUID? = item?.id) {
        if (item?.id != id) return
        item = null
        reveal = false
    }
}

private val LocalPreview = androidx.compose.runtime.staticCompositionLocalOf<PreviewHost?> { null }

/** The card that last handed off to the player: Back lands on it again, and it must not replay until focus moves on. */
private var handedOff: java.util.UUID? = null
private fun <T> move() = tween<T>(MOVE_MS, easing = FastOutSlowInEasing)

/** Rows travel slower than cards: a whole screen of content moves, and slower reads smoother here. */
internal fun <T> rowMove(held: Boolean) = tween<T>(if (held) 300 else 460, easing = FastOutSlowInEasing)

/**
 * Glides row [index] to the top of the list. Every frame aims at where the row is now, so the info
 * line swapping rows can't make it overshoot. A row above that is off screen is first nudged into
 * view by a pixel so going up glides like going down. A held D-pad ([held]) glides faster; the key
 * handler waits for each glide to end, so holding steps steadily instead of jumping.
 */
internal suspend fun LazyListState.glideTo(index: Int, held: Boolean) {
    fun offsetOf() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.toFloat()
    if (offsetOf() == null && index == firstVisibleItemIndex - 1) {
        // A 1 px scroll can be applied without a new layout pass, so the row above only joins the
        // visible items on the next frame. Checking before that made every other Up jump instead of glide.
        scroll { scrollBy(-1f) }
        androidx.compose.runtime.withFrameNanos { }
    }
    if (offsetOf() == null) {
        animateScrollToItem(index)
        return
    }
    var done = 0f
    scroll {
        animate(0f, 1f, animationSpec = rowMove(held)) { p, _ ->
            val d = offsetOf() ?: 0f
            scrollBy(if (p >= 1f) d else d * (p - done) / (1f - done))
            done = p
        }
    }
}

/**
 * One card row's motion. A single progress value opens the new card, closes the old one and slides
 * the row so the new card lands at the start. The slide is planned up front and re-anchored on the
 * new card every frame, so the cards changing size never feed back into the scroll (that feedback
 * is what made it overshoot and spring back).
 */
@androidx.compose.runtime.Stable
private class RowMotion {
    private var target by mutableIntStateOf(-1)
    private val progress = Animatable(1f)
    private var start = emptyMap<Int, Float>()

    /** 0 = resting, 1 = fully open. Read it in layout or draw only. */
    fun openness(i: Int): Float {
        val s = start[i] ?: 0f
        return s + ((if (i == target) 1f else 0f) - s) * progress.value
    }

    private fun retarget(to: Int) {
        start = (start.keys + target).associateWith { openness(it) }.filterValues { it > 0f }
        target = to
    }

    /** Focus left the row: the open card folds back to rest where it is. */
    suspend fun close() {
        if (target < 0) return
        retarget(-1)
        progress.snapTo(0f)
        progress.animateTo(1f, move())
    }

    suspend fun moveTo(to: Int, list: LazyListState, held: Boolean) {
        val d0 = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == to }?.offset
        retarget(to)
        if (held || d0 == null) {
            progress.snapTo(1f)
            list.requestScrollToItem(to)
            return
        }
        progress.snapTo(0f)
        list.requestScrollToItem(to, -d0)
        progress.animateTo(1f, move()) { list.requestScrollToItem(to, -(d0 * (1f - value)).roundToInt()) }
    }
}

/**
 * Home: a horizontal carousel of random titles fills the screen (Left on Play / Right on the last
 * button page through it). Down slides it away and the rows rise on a plain dark background, like
 * Netflix: genre tiles, Your Next Watch, Continue Watching and the rest. Up from the first row (or
 * Back) brings the carousel back.
 */
@Composable
fun HomeScreen() {
    val repo = FinmaxApp.instance.repo
    val s = FinmaxApp.instance.settings.state.collectAsState().value
    val state = entryState { HomeState(repo) }
    val rowsKey = listOf(s.homeOrder, s.homeHidden, s.homeMerge, s.homeContinue, s.homeNextUp, s.homeLatest, s.homeFavorites, s.homeCollections, s.homeGenres, s.homePlaylists)
    androidx.compose.runtime.LaunchedEffect(rowsKey) { state.rowSettings(rowsKey) }
    HomeContent(state, repo, s.heroRight)
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HomeContent(state: HomeState, repo: MediaRepository, heroRight: Boolean = true) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val preview = remember { PreviewHost() }

    val playReq = remember { FocusRequester() }
    val rowsReq = remember { FocusRequester() }
    val topBarReq = remember { FocusRequester() }
    var topBarFocused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var glide by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val slide = state.slide
    val browsing = state.inRows || slide == null

    // Warm the image cache so carousel slides arrive with their artwork.
    LaunchedEffect(state.featured) {
        val loader = SingletonImageLoader.get(context)
        state.featured.forEach { f -> repo.backdrop(f)?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) } }
    }
    // The current dot fills over AUTO_ADVANCE_MS; when it is full the carousel moves on.
    val dwell = remember { Animatable(0f) }
    val paused = state.inRows || topBarFocused || state.featured.size < 2
    LaunchedEffect(state.index, paused) {
        dwell.snapTo(0f)
        if (paused) return@LaunchedEffect
        dwell.animateTo(1f, tween(AUTO_ADVANCE_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing))
        state.step(1)
    }
    fun focusSoon(req: FocusRequester) = scope.launch {
        repeat(15) {
            delay(30)
            if (runCatching { req.requestFocus() }.getOrDefault(false)) return@launch
        }
    }
    fun enterRows() {
        state.inRows = true
        state.focusedRow = 0
        scope.launch { state.rows.scrollToItem(0) }
        focusSoon(rowsReq)
    }
    fun backToHero() {
        state.inRows = false
        state.focusedRow = 0
        scope.launch { state.rows.scrollToItem(0) }
        focusSoon(playReq)
    }
    // Rows load in any order; until the user enters them, keep the first one (genres) on top.
    LaunchedEffect(state.sections.size) { if (!state.inRows) state.rows.scrollToItem(0) }
    BackHandler(enabled = state.inRows && slide != null) { backToHero() }
    BackHandler(enabled = !state.inRows && !topBarFocused) { runCatching { topBarReq.requestFocus() } }

    val rowsInState = animateFloatAsState(if (browsing) 1f else 0f, tween(380, easing = FastOutSlowInEasing), label = "rows")
    val rowsIn by rowsInState
    // Only flips at the end of the transition, so the screen doesn't recompose every frame of it.
    val covered by remember { androidx.compose.runtime.derivedStateOf { rowsInState.value >= 1f } }

    Box(Modifier.fillMaxSize().background(Fm.Bg)) {
        // ---- Hero artwork; the rows sit on plain dark, like the Movies and Shows pages ----
        if (covered) {
            // Covered by the rows' plain background: skip the full-screen blur entirely.
        } else if (slide == null) {
            BrandBackdrop()
        } else {
            AnimatedContent(
                targetState = slide,
                contentKey = { it.id },
                transitionSpec = { horizontalSlide(state.direction) },
                label = "art",
            ) { item ->
                // Mirrored, so the art's subject sits on the left and the glass under the text on the right.
                GlassBackdrop(repo.backdrop(item), soft = repo.backdrop(item, 320), blur = 64.dp, reach = 0.12f, modifier = if (heroRight) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = rowsIn }.background(Fm.Bg))

        TopBar(Route.Home, Modifier.focusRequester(topBarReq).onFocusChanged { topBarFocused = it.hasFocus })

        // ---- Hero carousel ----
        when {
            slide != null -> Column(
                (if (heroRight) Modifier.align(Alignment.TopEnd).padding(end = Fm.SafeH, top = 68.dp) else Modifier.padding(start = Fm.SafeH, top = 68.dp))
                    .width(580.dp)
                    .graphicsLayer {
                        alpha = 1f - rowsIn
                        translationY = -rowsIn * 60.dp.toPx()
                    },
                horizontalAlignment = if (heroRight) Alignment.End else Alignment.Start,
            ) {
                // Only the words travel with the carousel; the buttons stay put so focus never drops.
                AnimatedContent(
                    targetState = slide,
                    contentKey = { it.id },
                    transitionSpec = { horizontalSlide(state.direction, delayMs = 90) },
                    label = "hero",
                    modifier = Modifier.height(322.dp),
                ) { item -> SlideInfo(item, repo, heroRight) }
                Spacer(Modifier.height(20.dp))
                HeroActions(
                    item = slide,
                    repo = repo,
                    nav = nav,
                    playReq = playReq,
                    onStep = { state.step(it) },
                    onDown = { if (state.sections.isNotEmpty()) enterRows() },
                )
                Spacer(Modifier.height(18.dp))
                HeroDots(state.index, state.featured.size, { dwell.value }, Modifier.padding(start = 6.dp))
            }
            state.error != null && state.sections.isEmpty() -> MessagePanel(
                "Nothing to show yet", state.error!!, Modifier.align(Alignment.Center),
                actionLabel = "Try again", onAction = { state.refresh() },
            )
        }

        CompositionLocalProvider(LocalPreview provides preview) {
        // ---- Rows: off screen below until Down, then they rise under the top bar ----
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
            LazyColumn(
                state = state.rows,
                contentPadding = PaddingValues(bottom = 360.dp),
                modifier = Modifier.fillMaxSize().padding(top = RowsTop)
                    .graphicsLayer {
                        alpha = rowsIn
                        translationY = (1f - rowsIn) * size.height
                    }
                    .focusRequester(rowsReq)
                    .onFocusChanged { if (it.hasFocus) state.inRows = true }
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        val vertical = k.keyCode == KeyEvent.KEYCODE_DPAD_UP || k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        if (vertical && k.action == KeyEvent.ACTION_DOWN) held = k.repeatCount > 0
                        val up = k.keyCode == KeyEvent.KEYCODE_DPAD_UP && k.action == KeyEvent.ACTION_DOWN
                        val down = k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && k.action == KeyEvent.ACTION_DOWN
                        when {
                            // A held key waits for the current glide, so rows step by steadily instead of skipping.
                            vertical && k.action == KeyEvent.ACTION_DOWN && k.repeatCount > 0 && glide?.isActive == true -> true
                            // Nothing below the last row: swallow Down so focus search can't bounce around.
                            down && state.focusedRow == state.sections.lastIndex -> true
                            up && state.inRows && state.focusedRow == 0 && slide != null -> { backToHero(); true }
                            else -> false
                        }
                    },
            ) {
                itemsIndexed(state.sections, key = { _, s -> s.key }) { i, section ->
                    // Only the focused row is lit, like Netflix; the next one peeks in dimmed.
                    val active by remember(i) { androidx.compose.runtime.derivedStateOf { state.focusedRow == i } }
                    val lit = animateFloatAsState(if (active) 1f else 0.32f, move(), label = "lit")
                    Box(Modifier.graphicsLayer { alpha = lit.value }) {
                    HomeRow(
                        section = section,
                        repo = repo,
                        genreArt = state.genreArt,
                        listState = state.rowState(section.key),
                        onFocused = { item ->
                            if (section.kind != RowKind.GENRES) state.focused = item
                            if (state.focusedRow != i || state.rows.firstVisibleItemIndex != i) {
                                state.focusedRow = i
                                glide = scope.launch { state.rows.glideTo(i, held) }
                            }
                        },
                    )
                    }
                }
            }
        }
        }

        // ---- Card preview: over the focused card; on hand-off it grows to full screen ----
        preview.item?.let { item ->
            androidx.compose.runtime.key(item.id) {
                Box(
                    Modifier
                        .layout { m, c ->
                            val b = preview.bounds
                            val g = preview.grow.value
                            val w = androidx.compose.ui.util.lerp(b.width, c.maxWidth.toFloat(), g).roundToInt().coerceAtLeast(1)
                            val h = androidx.compose.ui.util.lerp(b.height, c.maxHeight.toFloat(), g).roundToInt().coerceAtLeast(1)
                            val placeable = m.measure(Constraints.fixed(w, h))
                            layout(c.maxWidth, c.maxHeight) {
                                placeable.place(androidx.compose.ui.util.lerp(b.left, 0f, g).roundToInt(), androidx.compose.ui.util.lerp(b.top, 0f, g).roundToInt())
                            }
                        }
                        .graphicsLayer {
                            shape = RoundedCornerShape((8f * (1f - preview.grow.value)).dp)
                            clip = true
                        }
                        // The video covers the inner half of the card's 2 dp focus ring: redraw only that half (1 dp), or
                        // the ring visibly thickens the moment the preview layer appears. Gone once full screen.
                        .border(1.dp * (1f - preview.grow.value), Fm.Focus, RoundedCornerShape((8f * (1f - preview.grow.value)).dp)),
                ) {
                    AmbientVideo(
                        item, playing = true, settleMs = 0, reveal = preview.reveal,
                        resumeMs = preview.resumeMs,
                        carryOver = { preview.carry.also { preview.carry = false } },
                        onHandOff = { position ->
                            handedOff = item.id
                            scope.launch {
                                preview.grow.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                                nav.push(Route.Player(item.id, startMs = position()))
                                preview.clear()
                            }
                        },
                    )
                }
            }
        }
    }
}

internal fun horizontalSlide(direction: Int, delayMs: Int = 0): ContentTransform =
    (slideInHorizontally(tween(460, delayMs, FastOutSlowInEasing)) { w -> w * direction / 5 } + fadeIn(tween(380, delayMs))) togetherWith
        (slideOutHorizontally(tween(400, easing = FastOutSlowInEasing)) { w -> -w * direction / 5 } + fadeOut(tween(260)))

/**
 * One dot per carousel title, no container or numbers. The current one stretches into a pill that
 * fills with amethyst as its time runs out ([fill], read only while drawing).
 */
@Composable
internal fun HeroDots(index: Int, count: Int, fill: () -> Float, modifier: Modifier = Modifier) {
    if (count < 2) return
    val current = index.mod(count)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 28.dp else 6.dp, tween(400), label = "dot")
            Box(
                Modifier.width(w).height(6.dp).clip(CircleShape).background(Fm.Mist.copy(alpha = 0.55f))
                    .drawBehind { if (i == current) drawRect(Fm.Accent, size = size.copy(width = size.width * fill())) },
            )
        }
    }
}

/**
 * Play / Details / My List for the current title. Left on Play and Right on the last button page
 * through titles; Down opens the rows.
 */
@Composable
private fun HeroActions(
    item: BaseItemDto,
    repo: MediaRepository,
    nav: Navigator,
    playReq: FocusRequester,
    onStep: (Int) -> Unit,
    onDown: () -> Unit,
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
                KeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                else -> false
            }
        },
    ) {
        val resumable = (item.userData?.playbackPositionTicks ?: 0) > 0
        PillButton(
            if (resumable) "Resume" else "Play now",
            { if (item.type == BaseItemKind.SERIES) nav.push(Route.Detail(item.id)) else nav.push(Route.Player(item.id)) },
            icon = Ic.Play, primary = true,
            modifier = Modifier.focusRequester(playReq).onFocusChanged { firstFocused = it.isFocused }.focusMemory("hero/play", initial = true),
        )
        GhostButton("Details", { nav.push(Route.Detail(item.id)) }, modifier = Modifier.focusMemory("hero/details"))
        IconButton(
            if (fav) Ic.Check else Ic.Add,
            if (fav) "In My List" else "Add to My List",
            { FinmaxApp.instance.appScope.launch { runCatching { fav = repo.setFavorite(item.id, !fav) } } },
            active = fav,
            modifier = Modifier.onFocusChanged { lastFocused = it.isFocused }.focusMemory("hero/fav"),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeRow(
    section: HomeSection,
    repo: MediaRepository,
    genreArt: Map<String, String>,
    listState: LazyListState,
    onFocused: (BaseItemDto) -> Unit,
) {
    val nav = LocalNav.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Held as state objects and read only where needed (key handling, each card's preload, the info
    // line), so moving focus doesn't recompose the whole row.
    val focusedIndex = remember { mutableIntStateOf(-1) }
    val rowFocused = remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val genres = section.kind == RowKind.GENRES
    // Poster rows pin the focused card to the row start and widen it (Netflix). Genre tiles and the
    // already-wide Continue Watching / Next Up cards just scroll as far as needed (Prime Video).
    val pinned = section.kind == RowKind.POSTER
    val sizes = remember(section.kind, section.key) { cardSizes(section) }
    val motion = remember { RowMotion() }
    // Where focus lands when it enters the row: the card it last left, else the first. Without this the
    // focus search picks whichever card is geometrically nearest, usually the second.
    val entry = remember { FocusRequester() }
    // The card at the row's start. A row scrolled off screen is recomposed fresh (its focus index is
    // gone) but its scroll position is kept, so the view is the source of truth.
    val startIndex by remember {
        androidx.compose.runtime.derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.offset >= 0 }?.index ?: 0 }
    }
    Column(
        Modifier.onFocusChanged {
            rowFocused.value = it.hasFocus
            if (!it.hasFocus && pinned) scope.launch { motion.close() }
        }.onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent
            val horizontal = k.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || k.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (horizontal && k.action == KeyEvent.ACTION_DOWN) held = k.repeatCount > 0
            // Right past the last card stays put instead of jumping to whatever lies beyond (the top bar).
            k.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && k.action == KeyEvent.ACTION_DOWN && focusedIndex.intValue == section.items.lastIndex
        },
    ) {
        if (!genres) {
            Text(section.title, style = MaterialTheme.typography.titleMedium, color = Fm.Text, modifier = Modifier.padding(start = Fm.SafeH, top = 6.dp))
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides if (pinned) NoAutoScroll else rememberEdgeScroll()) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (genres) 12.dp else 10.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.focusRestorer(entry),
            ) {
                itemsIndexed(section.items, key = { _, it -> it.id }) { i, item ->
                    val isEntry by remember(i) { androidx.compose.runtime.derivedStateOf { i == startIndex } }
                    // Cards left of the focused one (the remembered one, else what the view shows) are dimmed;
                    // keyed off focus, not scroll, so the dim lifts as soon as the card is chosen.
                    val peek by remember(i) {
                        androidx.compose.runtime.derivedStateOf { pinned && i < (focusedIndex.intValue.takeIf { it >= 0 } ?: startIndex) }
                    }
                    val focus = Modifier.focusMemory("${section.key}/${item.id}").then(if (isEntry) Modifier.focusRequester(entry) else Modifier)
                    val focused = {
                        if (item.id != handedOff) handedOff = null
                        focusedIndex.intValue = i
                        onFocused(item)
                        if (pinned) scope.launch { motion.moveTo(i, listState, held) }
                    }
                    if (genres) {
                        GenreTile(
                            item.name ?: "", genreArt[item.name], i,
                            onClick = { nav.push(Route.Genre(item.name ?: "", null)) },
                            onFocused = { focused() },
                            modifier = focus,
                        )
                    } else {
                        HomeCard(
                            item, repo, sizes,
                            openness = { motion.openness(i) },
                            peek = peek,
                            landscapeOnly = section.kind == RowKind.LANDSCAPE,
                            // Load the wide art ahead of the focus so it is there when the card opens.
                            preload = remember(i) {
                                androidx.compose.runtime.derivedStateOf { i == 0 || (focusedIndex.intValue >= 0 && i - focusedIndex.intValue in -1..2) }
                            }.value,
                            onClick = { nav.open(item) },
                            onFocused = { focused() },
                            modifier = focus.playKey(item, nav),
                        )
                    }
                }
            }
        }
        if (section.kind == RowKind.POSTER) RowInfo(section, focusedIndex, rowFocused)
    }
}

/** Netflix-style facts for the focused card, under its row. */
@Composable
private fun RowInfo(section: HomeSection, focusedIndex: androidx.compose.runtime.IntState, rowFocused: androidx.compose.runtime.State<Boolean>) {
    val info = section.items.getOrNull(focusedIndex.intValue)
    // Keeps the last card while focus is between cards (the index is briefly unset then): hiding
    // the block for that moment skipped the old text's fade-out.
    val shown = androidx.compose.runtime.remember { mutableStateOf(info) }
    if (info != null) shown.value = info
    // Opens at full height in one step and only fades in: animating its height would re-lay
    // out every row below on each frame, which this hardware can't do in time.
    androidx.compose.animation.AnimatedVisibility(
        rowFocused.value && shown.value != null,
        enter = fadeIn(tween(450, 60, com.finmax.ui.components.FadeEasing)),
        exit = androidx.compose.animation.ExitTransition.None,
        label = "rowInfo",
    ) {
        shown.value?.let { com.finmax.ui.components.FocusSwap(it, key = { i -> i.id }) { item -> CardInfo(item, section.kind) } }
    }
}

/** Resting and focused card sizes for a row. */
private class CardSizes(val restW: Dp, val restH: Dp, val openW: Dp, val openH: Dp)

private fun cardSizes(section: HomeSection): CardSizes = when (section.kind) {
    RowKind.POSTER -> {
        // Posters widen into landscape art at the same height.
        val h = 236.dp
        CardSizes(h * (2f / 3f), h, h * (16f / 9f), h)
    }
    // Landscape cards (Continue Watching, Next Up) are already wide: one even size, the focus ring marks the card.
    else -> CardSizes(288.dp, 162.dp, 288.dp, 162.dp)
}

/**
 * Netflix-style card. Poster rows show the poster and open into the title's landscape art (with its
 * logo) at the same height; landscape rows show the art and grow. The size change runs on the same
 * timing as the row's glide, so the focused card arrives at the row start fully open.
 */
@Composable
private fun HomeCard(
    item: BaseItemDto,
    repo: MediaRepository,
    size: CardSizes,
    openness: () -> Float,
    /** Cut off at the row's left edge: darkened, like the rows that aren't focused. */
    peek: Boolean,
    landscapeOnly: Boolean,
    preload: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Fixed-size requests: the card resizing must not make Coil reload the art each frame.
    fun request(url: String?) = url?.let { ImageRequest.Builder(context).data(it).size(coil3.size.Size.ORIGINAL).build() }
    val poster = remember(item.id) { if (landscapeOnly) null else request(repo.poster(item)) }
    val wide = remember(item.id) { request(repo.landscape(item, 720) ?: repo.backdrop(item, 720)) }
    // Thumb art usually has the title baked in; plain backdrops and episode stills get the show's logo.
    val thumb = item.type != BaseItemKind.EPISODE && item.imageTags?.containsKey(ImageType.THUMB) == true
    val logo = remember(item.id) { if (thumb) null else request(repo.logo(item, 500)) }
    val wideOnly = landscapeOnly || poster == null
    var focused by remember { mutableStateOf(false) }
    val lit = animateFloatAsState(if (peek) 0.32f else 1f, tween(120), label = "peek")
    // Video replaces the art once the card has held focus a moment. Unwatched episodes get none (spoilers).
    val resumeMs = (item.userData?.playbackPositionTicks ?: 0L) / 10_000
    val video = resumeMs > 0 || item.type != BaseItemKind.EPISODE
    // The preview is armed shortly after focus (not on every card a held key passes) so the video
    // loads during the dwell, and revealed at the end of it.
    val preview = LocalPreview.current
    LaunchedEffect(focused) {
        if (!focused) { preview?.clear(item.id); return@LaunchedEffect }
        if (preview == null || !video || handedOff == item.id) return@LaunchedEffect
        delay(VIDEO_ARM_MS)
        preview.show(item, resumeMs.takeIf { it > 0 })
        delay(VIDEO_DWELL_MS - VIDEO_ARM_MS)
        preview.reveal = true
    }
    androidx.compose.runtime.DisposableEffect(item.id) { onDispose { preview?.clear(item.id) } }
    Surface(
        onClick = {
            preview?.let { if (it.item?.id == item.id && it.reveal) it.carry = true }
            onClick()
        },
        modifier = modifier
            .layout { measurable, _ ->
                // Read only in layout and draw (never in composition), so the motion doesn't recompose cards.
                val t = openness()
                val w = lerp(size.restW, size.openW, t).roundToPx()
                val h = lerp(size.restH, size.openH, t).roundToPx()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .graphicsLayer { alpha = lit.value }
            .onGloballyPositioned { if (focused) preview?.bounds = it.boundsInRoot() }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(HomeCardShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Fm.Surface, focusedContainerColor = Fm.Surface, pressedContainerColor = Fm.Surface),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Fm.Focus), shape = HomeCardShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        if (poster != null) {
            AsyncImage(poster, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (wide != null && (wideOnly || preload)) {
            // The wide art takes over early in the opening so the poster never visibly stretches.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (wideOnly) 1f else (openness() * 2.5f).coerceAtMost(1f) }) {
                AsyncImage(wide, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                if (logo != null) {
                    AsyncImage(
                        logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxHeight(0.26f).fillMaxWidth(0.5f),
                    )
                } else if (landscapeOnly && !thumb) {
                    // Thumb art has the title painted in; anything else needs the name.
                    Text(
                        item.seriesName ?: item.name ?: "", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        } else if (poster == null) {
            Text(
                item.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        if (landscapeOnly) {
            (timeLeft(item) ?: item.episodeLabel())?.let {
                Text(
                    it, style = MaterialTheme.typography.labelMedium, color = Fm.Text,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        item.progress?.let { ProgressStripe(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** What Netflix shows under the focused card: facts and synopsis, or for an episode where you are in it. */
@Composable
private fun CardInfo(item: BaseItemDto, kind: RowKind) {
    Column(Modifier.padding(start = Fm.SafeH, bottom = 10.dp).width(560.dp)) {
        if (kind == RowKind.LANDSCAPE) {
            val episode = item.type == BaseItemKind.EPISODE
            Text((if (episode) item.seriesName else null) ?: item.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (episode) {
                Text(
                    listOfNotNull(item.episodeLabel(), item.name).joinToString("  "),
                    style = MaterialTheme.typography.labelMedium, color = Fm.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val left = timeLeft(item)
            if (left != null) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item.progress?.let { p -> Box(Modifier.width(160.dp).clip(CircleShape)) { ProgressStripe(p) } }
                    Text(left, style = MaterialTheme.typography.labelMedium, color = Fm.Text)
                }
            } else if (!episode) {
                MetaLine(metaParts(item) - listOfNotNull(item.officialRating), color = Fm.TextDim, badge = item.officialRating, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            MetaLine(metaParts(item) - listOfNotNull(item.officialRating), color = Fm.Text, badge = item.officialRating)
            // Always three lines tall, so the next row sits at the same distance whatever the synopsis length.
            Text(
                item.overview ?: "", style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
                minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun timeLeft(item: BaseItemDto): String? {
    val total = item.runTimeTicks ?: return null
    val at = item.userData?.playbackPositionTicks?.takeIf { it > 0 } ?: return null
    return formatRuntime((total - at).coerceAtLeast(0) / 10_000) + " left"
}

@OptIn(ExperimentalTextApi::class)
private val CondensedFont = FontFamily(
    Font(
        R.font.bricolage_grotesque, weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("opsz", 96f), FontVariation.width(75f)),
    ),
)

/** Each tile gets its own lettering, cycling through the cuts of the brand face, like Netflix's genre logos. */
private fun genreStyle(index: Int, long: Boolean): TextStyle {
    val k = if (long) 0.72f else 1f
    return when (index.mod(4)) {
        0 -> TextStyle(fontFamily = CondensedFont, fontWeight = FontWeight.ExtraBold, fontSize = 27.sp * k, lineHeight = 25.sp * k, letterSpacing = 0.5.sp)
        1 -> TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 23.sp * k, lineHeight = 22.sp * k, letterSpacing = (-0.5).sp)
        2 -> TextStyle(fontFamily = WideFont, fontWeight = FontWeight.Bold, fontSize = 14.sp * k, lineHeight = 17.sp * k, letterSpacing = 3.sp)
        else -> TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Medium, fontSize = 26.sp * k, lineHeight = 24.sp * k, letterSpacing = (-1).sp)
    }
}

/**
 * Genre tile: a title's art from that genre, softly blurred and darkened toward the centre so the
 * lettering reads, under a frosted sheen and hairline rim.
 */
@Composable
internal fun GenreTile(name: String, art: String?, index: Int, onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(156.dp).height(86.dp).onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(HomeCardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Fm.SurfaceHigh, contentColor = Fm.Text,
            focusedContainerColor = Fm.SurfaceHigh, focusedContentColor = Fm.Text,
            pressedContainerColor = Fm.SurfaceHigh,
            pressedContentColor = Fm.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), shape = HomeCardShape),
            focusedBorder = Border(BorderStroke(2.dp, Fm.Focus), shape = HomeCardShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        if (art != null) com.finmax.ui.components.BlurredImage(art, 3.dp, Modifier.fillMaxSize(), across = 156.dp)
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Fm.Bg.copy(alpha = 0.3f))
                drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.12f)), radius = size.width * 0.6f))
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), endY = size.height * 0.5f))
            },
        )
        val label = if (index.mod(4) == 3) name.lowercase() else name.uppercase()
        Text(
            label, style = genreStyle(index, name.length > 9), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
        )
    }
}

@Composable
private fun SlideInfo(item: BaseItemDto, repo: MediaRepository, right: Boolean) {
    val logo = remember(item.id) { repo.logo(item) }
    // On the right (mirrored art) everything hugs the right edge.
    val align = if (right) TextAlign.End else TextAlign.Start
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
        Overline(listOfNotNull("Featured", typeLabel(item)).joinToString("  ·  "))
        Spacer(Modifier.height(12.dp))
        if (logo != null) {
            AsyncImage(logo, item.name, contentScale = ContentScale.Fit, alignment = if (right) Alignment.BottomEnd else Alignment.BottomStart, modifier = Modifier.height(140.dp).fillMaxWidth())
        } else {
            BigTitle(item.name ?: "", textAlign = align)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            listOfNotNull(item.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotEmpty() }, item.productionYear?.toString()).joinToString("     "),
            style = MaterialTheme.typography.labelMedium, color = Fm.TextDim, textAlign = align,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            item.overview ?: "", style = MaterialTheme.typography.bodyLarge, color = Fm.TextDim,
            maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = align,
        )
        Spacer(Modifier.height(12.dp))
        RatingLine(item)
    }
}

/** Large expanded title for items without logo art; scales down for long names. */
@Composable
fun BigTitle(name: String, modifier: Modifier = Modifier, maxLines: Int = 2, textAlign: TextAlign? = null) {
    val style = when {
        name.length <= 10 -> MaterialTheme.typography.displayLarge
        name.length <= 22 -> MaterialTheme.typography.displayMedium
        else -> MaterialTheme.typography.displaySmall
    }
    Text(name.uppercase(), style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign, modifier = modifier)
}

/** ★★★½☆ 7.4  │  2h 35m  │  PG-13 */
@Composable
/** [ends]: "ends 20:12", shown after the runtime, joined by a dot. */
fun RatingLine(item: BaseItemDto, modifier: Modifier = Modifier, ends: String? = null) {
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
        runtime?.let {
            parts += {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Fm.Text)
                    if (ends != null) {
                        Box(Modifier.size(3.dp).background(Fm.TextFaint, CircleShape))
                        Text(ends, style = MaterialTheme.typography.labelMedium, color = Fm.TextDim)
                    }
                }
            }
        }
        item.officialRating?.let { parts += { com.finmax.ui.components.Badge(it) } }
        parts.forEachIndexed { i, p ->
            if (i > 0) Hairline(Modifier.height(12.dp), vertical = true, alpha = 0.4f)
            p()
        }
    }
}

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
