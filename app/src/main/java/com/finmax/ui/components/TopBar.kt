package com.finmax.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.R
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.theme.Fm

/** The finmax wordmark: soft grey-to-amethyst lettering (brand art in res/drawable-nodpi, trimmed tight). */
@Composable
fun Wordmark(height: Dp = 22.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.finmax_wordmark_frost), contentDescription = "finmax", modifier = modifier.height(height))
}

private val tabs = listOf(
    Route.Home to "Home",
    Route.Movies to "Movies",
    Route.Shows to "Shows",
    Route.MyList to "My List",
    Route.Discover to "Discover",
)

/** Wordmark on the left, page tabs centred on screen, search/settings/profile on the right. */
@Composable
fun TopBar(current: Route.Tab, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    // Top padding puts the wordmark's centre where the details page has it.
    Box(modifier.fillMaxWidth().padding(start = Fm.SafeH, end = Fm.SafeH, top = TopBarTop, bottom = 6.dp)) {
        Wordmark(20.dp, Modifier.align(Alignment.CenterStart).offset(y = -LogoLift))
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { (route, label) ->
                TabItem(label, selected = current == route) { nav.tab(route) }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Ic.Search, "Search", { nav.tab(Route.Search) }, active = current == Route.Search, size = TabHeight)
            Spacer(Modifier.width(10.dp))
            Box {
                IconButton(Ic.Settings, "Settings", { nav.tab(Route.Settings) }, active = current == Route.Settings, size = TabHeight)
                // An app update is waiting (Settings → About & updates).
                if (com.finmax.update.Updater.available != null) {
                    Box(Modifier.align(Alignment.TopEnd).size(9.dp).background(Fm.Accent, CircleShape))
                }
            }
            Spacer(Modifier.width(10.dp))
            Avatar { nav.push(Route.Profiles) }
        }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Fm.Text else Fm.TextDim,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        // The selected marker is drawn over the bottom padding, so it never shifts the label off centre.
        Box(contentAlignment = Alignment.Center) {
            Text(
                label, style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.height(TabHeight).wrapContentHeight().padding(horizontal = 16.dp),
            )
            if (selected) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 14.dp, height = 2.dp).background(Fm.Accent, CircleShape))
            }
        }
    }
}

/** Top padding of the bar, shared with pages that place content under it. */
val TopBarTop = 20.dp

/** How far the wordmark sits above the line the rest of a top bar centres on (same on every page). */
val LogoLift = 2.dp

/** A tab pill's height (label plus its padding); the bar's round actions match it. */
private val TabHeight = 38.dp

/** The picture fills the circle (no grey ring); focus draws a white ring inside it, so it never grows. */
@Composable
private fun Avatar(onClick: () -> Unit) {
    val url = runCatching { FinmaxApp.instance.repo.userImage() }.getOrNull()
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Fm.SurfaceHigh, focusedContainerColor = Fm.SurfaceHigh, pressedContainerColor = Fm.SurfaceHigh),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
    ) {
        Box(Modifier.size(TabHeight).clip(CircleShape), contentAlignment = Alignment.Center) {
            if (url != null) {
                AsyncImage(url, "Profile", contentScale = ContentScale.Crop, modifier = Modifier.size(TabHeight))
            } else {
                Icon(Ic.Person, "Profile", Modifier.size(20.dp))
            }
            if (focused) Box(Modifier.size(TabHeight).border(2.5.dp, Fm.Focus, CircleShape))
        }
    }
}

/** Small "Movie • 2003 • 3h 20m • PG-13" style line used across hero, details and info strips. */
@Composable
fun MetaLine(parts: List<String>, modifier: Modifier = Modifier, color: Color = Fm.TextDim, badge: String? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val shown = parts.filter { it.isNotBlank() }
        shown.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.size(3.dp).background(Fm.TextFaint, CircleShape))
            Text(p, style = MaterialTheme.typography.labelMedium, color = color)
        }
        // The age rating, outlined like everywhere else.
        if (badge != null) {
            if (shown.isNotEmpty()) Box(Modifier.size(3.dp).background(Fm.TextFaint, CircleShape))
            Badge(badge)
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 0.8.sp,
        color = Fm.TextDim,
        modifier = modifier.clip(CircleShape).border(1.dp, Fm.Mist.copy(alpha = 0.3f), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
