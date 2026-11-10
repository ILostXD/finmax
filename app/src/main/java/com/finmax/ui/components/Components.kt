package com.finmax.ui.components

import kotlin.math.roundToInt
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import com.finmax.ui.Navigator
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.layer.drawLayer
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

/** Pill-shaped controls and soft, generously rounded cards. */
val ButtonShape = RoundedCornerShape(percent = 50)
val CardShape = RoundedCornerShape(14.dp)

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

/**
 * For horizontal rows: keeps a focused card at least [margin] from either edge, so scrolling back
 * to the first card restores the row's original start padding instead of pinning it to the edge.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun rememberEdgeScroll(margin: Dp = Fm.SafeH): androidx.compose.foundation.gestures.BringIntoViewSpec {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { margin.toPx() }
    return remember(px) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < px -> offset - px
                offset + size > containerSize - px -> offset + size - (containerSize - px)
                else -> 0f
            }
        }
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
            // Every button rests on the same translucent fill and turns white when focused.
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Fm.Text,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
            disabledContainerColor = Color.White.copy(alpha = 0.04f),
            disabledContentColor = Fm.TextFaint,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Row(
            Modifier.height(ButtonHeight).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) MorphIcon(icon, null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Editorial text action ("LEARN MORE  ⟶" in the reference art): tracked uppercase label with a
 * hairline arrow, on the same translucent fill as the icon buttons.
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
            // Same translucent fill as IconButton and the secondary pills.
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Fm.Text,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Row(
            Modifier.height(ButtonHeight).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) MorphIcon(icon, null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (arrow) {
                Box(Modifier.padding(start = 4.dp).width(22.dp).height(1.dp).background(LocalContentColor.current))
            }
        }
    }
}

/** Height shared by every pill and icon button, so a row of actions lines up. */
val ButtonHeight = 44.dp

/** Round icon action, [size] across (pills' height by default; the top bar uses its tabs' height). */
@Composable
fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, size: Dp = ButtonHeight) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = if (active) Fm.Accent else Fm.Text,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            MorphIcon(icon, label, Modifier.size(size * 0.5f), key = active)
        }
    }
}

/**
 * An icon that morphs when it changes (+ into a check, or the same icon switching on): the old one
 * turns and shrinks away while the new one turns and grows in. [key] also triggers it, for buttons
 * whose icon stays the same and only switches state.
 */
@Composable
fun MorphIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, key: Any? = null) {
    androidx.compose.animation.AnimatedContent(
        targetState = icon to key,
        transitionSpec = {
            (fadeIn(tween(200, 60)) + androidx.compose.animation.scaleIn(tween(320, 60, FastOutSlowInEasing), initialScale = 0.3f)) togetherWith
                (fadeOut(tween(160)) + androidx.compose.animation.scaleOut(tween(220, easing = FastOutSlowInEasing), targetScale = 0.3f)) using
                androidx.compose.animation.SizeTransform(clip = false)
        },
        contentAlignment = Alignment.Center,
        label = "morph",
    ) { (ic, _) ->
        val turn by transition.animateFloat({ tween(320, easing = FastOutSlowInEasing) }, label = "turn") {
            when (it) {
                androidx.compose.animation.EnterExitState.PreEnter -> -90f
                androidx.compose.animation.EnterExitState.Visible -> 0f
                androidx.compose.animation.EnterExitState.PostExit -> 90f
            }
        }
        Icon(ic, contentDescription, modifier.graphicsLayer { rotationZ = turn })
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
    glow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Fm.Surface, focusedContainerColor = Fm.SurfaceHigh, pressedContainerColor = Fm.SurfaceHigh),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, Fm.Focus), shape = shape)),
        glow = if (glow) ClickableSurfaceDefaults.glow(focusedGlow = Glow(Fm.Amethyst.copy(alpha = 0.6f), 16.dp)) else ClickableSurfaceDefaults.glow(),
        content = content,
    )
}

