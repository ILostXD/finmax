package com.finmax.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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

class DetailState(private val repo: MediaRepository, val id: UUID) : ScreenState() {
    val item = loadState<BaseItemDto>()
    var seasons by mutableStateOf<List<BaseItemDto>>(emptyList())
    var season by mutableStateOf<BaseItemDto?>(null)
    var episodes by mutableStateOf<List<BaseItemDto>>(emptyList())
    var nextUp by mutableStateOf<BaseItemDto?>(null)
    var similar by mutableStateOf<List<BaseItemDto>>(emptyList())
    var children by mutableStateOf<List<BaseItemDto>>(emptyList())
    var favorite by mutableStateOf(false)
    var played by mutableStateOf(false)

    // Pre-play choices from the "Audio & Subtitles" panel.
    var mediaSourceId by mutableStateOf<String?>(null)
    var audioIndex by mutableStateOf<Int?>(null)
    var subtitleIndex by mutableStateOf<Int?>(null)
    val list = LazyListState()
    val episodeRow = LazyListState()

    init { refresh() }

    fun refresh() = load(item) {
        val it = repo.item(id)
        favorite = it.userData?.isFavorite == true
        played = it.userData?.played == true
        scope.launch { similar = runCatching { repo.similar(id) }.getOrDefault(emptyList()) }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Detail(item: BaseItemDto, state: DetailState, repo: MediaRepository) {
    val nav = LocalNav.current
    var tracksOpen by remember { mutableStateOf(false) }
    val logo = remember(item.id) { repo.logo(item) }
    val isSeries = item.type == BaseItemKind.SERIES
    val isPlayable = item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)
    val playTarget = if (isSeries) state.nextUp ?: state.episodes.firstOrNull() else item.takeIf { isPlayable }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        BackdropImage(repo.backdrop(item))
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.finmax.ui.components.MinimalScroll) {
        LazyColumn(state = state.list, contentPadding = PaddingValues(bottom = 60.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "head") {
                Column(Modifier.padding(start = Fm.SafeH, top = 56.dp).width(600.dp)) {
                    if (item.type == BaseItemKind.EPISODE) {
                        Text(item.seriesName ?: "", style = MaterialTheme.typography.titleLarge, color = Fm.TextDim)
                    }
                    if (logo != null && item.type != BaseItemKind.EPISODE) {
                        AsyncImage(logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(120.dp).width(420.dp))
                    } else {
                        Text(item.name ?: "", style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetaLine(metaParts(item))
                        techBadges(item).forEach { Badge(it) }
                    }
                    item.criticRating?.let {
                        Text("Critics $it%", style = MaterialTheme.typography.labelMedium, color = Fm.Gold, modifier = Modifier.padding(top = 6.dp))
                    }
                    item.taglines?.firstOrNull()?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = Fm.Text, modifier = Modifier.padding(top = 12.dp))
                    }
                    Text(
                        item.overview ?: "", style = MaterialTheme.typography.bodyLarge, color = Fm.TextDim,
                        maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                    )
                    val cast = item.people.orEmpty().filter { it.type == PersonKind.ACTOR }.take(4).mapNotNull { it.name }
                    val directors = item.people.orEmpty().filter { it.type == PersonKind.DIRECTOR }.mapNotNull { it.name }
                    if (cast.isNotEmpty()) Text("Starring: ${cast.joinToString()}", style = MaterialTheme.typography.bodyMedium, color = Fm.TextFaint, modifier = Modifier.padding(top = 10.dp))
                    if (directors.isNotEmpty()) Text("Director: ${directors.joinToString()}", style = MaterialTheme.typography.bodyMedium, color = Fm.TextFaint)
                    item.studios?.firstOrNull()?.name?.let { Text("Studio: $it", style = MaterialTheme.typography.bodyMedium, color = Fm.TextFaint) }

                    Spacer(Modifier.height(22.dp))
                    val resumeTicks = playTarget?.userData?.playbackPositionTicks ?: 0
                    Row(
                        Modifier.wrapContentWidth(Alignment.Start, unbounded = true), // may extend past the text column so all actions fit
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                                PillButton("From start", {
                                    nav.push(Route.Player(playTarget.id, true, state.mediaSourceId, state.audioIndex, state.subtitleIndex))
                                }, icon = Icons.Filled.Replay)
                            }
                        }
                        if (isPlayable) PillButton("Audio & Subtitles", { tracksOpen = true }, icon = Icons.Filled.Subtitles)
                        if (isSeries && state.seasons.isNotEmpty()) {
                            PillButton("Episodes", { scope.launch { state.list.animateScrollToItem(1) } }, icon = Icons.Filled.VideoLibrary)
                        }
                        if (item.type == BaseItemKind.EPISODE && item.seriesId != null) {
                            PillButton("Series", { nav.push(Route.Detail(item.seriesId!!)) }, icon = Icons.Filled.Tv)
                        }
                        PillButton(
                            if (state.favorite) "In My List" else "My List", { state.toggleFavorite() },
                            icon = if (state.favorite) Icons.Filled.Check else Icons.Filled.Add,
                            modifier = Modifier.focusMemory("fav"),
                        )
                        if (item.type != BaseItemKind.BOX_SET) {
                            PillButton(if (state.played) "Watched" else "Mark watched", { state.togglePlayed() }, icon = Icons.Filled.DoneAll, modifier = Modifier.focusMemory("played"))
                        }
                    }
                    playTarget?.progress?.let { p ->
                        Row(Modifier.padding(top = 12.dp).width(320.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).clip(CircleShape)) { ProgressStripe(p) }
                            val left = ((playTarget.runTimeTicks ?: 0) - resumeTicks) / 10_000
                            Text("  ${formatRuntime(left)} left", style = MaterialTheme.typography.labelMedium, color = Fm.TextDim)
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
            if (isSeries && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Fm.SafeH),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(state.seasons, key = { it.id }) { s ->
                                PillButton(
                                    s.name ?: "Season ${s.indexNumber}",
                                    { state.selectSeason(s) },
                                    primary = s.id == state.season?.id,
                                    modifier = Modifier.focusMemory("season/${s.id}"),
                                )
                            }
                        }
                        LazyRow(
                            state = state.episodeRow,
                            contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(state.episodes, key = { it.id }) { ep -> EpisodeCard(ep) { nav.push(Route.Detail(ep.id)) } }
                        }
                    }
                }
            }
            if (state.children.isNotEmpty()) {
                item(key = "children") {
                    MediaRow("In this collection", state.children, CardStyle.POSTER, "children", onOpen = { nav.open(it) })
                }
            }
            val cast = item.people.orEmpty().filter { it.type == PersonKind.ACTOR }.take(20)
            if (cast.isNotEmpty()) {
                item(key = "cast") {
                    Column {
                        SectionTitle("Cast")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(cast, key = { it.id.toString() + it.role }) { p ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                                    FocusTile(onClick = {}, shape = CircleShape, modifier = Modifier.size(84.dp)) {
                                        repo.personImage(p.id, p.primaryImageTag)?.let { AsyncImage(it, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                            ?: Text(p.name?.take(1) ?: "", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.Center))
                                    }
                                    Text(p.name ?: "", style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                                    Text(p.role ?: "", style = MaterialTheme.typography.labelSmall, color = Fm.TextFaint, maxLines = 1, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
            if (state.similar.isNotEmpty()) {
                item(key = "similar") { MediaRow("More like this", state.similar, CardStyle.POSTER, "similar", onOpen = { nav.open(it) }) }
            }
            if (isPlayable) item(key = "tech") { TechInfo(item) }
        }
        }
        if (tracksOpen) {
            BackHandler { tracksOpen = false }
            TrackPicker(item, state, onDone = { tracksOpen = false })
        }
    }
}

@Composable
private fun EpisodeCard(ep: BaseItemDto, onClick: () -> Unit) {
    Column(Modifier.width(300.dp)) {
        MediaCard(ep, CardStyle.LANDSCAPE, onClick = onClick, width = 300.dp, overlayText = false, modifier = Modifier.focusMemory("ep/${ep.id}"))
        Text(
            "${ep.indexNumber ?: ""}. ${ep.name ?: ""}", style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            listOfNotNull(ep.runTimeTicks?.let { formatRuntime(it / 10_000) }, ep.premiereDate?.toLocalDate()?.toString()).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = Fm.TextFaint,
        )
        Text(ep.overview ?: "", style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
private fun TechInfo(item: BaseItemDto) {
    val source = item.mediaSources?.firstOrNull() ?: return
    Column(Modifier.padding(start = Fm.SafeH, top = 28.dp, end = 300.dp)) {
        SectionTitle("File", Modifier.padding(start = 0.dp))
        Text(source.path?.substringAfterLast('/')?.substringAfterLast('\\') ?: source.name ?: "", style = MaterialTheme.typography.bodyMedium, color = Fm.TextFaint, modifier = Modifier.padding(top = 6.dp))
        source.mediaStreams.orEmpty().filter { it.type in setOf(MediaStreamType.VIDEO, MediaStreamType.AUDIO, MediaStreamType.SUBTITLE) }.take(14).forEach {
            Text(
                "${it.type.serialName}: ${it.displayTitle ?: it.codec}",
                style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
            )
        }
    }
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
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = Fm.Gold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
