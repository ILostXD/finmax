package com.finmax.ui.login

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.finmax.ui.components.Ic
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PIN_LENGTH = 4

/**
 * Full-screen 4-digit PIN entry: an on-screen pad, or the remote's number keys.
 * [onPin] gets each complete PIN and returns false when it is wrong (the dots shake and clear).
 */
@Composable
fun PinPad(title: String, subtitle: String, onPin: (String) -> Boolean) {
    var pin by remember(title, subtitle) { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(title, subtitle) { delay(50); runCatching { first.requestFocus() } }

    fun type(d: Char) {
        if (pin.length >= PIN_LENGTH) return
        pin += d
        if (pin.length == PIN_LENGTH) scope.launch {
            delay(120) // let the last dot show
            val entered = pin
            if (!onPin(entered)) {
                shake.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 900f), initialVelocity = 1800f)
            }
            pin = ""
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).onPreviewKeyEvent { e ->
            val code = e.key.nativeKeyCode
            if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                if (e.type == KeyEventType.KeyDown) type('0' + (code - KeyEvent.KEYCODE_0))
                true
            } else if (code == KeyEvent.KEYCODE_DEL) {
                if (e.type == KeyEventType.KeyDown) pin = pin.dropLast(1)
                true
            } else false
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Fm.TextDim, modifier = Modifier.padding(top = 6.dp))
            Row(
                Modifier.padding(vertical = 28.dp).offset { IntOffset(shake.value.toInt() / 40, 0) },
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                repeat(PIN_LENGTH) { i ->
                    val on = i < pin.length
                    Box(
                        Modifier.size(16.dp).background(if (on) Fm.Accent else Color.Transparent, CircleShape)
                            .border(2.dp, if (on) Fm.Accent else Fm.TextFaint, CircleShape),
                    )
                }
            }
            val rows = listOf("123", "456", "789")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEachIndexed { r, keys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        keys.forEachIndexed { c, k ->
                            PadKey(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier, onClick = { type(k) }) {
                                Text(k.toString(), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(72.dp))
                    PadKey(onClick = { type('0') }) { Text("0", style = MaterialTheme.typography.headlineSmall) }
                    PadKey(onClick = { pin = pin.dropLast(1) }) { Icon(Ic.Backspace, "Delete", Modifier.size(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PadKey(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(72.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Fm.Surface, contentColor = Fm.Text, focusedContainerColor = Fm.Focus, focusedContentColor = Fm.OnFocus,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
