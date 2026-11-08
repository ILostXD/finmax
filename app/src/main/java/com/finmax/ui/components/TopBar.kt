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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** The finmax wordmark (brand art in res/drawable-nodpi), tinted parchment by default. */
@Composable
fun Wordmark(height: Dp = 22.dp, modifier: Modifier = Modifier, gradient: Boolean = false, tint: Color = Fm.Parchment) {
    Image(
        painterResource(if (gradient) R.drawable.finmax_wordmark_gradient else R.drawable.finmax_wordmark),
        contentDescription = "finmax",
        colorFilter = if (gradient) null else ColorFilter.tint(tint),
        modifier = modifier.height(height),
    )
}

private val tabs = listOf(
    Route.Home to "Home",
    Route.Movies to "Movies",
    Route.Shows to "Shows",
    Route.MyList to "My List",
    Route.Libraries to "Libraries",
)

@Composable
fun TopBar(current: Route.Tab, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    Row(
        modifier.fillMaxWidth().padding(start = Fm.SafeH, end = Fm.SafeH, top = Fm.SafeV, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark(20.dp)
        Spacer(Modifier.width(44.dp))
        tabs.forEach { (route, label) ->
            TabItem(label, selected = current == route) { nav.tab(route) }
        }
        Spacer(Modifier.weight(1f))
        IconButton(Icons.Filled.Search, "Search", { nav.tab(Route.Search) }, active = current == Route.Search)
        Spacer(Modifier.width(10.dp))
        IconButton(Icons.Filled.Settings, "Settings", { nav.tab(Route.Settings) }, active = current == Route.Settings)
        Spacer(Modifier.width(10.dp))
        Avatar { nav.push(Route.Profiles) }
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
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            Box(
                Modifier.padding(top = 3.dp).size(width = 14.dp, height = 2.dp)
                    .background(if (selected) Fm.Accent else Color.Transparent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
private fun Avatar(onClick: () -> Unit) {
    val url = runCatching { FinmaxApp.instance.repo.userImage() }.getOrNull()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Fm.SurfaceHigh, focusedContainerColor = Fm.Focus),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    ) {
        Box(Modifier.size(40.dp).padding(3.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            if (url != null) {
                AsyncImage(url, "Profile", contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp).clip(CircleShape))
            } else {
                Icon(Icons.Filled.Person, "Profile", Modifier.size(22.dp))
            }
        }
    }
}

/** Small "Movie • 2003 • 3h 20m • PG-13" style line used across hero, details and info strips. */
@Composable
fun MetaLine(parts: List<String>, modifier: Modifier = Modifier, color: Color = Fm.TextDim) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.filter { it.isNotBlank() }.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.size(3.dp).background(Fm.TextFaint, CircleShape))
            Text(p, style = MaterialTheme.typography.labelMedium, color = color)
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
        modifier = modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, Fm.Parchment.copy(alpha = 0.3f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
