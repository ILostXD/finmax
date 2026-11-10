package com.finmax.playback

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * The app's face (Bricolage Grotesque) at Medium for subtitles the players draw themselves. A static
 * cut, because the bundled variable font defaults to ExtraBold and neither ExoPlayer's caption
 * style nor libass can pick a weight.
 */
object SubtitleFont {
    const val FAMILY = "Bricolage Grotesque"
    private const val ASSET = "fonts/BricolageGrotesque-Medium.ttf"

    fun typeface(context: Context): Typeface? = runCatching { Typeface.createFromAsset(context.assets, ASSET) }.getOrNull()

    /** A folder holding the font, for libass (it loads fonts from a folder, not assets). */
    fun dir(context: Context): File? = runCatching {
        val dir = File(context.filesDir, "subtitle-fonts").apply { mkdirs() }
        val dest = File(dir, ASSET.substringAfter('/'))
        if (!dest.exists()) context.assets.open(ASSET).use { input -> dest.outputStream().use { input.copyTo(it) } }
        dir
    }.getOrNull()
}
