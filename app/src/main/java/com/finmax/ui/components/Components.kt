package com.finmax.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import com.finmax.ui.Navigator
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.ui.LocalEntry
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

val ButtonShape = RoundedCornerShape(6.dp)
val CardShape = RoundedCornerShape(8.dp)

// ---- Scrolling ------------------------------------------------------------------------------

/** Scrolls only when the focused element is (partly) off screen. Avoids TV "pivot" jumps on tall headers. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MinimalScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset >= 0 && offset + size <= containerSize -> 0f
        offset < 0 || size > containerSize -> offset
        else -> offset + size - containerSize
    }
}

/** Never auto-scrolls: the screen positions content itself (Home snaps rows explicitly). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val NoAutoScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

// ---- Focus helpers -------------------------------------------------------------------------

/**
 * Records this element as the screen's focused element, and re-focuses it when the user comes
 * back to the screen (Back from a detail page lands on the card they left from).
 */
@Composable
fun Modifier.focusMemory(key: String, initial: Boolean = false): Modifier {
    val entry = LocalEntry.current
    val req = remember { FocusRequester() }
    LaunchedEffect(key) {
        val restore = entry.pendingRestore && entry.focusKey == key
        val first = initial && entry.focusKey == null
        if (restore || first) {
            // Lazy layouts may not have attached the node yet: retry briefly until focus lands.
            for (attempt in 0 until 20) {
                delay(40)
                runCatching { req.requestFocus() }
                if (entry.focusKey == key) break
            }
            entry.pendingRestore = false
        }
    }
    return this.focusRequester(req).onFocusChanged { if (it.isFocused) entry.focusKey = key }
}

/** Remote Play/Play-Pause on a focused card starts playback without opening the detail page. */
fun Modifier.playKey(item: BaseItemDto, nav: Navigator): Modifier = onPreviewKeyEvent { e ->
    val play = e.nativeKeyEvent.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    if (play && e.nativeKeyEvent.action == KeyEvent.ACTION_UP && item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO)) {
        nav.push(com.finmax.ui.Route.Player(item.id)); true
    } else {
        play
    }
}

// ---- Buttons -------------------------------------------------------------------------------

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Fm.Orchid else Color.White.copy(alpha = 0.09f),
            contentColor = if (primary) Fm.Parchment else Fm.Text,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
            disabledContainerColor = Color.White.copy(alpha = 0.04f),
            disabledContentColor = Fm.TextFaint,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Fm.Orchid.copy(alpha = 0.55f), 14.dp)),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Editorial text action ("LEARN MORE  ⟶" in the reference art): tracked uppercase label with a
 * hairline arrow. No fill until focused, then the parchment focus fill.
 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    arrow: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Fm.Text,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Fm.Text.copy(alpha = 0.28f)), shape = ButtonShape),
            focusedBorder = Border(BorderStroke(1.dp, Fm.Focus), shape = ButtonShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(17.dp))
            Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp, maxLines = 1)
            if (arrow) {
                Box(Modifier.padding(start = 4.dp).width(22.dp).height(1.dp).background(LocalContentColor.current))
            }
        }
    }
}

@Composable
fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = if (active) Fm.Accent else Fm.Text,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.padding(10.dp).size(22.dp))
    }
}

/** Generic focusable tile with the FINMAX focus treatment: parchment ring, orchid bloom, gentle lift. */
@Composable
fun FocusTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    focusedScale: Float = 1.07f,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Fm.Surface, focusedContainerColor = Fm.SurfaceHigh),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, Fm.Focus), shape = shape)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Fm.Orchid.copy(alpha = 0.6f), 16.dp)),
        content = content,
    )
}

// ---- Media cards ---------------------------------------------------------------------------

enum class CardStyle { LANDSCAPE, POSTER }

val BaseItemDto.progress: Float?
    get() = userData?.playedPercentage?.toFloat()?.div(100f)?.takeIf { it in 0.01f..0.99f }

