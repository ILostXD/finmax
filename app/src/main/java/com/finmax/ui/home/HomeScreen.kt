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
import com.finmax.ui.components.BackdropScrim
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

    /** Position in the vertical carousel; grows without bound, read modulo [featured] size. */
    var index by mutableIntStateOf(0)
    /** +1 when the carousel moved down (next), -1 when it moved up. Drives the slide direction. */
    var direction by mutableIntStateOf(1)

    /** The recommendations panel is open and the artwork has slid aside. */
    var exploring by mutableStateOf(false)
    var focused by mutableStateOf<BaseItemDto?>(null)
    var focusedSection by mutableStateOf<String?>(null)
    val panel = LazyListState()

    val slide: BaseItemDto? get() = featured.takeIf { it.isNotEmpty() }?.let { it[index.mod(it.size)] }

    init { if (autoLoad) refresh() }

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

private val PanelWidth = 404.dp
private const val AUTO_ADVANCE_MS = 10_000L

/**
 * Home: a vertical carousel of random titles ("the stage"). Up/Down moves through it; Right slides
 * the artwork aside and opens the recommendations panel, a vertical list of everything Home used
 * to show as rows. Left or Back closes the panel. Back on the stage jumps to the top bar.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen() {
    val repo = FinmaxApp.instance.repo
    HomeContent(entryState { HomeState(repo) }, repo)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HomeContent(state: HomeState, repo: MediaRepository) {
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val playReq = remember { FocusRequester() }
    val panelReq = remember { FocusRequester() }
    val topBarReq = remember { FocusRequester() }
    var topBarFocused by remember { mutableStateOf(false) }

    val explore by animateFloatAsState(if (state.exploring) 1f else 0f, tween(620, easing = FastOutSlowInEasing), label = "explore")
    val slide = state.slide
    val art = if (state.exploring) state.focused ?: slide else slide

    // Warm the image cache so carousel slides arrive with their artwork.
    LaunchedEffect(state.featured) {
        val loader = SingletonImageLoader.get(context)
        state.featured.forEach { f -> repo.backdrop(f)?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) } }
    }
    LaunchedEffect(state.index, state.exploring, state.featured) {
        if (state.exploring || state.featured.size < 2) return@LaunchedEffect
        delay(AUTO_ADVANCE_MS)
        state.step(1)
    }
    // Move focus across when the panel opens or closes (the other side has just been composed).
    LaunchedEffect(state.exploring) {
        if (entry.pendingRestore) return@LaunchedEffect // focusMemory is putting focus back
        repeat(15) {
            delay(40)
            val ok = runCatching { if (state.exploring) panelReq.requestFocus() else playReq.requestFocus() }.getOrDefault(false)
            if (ok) return@LaunchedEffect
        }
    }
    BackHandler(enabled = state.exploring) { state.exploring = false }
    BackHandler(enabled = !state.exploring && !topBarFocused) { runCatching { topBarReq.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Fm.Bg)) {
        // ---- Artwork: slides vertically through the carousel, sideways when the panel opens ----
        val shift = with(density) { 150.dp.toPx() }
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -explore * shift }) {
            if (art == null) {
                BrandBackdrop()
            } else {
                AnimatedContent(
                    targetState = art,
                    contentKey = { it.id },
                    transitionSpec = {
                        if (state.exploring) {
                            fadeIn(tween(450)) togetherWith fadeOut(tween(450))
                        } else {
                            verticalSlide(state.direction)
                        }
                    },
                    label = "art",
                ) { item ->
                    AsyncImage(
                        model = repo.backdrop(item), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // Stage scrim (text on the right) gives way to the explore scrim (text bottom-left, panel right).
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - explore }) { BackdropScrim(textSide = Alignment.End) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = explore }) { BackdropScrim(textSide = Alignment.Start) }

        // ---- Stage ----
        AnimatedVisibility(!state.exploring, enter = fadeIn(tween(400, 200)), exit = fadeOut(tween(250))) {
            Box(Modifier.fillMaxSize()) {
                TopBar(
                    Route.Home,
                    Modifier.focusRequester(topBarReq).onFocusChanged { topBarFocused = it.hasFocus },
                )
                when {
                    slide != null -> {
                        CarouselIndex(state.index, state.featured.size, Modifier.align(Alignment.CenterStart).padding(start = Fm.SafeH))
                        Column(Modifier.align(Alignment.TopEnd).padding(end = Fm.SafeH, top = 88.dp).width(400.dp)) {
                            // Only the words travel with the carousel; the buttons stay put so focus never drops.
                            AnimatedContent(
                                targetState = slide,
                                contentKey = { it.id },
                                transitionSpec = { verticalSlide(state.direction, delayMs = 90) },
                                label = "stage",
                                modifier = Modifier.height(270.dp),
                            ) { item -> SlideInfo(item, repo) }
                            Spacer(Modifier.height(20.dp))
                            StageActions(
                                item = slide,
                                repo = repo,
                                nav = nav,
                                playReq = playReq,
                                onStep = { state.step(it) },
                                onAtTop = { state.index.mod(state.featured.size) == 0 },
                                onExplore = { state.exploring = true },
                            )
                        }
                        state.sections.firstOrNull()?.let { PanelPeek(it, repo, Modifier.align(Alignment.BottomEnd)) }
                    }
                    state.error != null -> MessagePanel(
                        "Nothing to show yet", state.error!!, Modifier.align(Alignment.Center),
                        actionLabel = "Try again", onAction = { state.refresh() },
                    )
                    !state.loading && state.sections.isNotEmpty() -> Column(
                        Modifier.align(Alignment.CenterEnd).padding(end = Fm.SafeH).width(360.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Your library", style = MaterialTheme.typography.displaySmall)
                        Text("Nothing with widescreen art to feature yet.", color = Fm.TextDim, style = MaterialTheme.typography.bodyLarge)
                        PillButton(
                            "Browse", { state.exploring = true }, icon = Icons.AutoMirrored.Filled.ArrowForward, primary = true,
                            modifier = Modifier.focusRequester(playReq).focusMemory("hero/play", initial = true),
                        )
                    }
                }
            }
        }

        // ---- Explore: focused title on the left, recommendations panel on the right ----
        AnimatedVisibility(state.exploring, enter = fadeIn(tween(400, 250)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize()) {
                Wordmark(20.dp, Modifier.padding(start = Fm.SafeH, top = Fm.SafeV + 9.dp))
                state.focused?.let { f ->
                    AnimatedContent(
                        targetState = f,
                        contentKey = { it.id },
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                        label = "focused",
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = Fm.SafeH, bottom = 44.dp, end = PanelWidth + 40.dp),
                    ) { FocusedInfo(it, state.focusedSection, repo) }
                }
            }
        }
        val panelOffset by animateDpAsState(if (state.exploring) 0.dp else PanelWidth + 24.dp, tween(620, easing = FastOutSlowInEasing), label = "panel")
        if (explore > 0.01f) {
            RecommendationPanel(
                state = state,
                repo = repo,
                nav = nav,
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = panelOffset).focusRequester(panelReq),
                onClose = { state.exploring = false },
            )
        }
    }
}

private fun verticalSlide(direction: Int, delayMs: Int = 0): ContentTransform =
    (slideInVertically(tween(700, delayMs, FastOutSlowInEasing)) { h -> h * direction / 3 } + fadeIn(tween(600, delayMs))) togetherWith
        (slideOutVertically(tween(600, easing = FastOutSlowInEasing)) { h -> -h * direction / 3 } + fadeOut(tween(450)))

/** "01 ┃ 08" with a dot per slide, like the section index running down the reference art. */
@Composable
private fun CarouselIndex(index: Int, count: Int, modifier: Modifier = Modifier) {
    if (count < 2) return
    val current = index.mod(count)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("%02d".format(current + 1), style = MaterialTheme.typography.headlineSmall, color = Fm.Text)
        Hairline(Modifier.height(18.dp), vertical = true, alpha = 0.35f)
        repeat(count) { i ->
            val h by animateDpAsState(if (i == current) 18.dp else 5.dp, tween(400), label = "dot")
            Box(
                Modifier.width(if (i == current) 3.dp else 5.dp).height(h).clip(CircleShape)
                    .background(if (i == current) Fm.Accent else Fm.Parchment.copy(alpha = 0.35f)),
            )
        }
        Hairline(Modifier.height(18.dp), vertical = true, alpha = 0.35f)
        Text("%02d".format(count), style = MaterialTheme.typography.labelMedium, color = Fm.TextFaint)
    }
}

