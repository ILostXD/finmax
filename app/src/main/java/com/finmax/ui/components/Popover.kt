package com.finmax.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Check
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.delay

/** Extra width over which the blur and tint fade in, to the left of a popover's content. */
private val FadeWidth = 160.dp

/**
 * Blurs this content progressively toward the right edge while a [Popover] of [width] is open:
 * sharp on the left, frosted under the menu. Apply it to the page *behind* the popover.
 * (Android 12+; older versions just get the tint from [Popover].)
 */
@Composable
fun Modifier.popoverBlur(active: Boolean, width: Dp = 360.dp): Modifier {
    if (!active || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val content = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val masked = rememberGraphicsLayer()
    val reach = with(LocalDensity.current) { (width + FadeWidth).toPx() }
    val fade = with(LocalDensity.current) { FadeWidth.toPx() }
    return drawWithContent {
        content.record { this@drawWithContent.drawContent() }
        drawLayer(content)
        blurred.renderEffect = BlurEffect(40f, 40f, TileMode.Clamp)
        blurred.record { drawLayer(content) }
        masked.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        masked.record {
            drawLayer(blurred)
            val start = size.width - reach
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = start, endX = start + fade),
                blendMode = BlendMode.DstIn,
            )
        }
        drawLayer(masked)
    }
}

/**
 * Right-hand menu without a container: a tint that fades in from transparent over the page (which
 * should use [popoverBlur]), with the [title] and [content] floating on it. Focus moves into the
 * menu when it opens, unless something inside already took it.
 */
@Composable
fun Popover(title: String, width: Dp = 360.dp, content: @Composable () -> Unit) {
    val req = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(10) {
            delay(60)
            if (hasFocus) return@LaunchedEffect
            runCatching { req.requestFocus() }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val startFraction = ((maxWidth - width - FadeWidth) / maxWidth).coerceIn(0f, 1f)
        val endFraction = ((maxWidth - width) / maxWidth).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    startFraction to Color.Transparent,
                    endFraction to Fm.Bg.copy(alpha = 0.72f),
                    1f to Fm.Bg.copy(alpha = 0.86f),
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(width)
                .padding(start = 8.dp, end = Fm.SafeH - 8.dp, top = Fm.SafeV, bottom = Fm.SafeV)
                .focusRequester(req)
                .onFocusChanged { hasFocus = it.hasFocus }
                .focusGroup(),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp, bottom = 18.dp))
            content()
        }
    }
}

/**
 * A row in a [Popover]: just text until focused (then the focus pill), a check and accent colour
 * when [primary] (selected). Same parameters as [PillButton] so pickers can swap freely.
 */
@Composable
fun PopoverItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(ButtonShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (primary) Fm.Accent else Fm.Text,
            focusedContainerColor = Fm.Focus,
            focusedContentColor = Fm.OnFocus,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Fm.TextFaint,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            val lead = icon ?: if (primary) androidx.compose.material.icons.Icons.Filled.Check else null
            if (lead != null) {
                androidx.tv.material3.Icon(lead, null, Modifier.padding(0.dp).width(18.dp))
            } else {
                Box(Modifier.width(18.dp))
            }
            Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
