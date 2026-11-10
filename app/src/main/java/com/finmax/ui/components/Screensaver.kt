package com.finmax.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemDto

/** Set while the screensaver covers the app: background videos pause under it. */
object ScreensaverState {
    var active by mutableStateOf(false)
}

private const val SLIDE_MS = 12_000

/**
 * Slow-drifting backdrops from the library with each title's logo and a clock (also the Android TV
 * screensaver, see FinmaxDream). Without a signed-in session it shows the brand and the clock.
 */
@Composable
fun Screensaver(modifier: Modifier = Modifier) {
    val app = FinmaxApp.instance
    val signedIn = app.sessions.session.value != null
    var items by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        items = runCatching { app.repo.featured(24) }.getOrDefault(emptyList())
        while (items.isNotEmpty()) {
            delay(SLIDE_MS.toLong())
            index++
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        val item = items.takeIf { it.isNotEmpty() }?.let { it[index % it.size] }
        if (item == null) BrandBackdrop()
        AnimatedContent(item, transitionSpec = { fadeIn(tween(1600)) togetherWith fadeOut(tween(1600)) }, label = "saver") { it ->
            if (it == null) return@AnimatedContent
            Box(Modifier.fillMaxSize()) {
                // Ken Burns: each slide drifts and zooms a little (also keeps static pixels moving).
                var go by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { go = true }
                val t by androidx.compose.animation.core.animateFloatAsState(
                    if (go) 1f else 0f, tween(SLIDE_MS + 2_000, easing = LinearEasing), label = "drift",
                )
                val right = it.id.hashCode() % 2 == 0
                val drift = Modifier.fillMaxSize().graphicsLayer {
                    val s = 1.06f + 0.08f * t
                    scaleX = s; scaleY = s
                    translationX = (if (right) 1 else -1) * size.width * 0.03f * (t - 0.5f)
                }
                AsyncImage(app.repo.backdrop(it), null, contentScale = ContentScale.Crop, modifier = drift)
                // Frosted glass behind the title (bottom left, large) and the clock (top right, small).
                val soft = app.repo.backdrop(it, 320)
                Frost(soft, drift) { Offset(0f, it.height) to it.width * 0.62f }
                Frost(soft, drift) { Offset(it.width, 0f) to it.width * 0.24f }
                Column(Modifier.align(Alignment.BottomStart).padding(start = Fm.SafeH, bottom = Fm.SafeV + 8.dp)) {
                    val logo = app.repo.logo(it, 600)
                    if (logo != null) {
                        AsyncImage(logo, it.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                            modifier = Modifier.height(130.dp).widthIn(max = 560.dp))
                    } else {
                        com.finmax.ui.home.BigTitle(it.name ?: "", maxLines = 2, modifier = Modifier.widthIn(max = 620.dp))
                    }
                }
            }
        }
        Clock(Modifier.align(Alignment.TopEnd).padding(end = Fm.SafeH, top = Fm.SafeV))
        if (item != null) Wordmark(20.dp, Modifier.align(Alignment.TopStart).padding(start = Fm.SafeH, top = Fm.SafeV + 8.dp))
        if (item == null) Wordmark(32.dp, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    var now by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = java.time.LocalTime.now(); delay(1_000) }
    }
    Text(now.format(fmt), style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.85f), modifier = modifier)
}

/** A blurred, darkened copy of the slide that shows only inside a soft circle ([at]: centre and radius). */
@Composable
private fun Frost(url: String?, drift: Modifier, at: (androidx.compose.ui.geometry.Size) -> Pair<Offset, Float>) {
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val (c, r) = at(size)
                drawRect(
                    Brush.radialGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent, center = c, radius = r),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        BlurredImage(url, 28.dp, drift)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}
