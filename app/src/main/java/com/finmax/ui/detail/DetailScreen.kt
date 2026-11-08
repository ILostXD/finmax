package com.finmax.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import com.finmax.ui.components.AmbientVideo
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.NoAutoScroll
import com.finmax.ui.components.MinimalScroll
import com.finmax.ui.components.GhostButton
import com.finmax.ui.components.Hairline
import com.finmax.ui.components.IconButton
import com.finmax.ui.components.Overline
import com.finmax.ui.components.Wordmark
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
    val isSeries = item.type == BaseItemKind.SERIES
    val isPlayable = item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)
    val playTarget = if (isSeries) state.nextUp ?: state.episodes.firstOrNull() else item.takeIf { isPlayable }
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
        if (cast.isNotEmpty()) add("cast")
        if (state.similar.isNotEmpty()) add("similar")
    }
    fun Modifier.snapOnFocus(key: String) = onFocusChanged { f ->
        if (f.hasFocus) scope.launch { state.list.animateScrollToItem(keys.indexOf(key).coerceAtLeast(0)) }
    }
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val dim by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 0.55f else 0f, androidx.compose.animation.core.tween(500), label = "dim")

    Box(Modifier.fillMaxSize().background(Fm.Bg)) {
        GlassBackdrop(
            repo.backdrop(item), soft = repo.backdrop(item, 320), dim = dim,
            sharp = { AmbientVideo(item, playing = !scrolled && !tracksOpen) },
        )
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NoAutoScroll) {
        LazyColumn(state = state.list, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
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
                    Actions(item, state, playTarget, isSeries, isPlayable, onTracks = { tracksOpen = true }, onEpisodes = {
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
                        SectionTitle("In this collection")
                        MediaRow("", state.children, CardStyle.POSTER, "children", onOpen = { nav.open(it) }, showTitle = false)
                    }
                }
            }
            if (cast.isNotEmpty()) {
                item(key = "cast") {
                    Section(Modifier.snapOnFocus("cast")) {
                        SectionTitle("Cast")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(cast, key = { it.id.toString() + it.role }) { p -> PersonCard(p, repo) }
                        }
                    }
                }
            }
            if (state.similar.isNotEmpty()) {
                item(key = "similar") {
                    Section(Modifier.snapOnFocus("similar")) {
                        SectionTitle("More like this")
                        MediaRow("", state.similar, CardStyle.POSTER, "similar", onOpen = { nav.open(it) }, showTitle = false)
                    }
                }
            }
        }
        }
        if (tracksOpen) {
            BackHandler { tracksOpen = false }
            TrackPicker(item, state, onDone = { tracksOpen = false })
        }
    }
}

/** A section below the header. Rows inside scroll themselves sideways only as far as needed. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Section(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalScroll) {
        Column(modifier) { content() }
    }
}

/** Wordmark and a "MOVIES / DRAMA" breadcrumb across the top of the header. */
@Composable
private fun Header(item: BaseItemDto, modifier: Modifier = Modifier) {
    Row(modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Wordmark(20.dp)
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
            BigTitle(item.name ?: "", maxLines = 2)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RatingLine(item)
            endsAt(item)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Fm.TextDim) }
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
    onTracks: () -> Unit,
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
                { nav.push(Route.Player(playTarget.id, false, state.mediaSourceId, state.audioIndex, state.subtitleIndex)) },
                icon = Icons.Filled.PlayArrow, primary = true,
                modifier = Modifier.focusMemory("play", initial = true),
            )
            if (resumeTicks > 0) {
                GhostButton("From start", {
                    nav.push(Route.Player(playTarget.id, true, state.mediaSourceId, state.audioIndex, state.subtitleIndex))
                }, icon = Icons.Filled.Replay, modifier = Modifier.focusMemory("fromstart"))
            }
        }
        state.trailer?.let { t ->
            GhostButton("Trailer", { nav.push(Route.Player(t.id, fromStart = true)) }, icon = Icons.Filled.PlayArrow, modifier = Modifier.focusMemory("trailer"))
        }
        if (isSeries && state.seasons.isNotEmpty()) GhostButton("Episodes", onEpisodes, arrow = true)
        if (item.type == BaseItemKind.EPISODE && item.seriesId != null) {
            GhostButton("Series", { nav.push(Route.Detail(item.seriesId!!)) }, arrow = true)
        }
        if (isPlayable) IconButton(Icons.Filled.Subtitles, "Audio & Subtitles", onTracks, Modifier.focusMemory("tracks"))
        IconButton(
            if (state.favorite) Icons.Filled.Check else Icons.Filled.Add,
            if (state.favorite) "In My List" else "Add to My List",
            { state.toggleFavorite() },
            Modifier.focusMemory("fav"),
            active = state.favorite,
        )
        if (item.type != BaseItemKind.BOX_SET) {
            IconButton(
                Icons.Filled.DoneAll, if (state.played) "Watched" else "Mark watched", { state.togglePlayed() },
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
private fun PersonCard(p: org.jellyfin.sdk.model.api.BaseItemPerson, repo: MediaRepository) {
    Column(Modifier.width(108.dp)) {
        FocusTile(onClick = {}, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
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
    SidePanel("Audio & Subtitles", width = 440.dp) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sources.size > 1) {
                item { Label("Version") }
                items(sources, key = { "v" + it.id }) { s ->
                    PillButton(s.name ?: s.id ?: "", {
                        state.mediaSourceId = s.id
                        state.audioIndex = null
                        state.subtitleIndex = null
                    }, primary = s.id == source?.id, modifier = Modifier.fillMaxWidth())
                }
            }
            item { Label("Audio") }
            items(streams.filter { it.type == MediaStreamType.AUDIO }, key = { "a" + it.index }) { s ->
                val selected = (state.audioIndex ?: source?.defaultAudioStreamIndex) == s.index
                PillButton(s.displayTitle ?: s.language ?: "Track ${s.index}", { state.audioIndex = s.index }, primary = selected,
                    modifier = Modifier.fillMaxWidth().focusMemory("pa/${s.index}", initial = selected))
            }
            item { Label("Subtitles") }
            item(key = "soff") {
                PillButton("Off", { state.subtitleIndex = -1 }, primary = state.subtitleIndex == -1, modifier = Modifier.fillMaxWidth())
            }
            items(streams.filter { it.type == MediaStreamType.SUBTITLE }, key = { "s" + it.index }) { s ->
                val selected = (state.subtitleIndex ?: source?.defaultSubtitleStreamIndex) == s.index
                PillButton(
                    listOfNotNull(s.displayTitle ?: s.language, "external".takeIf { s.isExternal }).joinToString(" · "),
                    { state.subtitleIndex = s.index }, primary = selected, modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Spacer(Modifier.height(8.dp)); PillButton("Done", onDone) }
        }
    }
}

@Composable
private fun Label(text: String) = Overline(text, Modifier.padding(top = 12.dp, bottom = 2.dp))