// ---- Media cards ---------------------------------------------------------------------------

enum class CardStyle { LANDSCAPE, POSTER }

/** Repository for card artwork; screenshot tests provide one without a running app. */
val LocalMediaRepo = androidx.compose.runtime.staticCompositionLocalOf<com.finmax.data.MediaRepository?> { null }

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
    focusedScale: Float = 1.07f,
    glow: Boolean = true,
) {
    val repo = LocalMediaRepo.current ?: FinmaxApp.instance.repo
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
        focusedScale = focusedScale,
        glow = glow,
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
                Ic.CheckCircle, "Watched", tint = Fm.Accent,
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
    Box(modifier.fillMaxWidth().height(4.dp).background(Fm.Mist.copy(alpha = 0.22f))) {
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
    cardWidth: Dp? = null,
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
                    width = cardWidth ?: if (style == CardStyle.LANDSCAPE) 248.dp else 140.dp,
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
            .background(Fm.Mist.copy(alpha = alpha)),
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
 * Full-bleed artwork treated like frosted glass: the picture stays sharp on the far side and melts
 * into a blurred, lightly tinted copy of itself where the text sits, instead of fading to black.
 * [soft] is a small render of the same art (cheap to blur); it falls back to [url].
 * [textSide] is where the text sits.
 */
@Composable
fun GlassBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    soft: String? = url,
    textSide: Alignment.Horizontal = Alignment.Start,
    dim: Float = 0f,
    sharp: @Composable BoxScope.() -> Unit = {},
    blur: Dp = 42.dp,
    /** How far past the default the glass reaches into the art, as a fraction of the width. */
    reach: Float = 0f,
    /** 0..1: lifts the glass so the sharp layer (a video) fills the screen, for the hand-off to the player. */
    clear: Float = 0f,
    /**
     * The glass is a blurred copy of what's actually behind it (art or a playing video, drawn twice)
     * instead of a separate blurred still, so a video never sits under the still's blur.
     */
    live: Boolean = false,
    /** 0..1: the sharp picture dissolves fully into the glass (the page scrolled past its header). */
    frost: Float = 0f,
    /** Where the bottom glass starts and where it's fully glass, as fractions of the height. */
    bottomFade: ClosedFloatingPointRange<Float> = 0.62f..1f,
) {
    Box(modifier.fillMaxSize().background(Fm.Bg)) {
        if (url == null) {
            BrandBackdrop()
            return@Box
        }
        if (live) {
            LiveGlass(url, textSide, dim, sharp, blur, reach, clear, frost, bottomFade)
            return@Box
        }
        StaticGlass(url, soft, textSide, dim, sharp, blur, reach, clear, bottomFade)
    }
}

/**
 * The still-art glass, kept as one offscreen layer: its ~10 full-screen passes (art, blur, tints,
 * masks) are drawn once and then reused as a single texture while anything else on the page
 * animates. Redrawing them every frame held the Chromecast's GPU at ~100 ms a frame.
 */
@Composable
private fun StaticGlass(
    url: String, soft: String?, textSide: Alignment.Horizontal, dim: Float, sharp: @Composable BoxScope.() -> Unit,
    blur: Dp, reach: Float, clear: Float, bottomFade: ClosedFloatingPointRange<Float>,
) {
    Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        // 1. Blurred copy fills the screen: the "glass" the text sits on.
        BlurredImage(soft ?: url, blur, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Fm.Bg.copy(alpha = 0.42f)))
        // Deeper tint where the text sits, so light artwork still reads (still glass, not black).
        val shade = if (textSide == Alignment.End) {
            Brush.horizontalGradient((0.45f - reach) to Color.Transparent, 1f to Fm.Bg.copy(alpha = 0.5f))
        } else {
            Brush.horizontalGradient(0f to Fm.Bg.copy(alpha = 0.5f), (0.6f + reach) to Color.Transparent)
        }
        Box(Modifier.fillMaxSize().background(shade))
        // 2. Sharp art, masked so it dissolves into the glass toward the text side.
        val glass = Color.Black.copy(alpha = clear)
        val fade = if (textSide == Alignment.End) {
            Brush.horizontalGradient((0.38f - reach) to Color.Black, (0.78f - reach) to glass)
        } else {
            Brush.horizontalGradient((0.3f + reach) to glass, (0.7f + reach) to Color.Black)
        }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(fade, blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(bottomFade.start to Color.Black, bottomFade.endInclusive to glass), blendMode = BlendMode.DstIn)
                },
        ) {
            AsyncImage(url, null, contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
            sharp()
        }
        // 3. A whisper of shade at the very top keeps the top bar legible on bright art.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Fm.Bg.copy(alpha = 0.35f * (1f - clear)), 0.2f to Color.Transparent)))
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Fm.Bg.copy(alpha = dim)))
    }
}