fun BaseItemDto.episodeLabel(): String? =
    if (type == BaseItemKind.EPISODE && parentIndexNumber != null && indexNumber != null) "S$parentIndexNumber:E$indexNumber" else null

@Composable
fun MediaCard(
    item: BaseItemDto,
    style: CardStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = if (style == CardStyle.LANDSCAPE) 248.dp else 140.dp,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    overlayText: Boolean = true,
) {
    val repo = FinmaxApp.instance.repo
    val url = remember(item.id, style) { if (style == CardStyle.LANDSCAPE) repo.landscape(item) else repo.poster(item) }
    // Thumb art usually has the title baked in; only overlay the logo on plain backdrops.
    val logo = remember(item.id) {
        val hasThumb = item.imageTags?.containsKey(org.jellyfin.sdk.model.api.ImageType.THUMB) == true
        if (style == CardStyle.LANDSCAPE && item.type != BaseItemKind.EPISODE && !hasThumb) repo.logo(item, 400) else null
    }
    FocusTile(
        onClick = onClick,
        onFocused = onFocused,
        onLongClick = onLongClick,
        modifier = modifier.width(width).aspectRatio(if (style == CardStyle.LANDSCAPE) 16f / 9f else 2f / 3f),
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        val label = item.episodeLabel()
        val thumbHasTitle = item.type != BaseItemKind.EPISODE && item.imageTags?.containsKey(org.jellyfin.sdk.model.api.ImageType.THUMB) == true
        val showText = url == null || (overlayText && style == CardStyle.LANDSCAPE && !thumbHasTitle)
        if (showText) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = if (url == null) 0f else 0.85f)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                when {
                    logo != null -> AsyncImage(
                        model = logo,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.height(34.dp).fillMaxWidth(0.7f),
                    )
                    item.type == BaseItemKind.EPISODE -> {
                        Text(item.seriesName ?: "", style = MaterialTheme.typography.labelSmall, color = Fm.TextDim, maxLines = 1)
                        Text(
                            listOfNotNull(label, item.name).joinToString("  "),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    else -> Text(item.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (item.userData?.played == true) {
            Icon(
                Icons.Filled.CheckCircle, "Watched", tint = Fm.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).background(Fm.Bg, CircleShape),
            )
        }
        item.userData?.unplayedItemCount?.takeIf { it > 0 && item.type == BaseItemKind.SERIES }?.let {
            Text(
                "$it", style = MaterialTheme.typography.labelSmall, color = Fm.Bg,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Fm.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        item.progress?.let { ProgressStripe(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
fun ProgressStripe(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Fm.Parchment.copy(alpha = 0.22f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Fm.Accent))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MediaRow(
    title: String,
    items: List<BaseItemDto>,
    style: CardStyle,
    rowKey: String,
    onOpen: (BaseItemDto) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    onFocused: ((BaseItemDto) -> Unit)? = null,
    initialFocus: Boolean = false,
    showTitle: Boolean = true,
) {
    val nav = com.finmax.ui.LocalNav.current
    Column(modifier) {
        if (showTitle) SectionTitle(title)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = Fm.SafeH, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Fm.CardGap),
            modifier = Modifier.focusRestorer(),
        ) {
            items(items, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    style = style,
                    onClick = { onOpen(item) },
                    onFocused = { onFocused?.invoke(item) },
                    modifier = Modifier.focusMemory("$rowKey/${item.id}", initial = initialFocus && item == items.first()).playKey(item, nav),
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, index: Int? = null) {
    Row(
        modifier.padding(start = Fm.SafeH, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (index != null) Text("%02d".format(index), style = MaterialTheme.typography.labelSmall, color = Fm.Accent)
        Text(text, style = MaterialTheme.typography.headlineSmall, color = Fm.Text)
    }
}

/** Tracked uppercase label ("PREMIERE", "DIRECTOR") used above facts and section titles. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Fm.Accent) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier, maxLines = 1)
}

/** Thin parchment rule, the hairline that structures the reference layouts. */
@Composable
fun Hairline(modifier: Modifier = Modifier, vertical: Boolean = false, alpha: Float = 0.18f) {
    Box(
        (if (vertical) modifier.fillMaxHeight().width(1.dp) else modifier.fillMaxWidth().height(1.dp))
            .background(Fm.Parchment.copy(alpha = alpha)),
    )
}

// ---- Loading / error / empty states ---------------------------------------------------------

@Composable
fun shimmerAlpha(): Float {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.05f, 0.13f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    return a
}

@Composable
fun SkeletonRow(style: CardStyle = CardStyle.LANDSCAPE, count: Int = 5) {
    val a = shimmerAlpha()
    Column {
        Box(Modifier.padding(start = Fm.SafeH, top = 18.dp).size(180.dp, 20.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = a)))
        Row(Modifier.padding(horizontal = Fm.SafeH, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(Fm.CardGap)) {
            repeat(count) {
                Box(
                    Modifier.width(if (style == CardStyle.LANDSCAPE) 248.dp else 140.dp)
                        .aspectRatio(if (style == CardStyle.LANDSCAPE) 16f / 9f else 2f / 3f)
                        .clip(CardShape).background(Color.White.copy(alpha = a)),
                )
            }
        }
    }
}

@Composable
fun MessagePanel(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Fm.TextDim, textAlign = TextAlign.Center, modifier = Modifier.width(520.dp))
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            val req = remember { FocusRequester() }
            PillButton(actionLabel, onAction, Modifier.focusRequester(req), primary = true)
            LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
        }
    }
}

@Composable
fun ErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) =
    MessagePanel("Couldn't load this", message, modifier, "Try again", onRetry)

/**
 * Full-bleed artwork washed into the Chlorophyll Void base so foreground text stays readable.
 * [textSide] is where the text sits: the artwork fades to the base colour on that side.
 */
@Composable
fun BackdropImage(
    url: String?,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    textSide: Alignment.Horizontal = Alignment.Start,
) {
    Box(modifier.fillMaxSize().background(Fm.Bg)) {
        if (url != null) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = if (textSide == Alignment.End) Alignment.CenterStart else Alignment.TopEnd,
                modifier = Modifier.fillMaxSize(),
            )
        }
        BackdropScrim(textSide)
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Fm.Bg.copy(alpha = dim)))
    }
}

/** The gradients of [BackdropImage], separately so video layers can sit under them too. */
@Composable
fun BackdropScrim(textSide: Alignment.Horizontal = Alignment.Start) {
    // A faint green wash over all artwork ties every title into the palette.
    Box(Modifier.fillMaxSize().background(Fm.Void.copy(alpha = 0.18f)))
    val side = if (textSide == Alignment.End) {
        Brush.horizontalGradient(0.3f to Color.Transparent, 0.62f to Fm.Bg.copy(alpha = 0.78f), 1f to Fm.Bg.copy(alpha = 0.95f))
    } else {
        Brush.horizontalGradient(0f to Fm.Bg, 0.36f to Fm.Bg.copy(alpha = 0.78f), 0.72f to Color.Transparent)
    }
    Box(Modifier.fillMaxSize().background(side))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Fm.Bg.copy(alpha = 0.55f), 0.22f to Color.Transparent, 0.55f to Color.Transparent, 1f to Fm.Bg)))
}

/**
 * The brand field from the palette card: Chlorophyll Void melting into Taut Orchid with a
 * Parchment glow. Used where there is no artwork (sign-in, profiles, empty states).
 */
@Composable
fun BrandBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Fm.Bg).drawBehind {
            drawRect(Brush.radialGradient(listOf(Fm.Void, Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.05f), radius = size.width * 0.75f))
            drawRect(Brush.radialGradient(listOf(Fm.Orchid.copy(alpha = 0.75f), Color.Transparent), center = Offset(size.width * 0.82f, size.height * 1.0f), radius = size.width * 0.6f))
            drawRect(Brush.radialGradient(listOf(Fm.Parchment.copy(alpha = 0.32f), Color.Transparent), center = Offset(size.width * 0.98f, size.height * 1.12f), radius = size.width * 0.32f))
        },
    )
}
