package com.finmax.ui.home

import android.view.KeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.data.MediaRepository
import com.finmax.ui.LocalNav
import com.finmax.ui.Navigator
import com.finmax.ui.Route
import com.finmax.ui.components.BackdropImage
import com.finmax.ui.components.CardStyle
import com.finmax.ui.components.MediaRow
import com.finmax.ui.components.MessagePanel
import com.finmax.ui.components.MetaLine
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.SkeletonRow
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.entryState
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

data class HomeSection(val order: Int, val key: String, val title: String, val items: List<BaseItemDto>, val style: CardStyle) {
    val listState = LazyListState()
}

class HomeState(private val repo: MediaRepository) : ScreenState() {
    val sections = mutableStateListOf<HomeSection>()
    var hero by mutableStateOf<List<BaseItemDto>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var focused by mutableStateOf<BaseItemDto?>(null)
    var heroIndex by mutableStateOf(0)
    val listState = LazyListState()

    init { refresh() }

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
            fun add(order: Int, key: String, title: String, style: CardStyle, block: suspend () -> List<BaseItemDto>) = async {
                val items = runCatching { block() }.getOrDefault(emptyList())
                sections.removeAll { it.key == key }
                if (items.isNotEmpty()) {
                    val at = sections.indexOfFirst { it.order > order }.let { if (it < 0) sections.size else it }
                    sections.add(at, HomeSection(order, key, title, items, style))
                }
                items
            }
            val jobs = buildList {
                if (s.homeContinue) add(add(0, "resume", "Continue Watching", CardStyle.LANDSCAPE) { repo.resume() })
                if (s.homeNextUp) add(add(1, "nextup", "Next Up", CardStyle.LANDSCAPE) { repo.nextUp() })
                if (s.homeLatest) views.filter { it.collectionType != CollectionType.BOXSETS }.forEachIndexed { i, v ->
                    add(add(10 + i, "latest/${v.id}", "Recently Added in ${v.name}", CardStyle.LANDSCAPE) { repo.latest(v.id) })
                }
                if (s.homeFavorites) add(add(30, "fav", "My List", CardStyle.POSTER) {
                    repo.items(types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), filter = com.finmax.data.FilterChoice.FAVORITES, sort = com.finmax.data.SortChoice.ADDED, limit = 30).items
                })
                add(async {
                    runCatching { repo.recommendations() }.getOrDefault(emptyList()).forEachIndexed { i, rec ->
                        val items = rec.items.orEmpty()
                        if (items.isNotEmpty()) {
                            val title = rec.baselineItemName?.let { "Because you watched $it" } ?: "Recommended for you"
                            val order = 40 + i
                            val at = sections.indexOfFirst { it.order > order }.let { if (it < 0) sections.size else it }
                            sections.add(at, HomeSection(order, "rec/$i", title, items, CardStyle.POSTER))
                        }
                    }
                    emptyList<BaseItemDto>()
                })
                if (s.homeCollections) add(add(50, "collections", "Collections", CardStyle.LANDSCAPE) {
                    repo.items(types = listOf(BaseItemKind.BOX_SET), sort = com.finmax.data.SortChoice.NAME, limit = 30).items
                })
                if (s.homeGenres) {
                    val movieView = views.firstOrNull { it.collectionType == CollectionType.MOVIES }
                    if (movieView != null) add(async {
                        runCatching { repo.genres(movieView.id) }.getOrDefault(emptyList()).shuffled().take(3).forEachIndexed { i, g ->
                            val items = runCatching {
                                repo.items(parentId = movieView.id, types = listOf(BaseItemKind.MOVIE), genre = g.name, sort = com.finmax.data.SortChoice.RANDOM, limit = 20).items
                            }.getOrDefault(emptyList())
                            if (items.size >= 4) {
                                val order = 60 + i
                                val at = sections.indexOfFirst { it.order > order }.let { if (it < 0) sections.size else it }
                                sections.add(at, HomeSection(order, "genre/${g.name}", "${g.name} Movies", items, CardStyle.POSTER))
                            }
                        }
                        emptyList<BaseItemDto>()
                    })
                }
                add(add(70, "watched", "Watched Recently", CardStyle.LANDSCAPE) { repo.recentlyWatched() })
                add(add(80, "libraries", "My Libraries", CardStyle.LANDSCAPE) { views })
            }
            // Spotlight: recent movies/shows that have proper widescreen art.
            hero = runCatching {
                repo.items(types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), sort = com.finmax.data.SortChoice.ADDED, limit = 30).items
                    .filter { !it.backdropImageTags.isNullOrEmpty() }.shuffled().take(6)
            }.getOrDefault(emptyList())
            loading = false
            jobs.forEach { it.await() }
            if (sections.isEmpty() && hero.isEmpty()) error = "Your libraries look empty. Add media in Jellyfin, then come back."
        }
    }
}

