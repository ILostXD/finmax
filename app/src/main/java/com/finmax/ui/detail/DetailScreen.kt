package com.finmax.ui.detail

import com.finmax.ui.components.Ic
import com.finmax.ui.components.friendlyError

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import com.finmax.ui.components.AmbientVideo
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.popoverBlur
import com.finmax.ui.components.NoAutoScroll
import com.finmax.ui.components.rememberEdgeScroll
import com.finmax.ui.components.GhostButton
import com.finmax.ui.components.Hairline
import com.finmax.ui.components.IconButton
import com.finmax.ui.components.Overline
import com.finmax.ui.components.Wordmark
import androidx.compose.foundation.layout.offset
import com.finmax.ui.components.episodeLabel
import com.finmax.ui.home.BigTitle
import com.finmax.ui.home.RatingLine
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.data.MediaRepository
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.components.BackdropImage
import com.finmax.ui.components.Badge
import com.finmax.ui.components.CardStyle
import com.finmax.ui.components.ErrorPanel
import com.finmax.ui.components.FocusTile
import com.finmax.ui.components.Load
import com.finmax.ui.components.MediaCard
import com.finmax.ui.components.MediaRow
import com.finmax.ui.components.MetaLine
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.ProgressStripe
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.SectionTitle
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.loadState
import com.finmax.ui.components.progress
import com.finmax.ui.entryState
import com.finmax.ui.home.formatRuntime
import com.finmax.ui.home.metaParts
import com.finmax.ui.home.open
import com.finmax.ui.library.SidePanel
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.finmax.data.DetailVideo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PersonKind
import java.util.UUID

class DetailState(private val repo: MediaRepository, val id: UUID, autoLoad: Boolean = true) : ScreenState() {
    val item = loadState<BaseItemDto>()
    var seasons by mutableStateOf<List<BaseItemDto>>(emptyList())
    var season by mutableStateOf<BaseItemDto?>(null)
    var episodes by mutableStateOf<List<BaseItemDto>>(emptyList())
    var nextUp by mutableStateOf<BaseItemDto?>(null)
    var similar by mutableStateOf<List<BaseItemDto>>(emptyList())
    var children by mutableStateOf<List<BaseItemDto>>(emptyList())
    var favorite by mutableStateOf(false)
    var played by mutableStateOf(false)
    var trailer by mutableStateOf<BaseItemDto?>(null)
    /** The page already faded into the player once; back here it doesn't do it again. */
    var handedOff = false

    // Pre-play choices from the "Audio & Subtitles" panel.
    var mediaSourceId by mutableStateOf<String?>(null)
    var audioIndex by mutableStateOf<Int?>(null)
    var subtitleIndex by mutableStateOf<Int?>(null)
    val list = LazyListState()
    val episodeRow = LazyListState()

    init { if (autoLoad) refresh() }

    fun refresh() = load(item) {
        val it = repo.item(id)
        favorite = it.userData?.isFavorite == true
        played = it.userData?.played == true
        scope.launch { similar = runCatching { repo.similar(id) }.getOrDefault(emptyList()) }
        scope.launch { trailer = runCatching { repo.localTrailers(id).firstOrNull() }.getOrNull() }
        when (it.type) {
            BaseItemKind.SERIES -> scope.launch {
                nextUp = runCatching { repo.nextUp(1, seriesId = id).firstOrNull() }.getOrNull()
                seasons = runCatching { repo.seasons(id) }.getOrDefault(emptyList())
                val target = seasons.firstOrNull { s -> s.id == nextUp?.seasonId } ?: seasons.firstOrNull()
                target?.let(::selectSeason)
            }
            BaseItemKind.BOX_SET -> scope.launch { children = runCatching { repo.collectionItems(id) }.getOrDefault(emptyList()) }
            BaseItemKind.PLAYLIST -> scope.launch { children = runCatching { repo.playlistItems(id) }.getOrDefault(emptyList()) }
            else -> Unit
        }
        it
    }

    fun selectSeason(s: BaseItemDto) {
        if (season?.id == s.id && episodes.isNotEmpty()) return
        season = s
        scope.launch {
            val seriesId = (item.value as? Load.Ok)?.value?.id ?: return@launch
            episodes = runCatching { repo.episodes(seriesId, s.id) }.getOrDefault(emptyList())
            runCatching { episodeRow.scrollToItem(episodes.indexOfFirst { it.id == nextUp?.id }.coerceAtLeast(0)) }
        }
    }