@Composable
private fun SlideInfo(item: BaseItemDto, repo: MediaRepository) {
    val logo = remember(item.id) { repo.logo(item) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Overline(listOfNotNull("Featured", typeLabel(item)).joinToString("  ·  "))
        Spacer(Modifier.height(10.dp))
        if (logo != null) {
            AsyncImage(logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(104.dp).fillMaxWidth())
        } else {
            BigTitle(item.name ?: "")
        }
        Spacer(Modifier.height(14.dp))
        Text(
            listOfNotNull(item.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotEmpty() }, item.productionYear?.toString()).joinToString("     "),
            style = MaterialTheme.typography.labelMedium, color = Fm.TextDim,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            item.overview ?: "", style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        RatingLine(item)
    }
}

/**
 * Play / Details / My List for the current slide. Down and Up move the carousel; Up on the first
 * slide reaches the top bar; Right from the last button opens the recommendations panel.
 */
@Composable
private fun StageActions(
    item: BaseItemDto,
    repo: MediaRepository,
    nav: Navigator,
    playReq: FocusRequester,
    onStep: (Int) -> Unit,
    onAtTop: () -> Boolean,
    onExplore: () -> Unit,
) {
    var fav by remember(item.id) { mutableStateOf(item.userData?.isFavorite == true) }
    var lastButtonFocused by remember { mutableStateOf(false) }
    val app = FinmaxApp.instance
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            when (e.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { onStep(1); true }
                KeyEvent.KEYCODE_DPAD_UP -> if (onAtTop()) false else { onStep(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (lastButtonFocused) { onExplore(); true } else false
                else -> false
            }
        },
    ) {
        val resumable = (item.userData?.playbackPositionTicks ?: 0) > 0
        PillButton(
            if (resumable) "Resume" else "Play now",
            { if (item.type == BaseItemKind.SERIES) nav.push(Route.Detail(item.id)) else nav.push(Route.Player(item.id)) },
            icon = Icons.Filled.PlayArrow, primary = true,
            modifier = Modifier.focusRequester(playReq).focusMemory("hero/play", initial = true),
        )
        GhostButton("Details", { nav.push(Route.Detail(item.id)) }, modifier = Modifier.focusMemory("hero/details"))
        IconButton(
            if (fav) Icons.Filled.Check else Icons.Filled.Add,
            if (fav) "In My List" else "Add to My List",
            { app.appScope.launch { runCatching { fav = repo.setFavorite(item.id, !fav) } } },
            active = fav,
            modifier = Modifier.onFocusChanged { lastButtonFocused = it.isFocused }.focusMemory("hero/fav"),
        )
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
                        Icon(icon, null, Modifier.size(13.dp), tint = Fm.Parchment)
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
@Composable
private fun PanelPeek(section: HomeSection, repo: MediaRepository, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = Fm.SafeH).width(400.dp).offset(y = 46.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(section.title, style = MaterialTheme.typography.titleSmall, color = Fm.Text)
            Spacer(Modifier.width(10.dp))
            Overline("Press ▶", color = Fm.TextFaint)
        }
        Hairline(Modifier.padding(top = 8.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            section.items.take(2).forEachIndexed { i, it ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        repo.landscape(it, 320), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(132.dp).aspectRatio(16f / 9f).clip(CardShape).graphicsLayer { alpha = 0.85f },
                    )
                    Text("%02d".format(i + 1), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Normal, color = Fm.TextDim)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RecommendationPanel(
    state: HomeState,
    repo: MediaRepository,
    nav: Navigator,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    Box(
        Modifier.then(modifier).width(PanelWidth).fillMaxHeight()
            .background(Brush.horizontalGradient(0f to Fm.Bg.copy(alpha = 0.55f), 0.12f to Fm.Bg.copy(alpha = 0.9f), 1f to Fm.Bg.copy(alpha = 0.96f))),
    ) {
        Hairline(Modifier.align(Alignment.CenterStart), vertical = true, alpha = 0.14f)
        LazyColumn(
            state = state.panel,
            contentPadding = PaddingValues(start = 22.dp, end = Fm.SafeH - 8.dp, top = Fm.SafeV + 8.dp, bottom = 180.dp),
            modifier = Modifier.fillMaxSize().focusRestorer().onPreviewKeyEvent { e ->
                val left = e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                if (left && e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onClose()
                left
            },
        ) {
            state.sections.forEachIndexed { si, section ->
                item(key = "h/${section.key}") {
                    Column(Modifier.padding(top = if (si == 0) 0.dp else 26.dp, bottom = 8.dp, start = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("%02d".format(si + 1), style = MaterialTheme.typography.labelSmall, color = Fm.Accent)
                            Text(section.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Hairline(Modifier.padding(top = 10.dp))
                    }
                }
                itemsIndexed(section.items, key = { _, it -> "${section.key}/${it.id}" }) { i, item ->
                    PanelRow(
                        item, i + 1, repo,
                        onClick = { nav.open(item) },
                        onFocused = {
                            state.focused = item
                            state.focusedSection = section.title
                        },
                        modifier = Modifier.focusMemory("panel/${section.key}/${item.id}").playKey(item, nav),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelRow(
    item: BaseItemDto,
    number: Int,
    repo: MediaRepository,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val url = remember(item.id) { repo.landscape(item, 400) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Fm.Text,
            focusedContainerColor = Fm.Parchment.copy(alpha = 0.09f), focusedContentColor = Fm.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.width(150.dp).aspectRatio(16f / 9f).clip(CardShape).background(Fm.Surface)
                    .then(if (focused) Modifier.border(2.dp, Fm.Focus, CardShape) else Modifier),
            ) {
                if (url != null) AsyncImage(url, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (focused && item.type != BaseItemKind.COLLECTION_FOLDER && item.type != BaseItemKind.USER_VIEW) {
                    Box(
                        Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Fm.Bg.copy(alpha = 0.55f))
                            .border(1.dp, Fm.Parchment.copy(alpha = 0.7f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp), tint = Fm.Parchment) }
                }
                item.progress?.let { ProgressStripe(it, Modifier.align(Alignment.BottomCenter)) }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "%02d".format(number), fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge, color = if (focused) Fm.Accent else Fm.TextFaint,
                )
                Text(
                    if (item.type == BaseItemKind.EPISODE) item.seriesName ?: item.name ?: "" else item.name ?: "",
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(item.episodeLabel(), item.productionYear?.toString()?.takeIf { item.type != BaseItemKind.EPISODE })
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Fm.TextFaint, maxLines = 1)
            }
        }
    }
}

/** The focused recommendation, written over its own artwork while the panel is open. */
@Composable
private fun FocusedInfo(item: BaseItemDto, section: String?, repo: MediaRepository) {
    val logo = remember(item.id) { if (item.type == BaseItemKind.EPISODE) null else repo.logo(item) }
    Column(Modifier.width(440.dp)) {
        section?.let { Overline(it) }
        Spacer(Modifier.height(10.dp))
        if (item.type == BaseItemKind.EPISODE) {
            Text(item.seriesName ?: "", style = MaterialTheme.typography.titleMedium, color = Fm.TextDim)
        }
        if (logo != null) {
            AsyncImage(logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(84.dp).width(340.dp))
        } else {
            Text(
                (item.name ?: "").uppercase(), style = MaterialTheme.typography.displaySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        MetaLine(metaParts(item))
        item.overview?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item.progress?.let { p ->
            Box(Modifier.padding(top = 12.dp).width(220.dp).clip(CircleShape)) { ProgressStripe(p) }
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
