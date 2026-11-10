package com.finmax.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Holding Up runs to the top of this area and stops there: leaving it upward (to the top bar)
 * takes a fresh press, so a held key never loses the focus in the bar.
 */
fun Modifier.holdUpStaysInside(): Modifier = composed {
    var held by remember { mutableStateOf(false) }
    onPreviewKeyEvent { e ->
        if (e.key == Key.DirectionUp) held = e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0
        false
    }
        .focusProperties { onExit = { if (held && requestedFocusDirection == FocusDirection.Up) cancelFocusChange() } }
        .focusGroup()
}