fun Navigator.open(item: BaseItemDto) = when (item.type) {
    BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> push(Route.Library(item.id, item.name ?: "", item.collectionType))
    else -> push(Route.Detail(item.id))
}

@Composable
fun HomeScreen() {
    val repo = FinmaxApp.instance.repo
    val state = entryState { HomeState(repo) }
    val nav = LocalNav.current
    val hero = state.hero.getOrNull(state.heroIndex % state.hero.size.coerceAtLeast(1))
    var heroFocused by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(state.hero, heroFocused) {
        while (state.hero.size > 1 && !heroFocused) {
            delay(9_000)
            state.heroIndex++
        }
    }

    Box(Modifier.fillMaxSize()) {
        val bgItem = state.focused ?: hero
        Crossfade(bgItem?.id, animationSpec = tween(500), label = "bg") { id ->
            val item = listOfNotNull(state.focused, hero).firstOrNull { it.id == id }
            BackdropImage(item?.let { repo.backdrop(it) }, dim = if (state.focused != null && !heroFocused) 0.35f else 0f)
        }
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.finmax.ui.components.NoAutoScroll) {
        LazyColumn(state = state.listState, contentPadding = PaddingValues(bottom = 400.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "top") { TopBar(Route.Home) }
            if (hero != null) {
                item(key = "hero") {
                    Hero(hero, repo, nav, Modifier.onFocusChanged {
                        heroFocused = it.hasFocus
                        if (it.hasFocus) {
                            state.focused = null
                            // Keep the whole spotlight (and top bar) on screen while its buttons have focus.
                            scope.launch { state.listState.animateScrollToItem(0) }
                        }
                    })
                }
            }
            when {
                state.error != null && state.sections.isEmpty() -> item {
                    MessagePanel("Nothing to show yet", state.error!!, actionLabel = "Try again", onAction = { state.refresh() })
                }
                state.loading -> items(3) { SkeletonRow() }
            }
            items(state.sections, key = { it.key }) { section ->
                Column {
                    MediaRow(
                        title = section.title,
                        items = section.items,
                        style = section.style,
                        rowKey = section.key,
                        state = section.listState,
                        onOpen = { nav.open(it) },
                        onFocused = {
                            state.focused = it
                            // Snap the focused row just under the top edge; the backdrop shows the focused title above.
                            val idx = state.listState.layoutInfo.visibleItemsInfo.firstOrNull { i -> i.key == section.key }?.index
                                ?: (2 + state.sections.indexOf(section))
                            scope.launch { state.listState.animateScrollToItem(idx, scrollOffset = -140) }
                        },
                        initialFocus = hero == null && section == state.sections.first(),
                    )
                    val f = state.focused
                    if (f != null && section.items.any { it.id == f.id } && section.key != "libraries") InfoStrip(f)
                }
            }
        }
        }
    }
}

@Composable
private fun Hero(item: BaseItemDto, repo: MediaRepository, nav: Navigator, modifier: Modifier) {
    val logo = remember(item.id) { repo.logo(item) }
    var fav by remember(item.id) { mutableStateOf(item.userData?.isFavorite == true) }
    val app = FinmaxApp.instance
    Column(modifier.fillMaxWidth().height(380.dp).padding(start = Fm.SafeH, top = 24.dp), verticalArrangement = Arrangement.Bottom) {
        if (logo != null) {
            AsyncImage(logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(110.dp).width(380.dp))
        } else {
            Text(item.name ?: "", style = MaterialTheme.typography.displayMedium, maxLines = 2, modifier = Modifier.width(560.dp))
        }
        Spacer(Modifier.height(12.dp))
        MetaLine(metaParts(item))
        Spacer(Modifier.height(8.dp))
        Text(
            item.overview ?: "", style = MaterialTheme.typography.bodyLarge, color = Fm.TextDim,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(520.dp),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val resumable = (item.userData?.playbackPositionTicks ?: 0) > 0
            PillButton(
                if (resumable) "Resume" else "Play",
                { if (item.type == BaseItemKind.SERIES) nav.push(Route.Detail(item.id)) else nav.push(Route.Player(item.id)) },
                icon = Icons.Filled.PlayArrow, primary = true,
                modifier = Modifier.focusMemory("hero/play", initial = true),
            )
            PillButton("More Info", { nav.push(Route.Detail(item.id)) }, icon = Icons.Filled.Info)
            PillButton(
                if (fav) "In My List" else "My List",
                { app.appScope.launch { runCatching { fav = repo.setFavorite(item.id, !fav) } } },
                icon = if (fav) Icons.Filled.Check else Icons.Filled.Add,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoStrip(item: BaseItemDto) {
    Column(Modifier.padding(start = Fm.SafeH, end = 320.dp, bottom = 6.dp)) {
        Text(
            listOfNotNull(item.seriesName, item.name).distinct().joinToString(" · "),
            style = MaterialTheme.typography.titleMedium, maxLines = 1,
        )
        MetaLine(metaParts(item))
        item.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
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
