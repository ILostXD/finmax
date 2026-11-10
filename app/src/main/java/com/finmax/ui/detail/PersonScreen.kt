package com.finmax.ui.detail

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.data.MediaRepository
import com.finmax.ui.LocalNav
import com.finmax.ui.components.CardStyle
import com.finmax.ui.components.ErrorPanel
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.Load
import com.finmax.ui.components.loadState
import com.finmax.ui.components.MediaCard
import com.finmax.ui.components.Overline
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.Wordmark
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.holdUpStaysInside
import com.finmax.ui.entryState
import com.finmax.ui.home.open
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

private class PersonState(private val repo: MediaRepository, val id: UUID) : ScreenState() {
    val person = loadState<BaseItemDto>()
    var titles by mutableStateOf<List<BaseItemDto>>(emptyList())
    var focused by mutableStateOf<BaseItemDto?>(null)

    init {
        load(person) { repo.item(id) }
        scope.launch {
            // Newest first: what they're doing lately leads.
            titles = runCatching {
                repo.items(types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), personIds = listOf(id), limit = 200).items
            }.getOrDefault(emptyList()).sortedByDescending { it.premiereDate ?: it.dateCreated }
        }
    }
}

private val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")

/**
 * A person's page: photo, life dates and biography on the left; everything of theirs in the
 * library on the right, over the focused title's art melted into glass.
 */
@Composable
fun PersonScreen(id: UUID) {
    val repo = FinmaxApp.instance.repo
    val state = entryState("person/$id") { PersonState(repo, id) }
    val nav = LocalNav.current
    val person = (state.person.value as? Load.Ok<BaseItemDto>)?.value
    (state.person.value as? Load.Failed)?.let { ErrorPanel(it.message, { }); return }

    Box(Modifier.fillMaxSize().background(Fm.Bg)) {
        val art = state.focused ?: state.titles.firstOrNull()
        Crossfade(art?.id, animationSpec = tween(500), label = "art") { artId ->
            val a = art?.takeIf { it.id == artId }
            GlassBackdrop(a?.let { repo.backdrop(it, 1280) }, soft = a?.let { repo.backdrop(it, 320) }, live = true, frost = 1f, dim = 0.3f)
        }
        Row(Modifier.fillMaxSize().padding(start = Fm.SafeH, top = Fm.SafeV)) {
            Column(Modifier.width(280.dp).fillMaxHeight().padding(bottom = Fm.SafeV)) {
                Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Wordmark(20.dp, Modifier.padding(bottom = com.finmax.ui.components.LogoLift))
                }
                Spacer(Modifier.height(22.dp))
                Box(Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Fm.Surface)) {
                    person?.let { p ->
                        repo.personImage(p.id, p.imageTags?.get(org.jellyfin.sdk.model.api.ImageType.PRIMARY), 500)?.let {
                            AsyncImage(it, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(person?.name ?: "", style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                person?.premiereDate?.let { born ->
                    val place = person.productionLocations?.firstOrNull()
                    val died = person.endDate
                    val age = java.time.Period.between(born.toLocalDate(), (died ?: java.time.LocalDateTime.now()).toLocalDate()).years
                    Spacer(Modifier.height(8.dp))
                    Overline(if (died != null) "Lived" else "Born")
                    Text(
                        listOfNotNull(
                            born.format(dateFormat) + (died?.let { " – " + it.format(dateFormat) } ?: ""),
                            "age $age",
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = Fm.Text,
                    )
                    place?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fm.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                person?.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, color = Fm.TextDim, maxLines = 7, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                contentPadding = PaddingValues(start = 36.dp, end = Fm.SafeH, top = 4.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f).holdUpStaysInside(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(bottom = 2.dp)) {
                        Overline(if (state.titles.isEmpty()) "In your library" else "In your library · ${state.titles.size}")
                        com.finmax.ui.components.FocusSwap(state.focused, key = { it?.id }) { focused ->
                            Text(
                                focused?.let { f -> listOfNotNull(f.name, f.productionYear?.toString()).joinToString("  ·  ") } ?: "Movies & shows",
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
                itemsIndexed(state.titles, key = { _, t -> t.id }) { i, t ->
                    MediaCard(
                        t, CardStyle.POSTER, onClick = { nav.open(t) }, width = 118.dp,
                        onFocused = { state.focused = t },
                        modifier = Modifier.focusMemory("p/${t.id}", initial = i == 0),
                    )
                }
            }
        }
    }
}
