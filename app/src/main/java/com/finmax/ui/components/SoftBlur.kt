package com.finmax.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

/**
 * Artwork blurred once, when it loads, instead of with `Modifier.blur`: a RenderEffect blur is
 * recomputed on every frame anything on screen animates, and a full-screen one held the Chromecast
 * at ~100 ms a frame. The blurred bitmap is small and costs nothing per frame.
 *
 * [blur] matches `Modifier.blur(blur)` on an image [across] wide.
 */
@Composable
fun BlurredImage(url: String?, blur: Dp, modifier: Modifier = Modifier, across: Dp = 960.dp) {
    val context = LocalContext.current
    val request = remember(url, blur, across) {
        url?.let { ImageRequest.Builder(context).data(it).transformations(SoftBlur(blur.value / across.value)).build() }
    }
    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = modifier)
}

/** Blurs with a radius of [fraction] of the image width, after shrinking it to at most [MaxWidth] px. */
private class SoftBlur(private val fraction: Float) : Transformation() {
    override val cacheKey = "softBlur($fraction)"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val src = if (input.config == Bitmap.Config.HARDWARE) input.copy(Bitmap.Config.ARGB_8888, false) else input
        val w = minOf(src.width, MaxWidth)
        val h = (src.height * w.toFloat() / src.width).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        // RenderEffect's radius r is a Gaussian with sigma ≈ 0.58 r; three box passes of radius sigma match it.
        boxBlur(px, w, h, (fraction * w * 0.58f).roundToInt())
        val out = small.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private companion object { const val MaxWidth = 360 }
}

/** Three box-blur passes each way over ARGB pixels (≈ Gaussian with sigma ≈ [r]), edges clamped. */
internal fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    if (r < 1) return
    val tmp = IntArray(px.size)
    repeat(3) {
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }
}

private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val n = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int) = src[if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line]
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (i in -r..r) { val c = at(i); a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
        for (i in 0 until len) {
            dst[if (horizontal) line * w + i else i * w + line] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            val out = at(i - r); val inn = at(i + r + 1)
            a += (inn ushr 24) - (out ushr 24)
            rr += ((inn shr 16) and 255) - ((out shr 16) and 255)
            g += ((inn shr 8) and 255) - ((out shr 8) and 255)
            b += (inn and 255) - (out and 255)
        }
    }
}