    fun toggleFavorite() = scope.launch { runCatching { favorite = repo.setFavorite(id, !favorite) } }

    fun togglePlayed() = scope.launch {
        runCatching { played = repo.setPlayed(id, !played) }
        refresh() // progress, next up and season counts all change
    }
}

/** How long the page must be left alone before it fades into the playing film. */
private const val FILM_IDLE_MS = 5_000L

@Composable
fun DetailScreen(id: UUID) {
    val repo = FinmaxApp.instance.repo
    val state = entryState { DetailState(repo, id) }
    when (val l = state.item.value) {
        Load.Loading -> Box(Modifier.fillMaxSize().background(Fm.Bg))
        is Load.Failed -> ErrorPanel(l.message, { state.refresh() }, Modifier.padding(top = 80.dp))
        is Load.Ok -> Detail(l.value, state, repo)
    }
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Detail(item: BaseItemDto, state: DetailState, repo: MediaRepository) {
    val nav = LocalNav.current
    var tracksOpen by remember { mutableStateOf(false) }
    var playlistOpen by remember { mutableStateOf(false) }
    val isSeries = item.type == BaseItemKind.SERIES
    val isPlaylist = item.type == BaseItemKind.PLAYLIST
    val isPlayable = item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)
    val playTarget = when {
        isSeries -> state.nextUp ?: state.episodes.firstOrNull()
        // A playlist carries on with the first title not watched to the end.
        isPlaylist -> state.children.firstOrNull { it.userData?.played != true } ?: state.children.firstOrNull()
        else -> item.takeIf { isPlayable }
    }
    val playlistId = item.id.takeIf { isPlaylist }
    val scope = rememberCoroutineScope()
    val cast = item.people.orEmpty().filter { it.type == PersonKind.ACTOR }.take(24)

    val scrolled by remember {
        androidx.compose.runtime.derivedStateOf { state.list.firstVisibleItemIndex > 0 || state.list.firstVisibleItemScrollOffset > 160 }
    }
    // The page positions itself: the header snaps back to the very top, each section snaps to the
    // top edge when it takes focus (the last ones as far as they go, keeping the bottom margin).
    // Item order of the list below; sections scrolled off screen aren't in the layout info, so
    // positions come from here.
    val keys = buildList {
        add("head")
        if (isSeries && state.seasons.isNotEmpty()) add("seasons")
        if (state.children.isNotEmpty()) add("children")
        if (cast.isNotEmpty() || state.similar.isNotEmpty()) add("more")
    }
    fun Modifier.snapOnFocus(key: String) = onFocusChanged { f ->
        if (f.hasFocus) scope.launch { state.list.animateScrollToItem(keys.indexOf(key).coerceAtLeast(0)) }
    }
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val dim by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 0.45f else 0f, androidx.compose.animation.core.tween(600), label = "dim")
    // Scrolled past the header: the picture melts fully into the glass as it darkens.
    val frost by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, androidx.compose.animation.core.tween(600), label = "frost")

    // Like Netflix, a movie's page plays the film itself (from where you stopped) behind the text;
    // left alone, the text fades away and the player takes the running video over.
    val settings by FinmaxApp.instance.settings.state.collectAsState()
    val film = isPlayable && !state.handedOff && settings.detailVideo == DetailVideo.CLIPS &&
        item.mediaSources?.firstOrNull()?.supportsDirectPlay != false
    val filmFromMs = ((item.userData?.playbackPositionTicks ?: 0) / 10_000 - settings.resumeRewindSec * 1000L).coerceAtLeast(0)
    val leave = remember { androidx.compose.animation.core.Animatable(0f) }
    var lastKey by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    val handOff: (() -> Long) -> Unit = { handOver ->
        scope.launch {
            while (true) {
                // Only once the page is left alone, at the top, with nothing open.
                while (System.currentTimeMillis() - lastKey < FILM_IDLE_MS || scrolled || tracksOpen || playlistOpen) delay(250)
                try {
                    leave.animateTo(1f, androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!isActive) throw e // a key press snapped the page back: wait for quiet again
                }
            }
            state.handedOff = true
            nav.push(Route.Player(item.id, false, state.mediaSourceId, state.audioIndex, state.subtitleIndex, startMs = handOver()))
        }
    }

    Box(
        Modifier.fillMaxSize().background(Fm.Bg).onPreviewKeyEvent {
            lastKey = System.currentTimeMillis()
            if (leave.value > 0f && !state.handedOff) scope.launch { leave.snapTo(0f) }
            false
        },
    ) {
      Box(Modifier.fillMaxSize().popoverBlur(tracksOpen || playlistOpen, 380.dp)) {
        GlassBackdrop(
            repo.backdrop(item), soft = repo.backdrop(item, 320), dim = dim * (1f - leave.value), clear = leave.value,
            live = true, frost = frost * (1f - leave.value),
            sharp = {
                AmbientVideo(
                    item, playing = !scrolled && !tracksOpen && !playlistOpen, fit = leave.value,
                    resumeMs = filmFromMs.takeIf { film },
                    onHandOff = handOff.takeIf { film },
                )
            },
        )
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NoAutoScroll) {
        LazyColumn(
            state = state.list, contentPadding = PaddingValues(bottom = 36.dp),
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - leave.value },
        ) {
            item(key = "head") {
                Column(
                    Modifier.height(screenHeight).fillMaxWidth()
                        .padding(start = Fm.SafeH, end = Fm.SafeH, top = Fm.SafeV, bottom = 24.dp)
                        .onFocusChanged { if (it.hasFocus) scope.launch { state.list.animateScrollToItem(0) } },
                ) {
                    Header(item)
                    Spacer(Modifier.weight(1f))
                    TitleBlock(item, repo, cast.take(3).mapNotNull { it.name })
                    Spacer(Modifier.height(20.dp))
                    Actions(item, state, playTarget, isSeries, isPlayable, playlistId, onTracks = { tracksOpen = true }, onPlaylist = { playlistOpen = true }, onEpisodes = {
                        scope.launch { state.list.animateScrollToItem(1) }
                    })
                    playTarget?.progress?.let { p ->
                        val resumeTicks = playTarget.userData?.playbackPositionTicks ?: 0
                        Row(Modifier.padding(top = 16.dp).width(340.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).clip(CircleShape)) { ProgressStripe(p) }
                            val left = ((playTarget.runTimeTicks ?: 0) - resumeTicks) / 10_000
                            Text("  ${formatRuntime(left)} left", style = MaterialTheme.typography.labelMedium, color = Fm.TextDim)
                        }
                    }
                    Spacer(Modifier.weight(0.6f))
                }
            }
            if (isSeries && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Section(Modifier.snapOnFocus("seasons")) {
                        SectionTitle("Episodes")
                        LazyRow(
                            contentPadding = PaddingValues(start = Fm.SafeH - 10.dp, end = Fm.SafeH, top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(state.seasons, key = { it.id }) { s ->
                                SeasonTab(
                                    s.name ?: "Season ${s.indexNumber}",
                                    selected = s.id == state.season?.id,
                                    onClick = { state.selectSeason(s) },
                                    modifier = Modifier.focusMemory("season/${s.id}"),
                                )
                            }
                        }
                        LazyRow(
                            state = state.episodeRow,
                            contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(Fm.CardGap + 4.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(state.episodes, key = { it.id }) { ep -> EpisodeCard(ep) { nav.push(Route.Detail(ep.id)) } }
                        }
                    }
                }
            }
            if (state.children.isNotEmpty()) {
                item(key = "children") {
                    Section(Modifier.snapOnFocus("children")) {
                        if (isPlaylist) {
                            SectionTitle("In this playlist")
                            // Picking a title plays the playlist from there.
                            MediaRow("", state.children, CardStyle.LANDSCAPE, "children", onOpen = { nav.push(Route.Player(it.id, playlistId = item.id)) })
                        } else {
                            SectionTitle("In this collection")
                            MediaRow("", state.children, CardStyle.POSTER, "children", onOpen = { nav.open(it) }, showTitle = false)
                        }
                    }
                }
            }
            // Cast and More like this share one screen (sized to fit with room below), so moving
            // between them never shifts the page.
            if (cast.isNotEmpty() || state.similar.isNotEmpty()) {
                item(key = "more") {
                    Section(Modifier.snapOnFocus("more").padding(top = 6.dp)) {
                        if (cast.isNotEmpty()) {
                            SectionTitle("Cast")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
                                modifier = Modifier.focusRestorer(),
                            ) {
                                items(cast, key = { it.id.toString() + it.role }) { p ->
                                    PersonCard(p, repo) { nav.push(Route.Person(p.id)) }
                                }
                            }
                        }
                        if (state.similar.isNotEmpty()) {
                            SectionTitle("More like this")
                            MediaRow("", state.similar, CardStyle.POSTER, "similar", onOpen = { nav.open(it) }, showTitle = false, cardWidth = 116.dp)
                        }
                    }
                }
            }
        }
        }
      }
        if (tracksOpen) {
            BackHandler { tracksOpen = false }
            TrackPicker(item, state, onDone = { tracksOpen = false })
        }
        if (playlistOpen) {
            BackHandler { playlistOpen = false }
            PlaylistPicker(item, repo, onDone = { playlistOpen = false })
        }
    }
}