private const val GlassDownscale = 8f

/** [GlassBackdrop] with live glass: art and video are recorded once and drawn blurred, then sharp and masked. */
@Composable
private fun LiveGlass(
    url: String, textSide: Alignment.Horizontal, dim: Float, sharp: @Composable BoxScope.() -> Unit,
    blur: Dp, reach: Float, clear: Float, frost: Float, bottomFade: ClosedFloatingPointRange<Float>,
) {
    // Glass copies, each recorded at 1/8 size: across the screen, at the bottom edge, and unmasked for frost.
    val acrossLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val bottomLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val frostLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val blurPx = with(androidx.compose.ui.platform.LocalDensity.current) { blur.toPx() }
    val right = textSide == Alignment.End
    Box(
        Modifier.fillMaxSize().drawWithContent {
            // The Chromecast's GPU manages only a few full-screen passes per video frame (nine took
            // ~110 ms a frame). So: the picture is drawn sharp once, and the blurred, tinted glass goes
            // on top as small layers scaled up, their fade toward the art baked in at 1/8 size.
            // Glass over sharp with alpha (1 - m) equals sharp masked by m over glass. The sharp mask m
            // is the product of a horizontal and a bottom fade; two glass coats with alpha (1 - mh) and
            // (1 - mv) give 1 - mh * mv, the same picture.
            this@drawWithContent.drawContent()
            val full = size
            val k = GlassDownscale
            val small = androidx.compose.ui.unit.IntSize((full.width / k).roundToInt().coerceAtLeast(1), (full.height / k).roundToInt().coerceAtLeast(1))
            val effect = androidx.compose.ui.graphics.BlurEffect(blurPx / k, blurPx / k, androidx.compose.ui.graphics.TileMode.Clamp)
            // Gradients get explicit full-screen ends: inside the small layer, relative ones would
            // stretch over the layer's 1/8 size instead of the screen.
            val w = full.width
            val shade = if (right) Brush.horizontalGradient((0.45f - reach) to Color.Transparent, 1f to Fm.Bg.copy(alpha = 0.5f), startX = 0f, endX = w)
                else Brush.horizontalGradient(0f to Fm.Bg.copy(alpha = 0.5f), (0.6f + reach) to Color.Transparent, startX = 0f, endX = w)
            fun coat(layer: androidx.compose.ui.graphics.layer.GraphicsLayer, sharpMask: Brush?, alpha: Float) {
                if (alpha <= 0f) return
                layer.renderEffect = effect
                // Its own buffer, or the DstOut cut below would cut into the screen behind it.
                layer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                layer.alpha = alpha
                layer.record(size = small) {
                    scale(1f / k, 1f / k, pivot = Offset.Zero) {
                        this@drawWithContent.drawContent()
                        drawRect(Fm.Bg.copy(alpha = 0.42f), size = full)
                        drawRect(shade, size = full)
                        // Cut the glass away where the sharp picture shows.
                        if (sharpMask != null) drawRect(sharpMask, size = full, blendMode = BlendMode.DstOut)
                    }
                }
                scale(k, k, pivot = Offset.Zero) { drawLayer(layer) }
            }
            val glass = 1f - clear
            val across = if (right) Brush.horizontalGradient((0.38f - reach) to Color.Black, (0.78f - reach) to Color.Transparent, startX = 0f, endX = w)
                else Brush.horizontalGradient((0.3f + reach) to Color.Transparent, (0.7f + reach) to Color.Black, startX = 0f, endX = w)
            coat(acrossLayer, across, glass)
            // The bottom fade only reaches the lower part: draw that coat there alone.
            clipRect(top = full.height * (bottomFade.start - 0.02f)) {
                coat(bottomLayer, Brush.verticalGradient(bottomFade.start to Color.Black, bottomFade.endInclusive to Color.Transparent, startY = 0f, endY = full.height), glass)
            }
            // Frost melts the sharp picture away: plain glass over everything.
            coat(frostLayer, null, glass * frost)
            drawRect(Brush.verticalGradient(listOf(Fm.Bg.copy(alpha = 0.35f * glass), Color.Transparent), endY = full.height * 0.2f), size = full.copy(height = full.height * 0.2f))
            if (dim > 0f) drawRect(Fm.Bg.copy(alpha = dim))
        },
    ) {
        AsyncImage(url, null, contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
        sharp()
    }
}

@Composable
fun BackdropImage(url: String?, modifier: Modifier = Modifier, dim: Float = 0f, textSide: Alignment.Horizontal = Alignment.Start) =
    GlassBackdrop(url, modifier, textSide = textSide, dim = dim)

/**
 * The same frosted glass as [GlassBackdrop], drawn only inside a panel ([fromRight] wide, at the
 * right edge), so a list can float over the artwork without a dark box behind it.
 */
@Composable
fun GlassPanelBackground(soft: String?, fromRight: Dp, modifier: Modifier = Modifier) {
    if (soft == null) return
    Box(
        modifier.fillMaxSize().drawWithContent {
            clipRect(left = size.width - fromRight.toPx()) { this@drawWithContent.drawContent() }
        },
    ) {
        BlurredImage(soft, 42.dp, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Fm.Bg.copy(alpha = 0.55f)))
    }
}

