package com.finmax.ui.discover

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
import com.finmax.data.SeerrItem
import com.finmax.data.SeerrPerson
import com.finmax.data.SeerrPersonInfo
import com.finmax.ui.LocalNav
import com.finmax.ui.components.GlassBackdrop
import com.finmax.ui.components.Overline
import com.finmax.ui.components.ScreenState
import com.finmax.ui.components.Wordmark
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.holdUpStaysInside
import com.finmax.ui.entryState
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.launch

private class SeerrPersonState(id: Int) : ScreenState() {
    val seerr = FinmaxApp.instance.seerr
    var info by mutableStateOf<SeerrPersonInfo?>(null)
    var titles by mutableStateOf<List<SeerrItem>>(emptyList())
    var focused by mutableStateOf<SeerrItem?>(null)

    init {
        scope.launch { info = runCatching { seerr.person(id) }.getOrNull() }
        scope.launch { titles = runCatching { seerr.credits(id) }.getOrDefault(emptyList()) }
    }
}

private val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")
private fun date(iso: String?) = iso?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

/** A cast member from Discover, laid out like a library person's page: their facts left, all their titles right. */
@Composable
fun SeerrPersonScreen(person: SeerrPerson) {
    val state = entryState("seerrPerson/${person.id}") { SeerrPersonState(person.id) }
    val nav = LocalNav.current
    val info = state.info

    Box(Modifier.fillMaxSize().background(Fm.Bg)) {
        Row(Modifier.fillMaxSize().padding(start = Fm.SafeH, top = Fm.SafeV)) {
            Column(Modifier.width(280.dp).fillMaxHeight().padding(bottom = Fm.SafeV)) {
                Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Wordmark(20.dp, Modifier.padding(bottom = com.finmax.ui.components.LogoLift))
                }
                Spacer(Modifier.height(22.dp))
                Box(Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Fm.Surface)) {
                    (info?.photo ?: person.photo)?.let { AsyncImage(it, person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.height(16.dp))
                Text(info?.name ?: person.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                date(info?.birthday)?.let { born ->
                    val died = date(info?.deathday)
                    val age = java.time.Period.between(born, died ?: java.time.LocalDate.now()).years
                    Spacer(Modifier.height(8.dp))
                    Overline(if (died != null) "Lived" else "Born")
                    Text(
                        listOf(born.format(dateFormat) + (died?.let { " – " + it.format(dateFormat) } ?: ""), "age $age").joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = Fm.Text,
                    )
                    info?.place?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fm.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                info?.biography?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Fm.TextDim, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
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
                        Overline(if (state.titles.isEmpty()) "On Seerr" else "On Seerr · ${state.titles.size}")
                        com.finmax.ui.components.FocusSwap(state.focused, key = { it?.key }) { f ->
                            Text(
                                f?.let { listOfNotNull(it.title, it.year).joinToString("  ·  ") } ?: "Movies & shows",
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
                itemsIndexed(state.titles, key = { _, t -> t.key }) { i, t ->
                    SeerrCard(t, Modifier.aspectRatio(2f / 3f).focusMemory("sp/${t.key}", initial = i == 0), onFocus = { state.focused = t }) { nav.openSeerr(t) }
                }
            }
        }
    }
}