/** A section below the header. Rows inside scroll themselves sideways only as far as needed. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Section(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rememberEdgeScroll()) {
        Column(modifier) { content() }
    }
}

/** Wordmark and a "MOVIES / DRAMA" breadcrumb across the top of the header. */
@Composable
private fun Header(item: BaseItemDto, modifier: Modifier = Modifier) {
    Row(modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Wordmark(20.dp, Modifier.offset(y = -com.finmax.ui.components.LogoLift))
        Spacer(Modifier.width(26.dp))
        val crumbs = listOfNotNull(
            when (item.type) {
                BaseItemKind.MOVIE -> "Movies"
                BaseItemKind.SERIES, BaseItemKind.EPISODE -> "Shows"
                BaseItemKind.BOX_SET -> "Collections"
                else -> null
            },
            item.genres?.firstOrNull(),
        )
        if (crumbs.isNotEmpty()) Overline(crumbs.joinToString("   /   "), color = Fm.TextDim)
    }
}

@Composable
private fun TitleBlock(item: BaseItemDto, repo: MediaRepository, starring: List<String>) {
    val logo = remember(item.id) { if (item.type == BaseItemKind.EPISODE) null else repo.logo(item) }
    Column(Modifier.width(600.dp)) {
        if (item.type == BaseItemKind.EPISODE) {
            Overline(listOfNotNull(item.seriesName, item.episodeLabel()).joinToString("  ·  "))
            Spacer(Modifier.height(8.dp))
        }
        if (logo != null) {
            AsyncImage(logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(104.dp).width(420.dp))
        } else {
            BigTitle(item.name ?: "", maxLines = 2, scale = 1.2f)
        }
        // Capitals leave room under the text that a logo doesn't, so a text title sits closer.
        Spacer(Modifier.height(if (logo != null) 14.dp else 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RatingLine(item, ends = endsAt(item))
            techBadges(item).forEach { Badge(it) }
        }
        item.taglines?.firstOrNull()?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = Fm.Text, modifier = Modifier.padding(top = 12.dp))
        }
        Text(
            item.overview ?: "", style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).width(560.dp),
        )
        Facts(item, starring, Modifier.padding(top = 14.dp))
    }
}

