package com.finmax.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The TV's refresh rate follows the film's (24p on a 24 Hz mode instead of judder at 60 Hz).
 * Keeps the current resolution: only the rate changes.
 */
object DisplayModes {
    /** The mode at the current resolution whose rate is a whole multiple of [fps], highest first (25p → 50 Hz). */
    fun matching(display: Display, fps: Float): Display.Mode? {
        if (fps < 10f) return null
        val cur = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .filter { m ->
                val k = (m.refreshRate / fps).roundToInt()
                // 0.01 Hz tells 23.976 from 24 and 59.94 from 60.
                k >= 1 && abs(m.refreshRate - k * fps) < 0.01f * k
            }
            .maxByOrNull { it.refreshRate }
    }

    /**
     * Asks for the mode matching [fps]; returns once the TV has switched (or after a few seconds).
     * @return true if a switch was requested.
     */
    suspend fun switchTo(activity: Activity, fps: Float): Boolean {
        val display = activity.window.decorView.display ?: return false
        val mode = matching(display, fps) ?: return false
        if (mode.modeId == display.mode.modeId) return false
        val dm = activity.getSystemService(DisplayManager::class.java)
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
        // The HDMI link resyncs (the screen blanks a moment); wait so playback doesn't start unseen.
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val l = object : DisplayManager.DisplayListener {
                    override fun onDisplayChanged(id: Int) {
                        if (id == display.displayId && display.mode.modeId == mode.modeId && cont.isActive) {
                            dm.unregisterDisplayListener(this)
                            cont.resume(Unit)
                        }
                    }
                    override fun onDisplayAdded(id: Int) = Unit
                    override fun onDisplayRemoved(id: Int) = Unit
                }
                dm.registerDisplayListener(l, null)
                cont.invokeOnCancellation { dm.unregisterDisplayListener(l) }
            }
        }
        return true
    }

    /** Back to the TV's own mode (the menus run at 60 Hz). */
    fun reset(activity: Activity) {
        if (activity.window.attributes.preferredDisplayModeId == 0) return
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