/**
 * The brand field: black with a Royal Amethyst glow rising from the
 * bottom right. Used where there is no artwork (sign-in, profiles, empty states).
 */
@Composable
fun BrandBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Fm.Bg).drawBehind {
            drawRect(Brush.radialGradient(listOf(Fm.Amethyst.copy(alpha = 0.7f), Color.Transparent), center = Offset(size.width * 0.9f, size.height * 1.05f), radius = size.width * 0.6f))
            drawRect(Brush.radialGradient(listOf(Fm.Mist.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.05f, size.height * 0.0f), radius = size.width * 0.45f))
        },
    )
}

/**
 * Text that follows focus (the facts under a row, a grid's title line): the new content fades in
 * smoothly as the old fades out, instead of popping. Size is never animated: on the
 * Chromecast that would re-lay out everything below on every frame.
 */
/** Ease-in-out for fades, so the change is spread over the whole duration. */
val FadeEasing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

@Composable
fun <T> FocusSwap(target: T, modifier: Modifier = Modifier, key: (T) -> Any? = { it }, content: @Composable (T) -> Unit) {
    androidx.compose.animation.AnimatedContent(
        target, modifier, contentKey = key,
        transitionSpec = {
            // Out first, then in, both eased evenly: a front-loaded curve finished in ~130 ms and read as a pop,
            // and a short fade-out vanished inside the frames the Chromecast drops right after a key press.
            fadeIn(tween(450, 220, FadeEasing)) togetherWith
                fadeOut(tween(300, easing = FadeEasing)) using androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() }
        },
        contentAlignment = Alignment.TopStart,
        label = "focusSwap",
    ) { content(it) }
}