/** "ends 20:12" for anything with a runtime, counting from the resume point. */
private fun endsAt(item: BaseItemDto): String? {
    val runtimeMs = (item.runTimeTicks ?: 0) / 10_000
    if (runtimeMs < 60_000 || item.type == BaseItemKind.SERIES) return null
    val left = runtimeMs - (item.userData?.playbackPositionTicks ?: 0) / 10_000
    return "ends " + java.time.LocalTime.now().plusSeconds(left / 1000)
        .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
}

/** Compact label/value facts that used to live in the side column. */
@Composable
private fun Facts(item: BaseItemDto, starring: List<String>, modifier: Modifier = Modifier) {
    val people = item.people.orEmpty()
    fun names(kind: PersonKind, n: Int = 2) = people.filter { it.type == kind }.mapNotNull { it.name }.distinct().take(n).joinToString(", ").takeIf { it.isNotEmpty() }
    val top = listOfNotNull(
        when (item.type) {
            BaseItemKind.SERIES -> item.productionYear?.let { y ->
                val end = if (item.status == "Continuing") "present" else item.endDate?.year?.takeIf { it != y }?.toString()
                "Years" to listOfNotNull(y.toString(), end).joinToString(" – ")
            }
            BaseItemKind.EPISODE -> item.premiereDate?.let { "Aired" to it.format(longDate) }
            else -> item.premiereDate?.format(longDate)?.let { "Premiere" to it }
        },
        names(PersonKind.DIRECTOR)?.let { "Director" to it },
        names(PersonKind.WRITER)?.let { "Written by" to it },
        item.studios?.firstOrNull()?.name?.let { (if (item.type == BaseItemKind.SERIES) "Network" else "Studio") to it },
    ).take(3)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (top.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                top.forEach { (label, value) ->
                    Column(Modifier.widthIn(max = 200.dp)) {
                        Overline(label)
                        Text(value, style = MaterialTheme.typography.bodySmall, color = Fm.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (starring.isNotEmpty()) {
            Column {
                Overline("Starring")
                Text(starring.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = Fm.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Actions(
    item: BaseItemDto,
    state: DetailState,
    playTarget: BaseItemDto?,
    isSeries: Boolean,
    isPlayable: Boolean,
    playlistId: UUID?,
    onTracks: () -> Unit,
    onPlaylist: () -> Unit,
    onEpisodes: () -> Unit,
) {
    val nav = LocalNav.current
    val resumeTicks = playTarget?.userData?.playbackPositionTicks ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (playTarget != null) {
            val label = buildString {
                append(if (resumeTicks > 0) "Resume" else "Play")
                if (isSeries) playTarget.parentIndexNumber?.let { append(" S$it:E${playTarget.indexNumber}") }
            }
            PillButton(
                label,
                { nav.push(Route.Player(playTarget.id, false, state.mediaSourceId, state.audioIndex, state.subtitleIndex, playlistId = playlistId)) },
                icon = Ic.Play, primary = true,
                modifier = Modifier.focusMemory("play", initial = true),
            )
            if (resumeTicks > 0) {
                GhostButton("From start", {
                    nav.push(Route.Player(playTarget.id, true, state.mediaSourceId, state.audioIndex, state.subtitleIndex, playlistId = playlistId))
                }, icon = Ic.Replay, modifier = Modifier.focusMemory("fromstart"))
            }
        }
        state.trailer?.let { t ->
            GhostButton("Trailer", { nav.push(Route.Player(t.id, fromStart = true)) }, icon = Ic.Play, modifier = Modifier.focusMemory("trailer"))
        }
        if (isSeries && state.seasons.isNotEmpty()) GhostButton("Episodes", onEpisodes, arrow = true)
        if (item.type == BaseItemKind.EPISODE && item.seriesId != null) {
            GhostButton("Series", { nav.push(Route.Detail(item.seriesId!!)) }, arrow = true)
        }
        if (isPlayable) IconButton(Ic.Subtitles, "Audio & Subtitles", onTracks, Modifier.focusMemory("tracks"))
        if (isPlayable) IconButton(Ic.ListPlus, "Add to playlist", onPlaylist, Modifier.focusMemory("playlist"))
        IconButton(
            if (state.favorite) Ic.Check else Ic.Add,
            if (state.favorite) "In My List" else "Add to My List",
            { state.toggleFavorite() },
            Modifier.focusMemory("fav"),
            active = state.favorite,
        )
        if (item.type != BaseItemKind.BOX_SET) {
            IconButton(
                Ic.DoneAll, if (state.played) "Watched" else "Mark watched", { state.togglePlayed() },
                Modifier.focusMemory("played"), active = state.played,
            )
        }
    }
}

private val longDate = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")

@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(com.finmax.ui.components.ButtonShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) Fm.Text else Fm.TextDim,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium)
            Box(
                Modifier.padding(top = 3.dp).size(width = 14.dp, height = 2.dp)
                    .background(if (selected) Fm.Accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
private fun PersonCard(p: org.jellyfin.sdk.model.api.BaseItemPerson, repo: MediaRepository, onClick: () -> Unit) {
    Column(Modifier.width(92.dp)) {
        FocusTile(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            repo.personImage(p.id, p.primaryImageTag)?.let { AsyncImage(it, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Text(p.name?.take(1) ?: "", style = MaterialTheme.typography.displaySmall, color = Fm.TextFaint, modifier = Modifier.align(Alignment.Center))
        }
        Text(p.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(p.role ?: "", style = MaterialTheme.typography.labelMedium, color = Fm.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EpisodeCard(ep: BaseItemDto, onClick: () -> Unit) {
    Column(Modifier.width(290.dp)) {
        MediaCard(ep, CardStyle.LANDSCAPE, onClick = onClick, width = 290.dp, overlayText = false, modifier = Modifier.focusMemory("ep/${ep.id}"))
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("%02d".format(ep.indexNumber ?: 0), style = MaterialTheme.typography.headlineSmall, color = Fm.Accent)
            Text(ep.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(
            listOfNotNull(ep.runTimeTicks?.let { formatRuntime(it / 10_000) }, ep.premiereDate?.format(longDate)).joinToString("  ·  "),
            style = MaterialTheme.typography.labelMedium, color = Fm.TextFaint, modifier = Modifier.padding(top = 2.dp),
        )
        Text(ep.overview ?: "", style = MaterialTheme.typography.bodySmall, color = Fm.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun techBadges(item: BaseItemDto): List<String> {
    val video = item.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
    val audio = item.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault } ?: item.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO }
    return listOfNotNull(
        video?.width?.let { w -> when { w >= 3800 -> "4K"; w >= 1900 -> "HD"; w >= 1200 -> "720p"; else -> "SD" } },
        video?.videoRangeType?.serialName?.takeIf { it != "SDR" && it != "Unknown" },
        audio?.channels?.let { c -> when { c >= 8 -> "7.1"; c >= 6 -> "5.1"; c == 2 -> "Stereo"; else -> null } },
        "CC".takeIf { item.mediaStreams?.any { it.type == MediaStreamType.SUBTITLE } == true },
        item.mediaSourceCount?.takeIf { it > 1 }?.let { "$it versions" },
    )
}

@Composable
private fun TrackPicker(item: BaseItemDto, state: DetailState, onDone: () -> Unit) {
    val sources = item.mediaSources.orEmpty()
    val source = sources.firstOrNull { it.id == state.mediaSourceId } ?: sources.firstOrNull()
    val streams = source?.mediaStreams.orEmpty()
    SidePanel("Audio & Subtitles", width = 380.dp, footer = { PillButton("Done", onDone) }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sources.size > 1) {
                item { Label("Version") }
                items(sources, key = { "v" + it.id }) { s ->
                    com.finmax.ui.components.PopoverItem(s.name ?: s.id ?: "", {
                        state.mediaSourceId = s.id
                        state.audioIndex = null
                        state.subtitleIndex = null
                    }, primary = s.id == source?.id, modifier = Modifier.fillMaxWidth())
                }
            }
            item { Label("Audio") }
            items(streams.filter { it.type == MediaStreamType.AUDIO }, key = { "a" + it.index }) { s ->
                val selected = (state.audioIndex ?: source?.defaultAudioStreamIndex) == s.index
                com.finmax.ui.components.PopoverItem(s.displayTitle ?: s.language ?: "Track ${s.index}", { state.audioIndex = s.index }, primary = selected,
                    modifier = Modifier.fillMaxWidth().focusMemory("pa/${s.index}", initial = selected))
            }
            item { Label("Subtitles") }
            item(key = "soff") {
                com.finmax.ui.components.PopoverItem("Off", { state.subtitleIndex = -1 }, primary = state.subtitleIndex == -1, modifier = Modifier.fillMaxWidth())
            }
            items(streams.filter { it.type == MediaStreamType.SUBTITLE }, key = { "s" + it.index }) { s ->
                val selected = (state.subtitleIndex ?: source?.defaultSubtitleStreamIndex) == s.index
                com.finmax.ui.components.PopoverItem(
                    listOfNotNull(s.displayTitle ?: s.language, "external".takeIf { s.isExternal }).joinToString(" · "),
                    { state.subtitleIndex = s.index }, primary = selected, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Adds the title to one of the user's playlists, or to a new one. */
@Composable
private fun PlaylistPicker(item: BaseItemDto, repo: MediaRepository, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { lists = runCatching { repo.playlists() }.getOrDefault(emptyList()) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { block() }.onSuccess { onDone() }.onFailure { error = friendlyError(it) }
            busy = false
        }
    }
    fun create() { if (name.isNotBlank()) run { repo.createPlaylist(name.trim(), item.id) } }
    SidePanel("Add to playlist", width = 380.dp) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lists.orEmpty(), key = { it.id }) { p ->
                com.finmax.ui.components.PopoverItem(
                    p.name ?: "Playlist", { run { repo.addToPlaylist(p.id, item.id) } },
                    modifier = Modifier.fillMaxWidth().focusMemory("pl/${p.id}", initial = p == lists?.firstOrNull()),
                )
            }
            item { Label("New playlist") }
            item {
                com.finmax.ui.login.FmTextField(
                    name, { name = it }, "Name", Modifier.fillMaxWidth(), requestFocus = lists?.isEmpty() == true, onDone = ::create,
                )
            }
            item { PillButton("Create and add", ::create, primary = name.isNotBlank()) }
            error?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim) } }
        }
    }
}

@Composable
private fun Label(text: String) = Overline(text, Modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp))
