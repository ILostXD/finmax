package com.finmax.ui.components

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.finmax.FinmaxApp
import com.finmax.data.DetailVideo
import com.finmax.ui.LocalEntry
import com.finmax.ui.LocalNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

/** How long the artwork stays alone before video is tried: lets the page render and the user settle. */
private const val SETTLE_MS = 2_500L
private const val CLIP_MS = 28_000L

/**
 * Video behind a details page: the title's local trailer or, failing that, short clips of the film
 * itself from random points. The artwork shows first; the video fades in on its first frames and
 * fades out again on errors, at the end, and between clips.
 *
 * Draws into a [TextureView] (alpha-blendable, never focusable) rather than a `PlayerView`. The
 * player exists only while this page is the top of the back stack and the app is in the
 * foreground, so it never competes with the real player for the hardware decoder. Nothing is
 * reported to the server, so clips don't touch watch progress.
 *
 * @param playing false pauses and hides the video (e.g. the page has scrolled past the header).
 */
@OptIn(UnstableApi::class)
@Composable
fun AmbientVideo(item: BaseItemDto, playing: Boolean, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) return // previews and screenshot tests: artwork only
    val app = FinmaxApp.instance
    val settings by app.settings.state.collectAsState()
    val mode = settings.detailVideo
    val sound = settings.detailVideoSound
    val context = LocalContext.current
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val onTop = nav.top === entry

    var foreground by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) foreground = false
            if (e == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var shown by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val alpha by animateFloatAsState(if (shown && playing) 1f else 0f, tween(if (shown) 1100 else 600), label = "ambient")

    val live = mode != DetailVideo.OFF && onTop && foreground
    DisposableEffect(live, item.id) {
        if (!live) return@DisposableEffect onDispose { }
        val repo = app.repo
        val p = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    OkHttpDataSource.Factory(app.http).setDefaultRequestProperties(mapOf("Authorization" to repo.authHeader)),
                ),
            )
            // Small buffers: start fast and stay light next to the UI on a 2 GB device.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_500, 10_000, 1_000, 1_000).build())
            .build()
        p.volume = if (sound) 1f else 0f
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !sound)
            .build()
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlayerError(error: PlaybackException) { shown = false }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) shown = false }
        })
        player = p
        onDispose {
            shown = false
            player = null
            p.release()
        }
    }

    LaunchedEffect(player, texture) {
        val p = player ?: return@LaunchedEffect
        val view = texture ?: return@LaunchedEffect
        // ExoPlayer must only be touched on the main thread, whatever the effect's dispatcher.
        withContext(Dispatchers.Main.immediate) {
            p.setVideoTextureView(view)
            delay(SETTLE_MS)
            val repo = app.repo
            val trailer = runCatching { repo.localTrailers(item.id).firstOrNull() }.getOrNull()
            if (trailer != null) {
                p.setMediaItem(MediaItem.fromUri(repo.staticStreamUrl(trailer.id)))
                p.prepare()
                p.playWhenReady = true
                if (p.awaitPlaying()) shown = true
                return@withContext
            }
            val source = item.mediaSources?.firstOrNull()
            val runtimeMs = (item.runTimeTicks ?: 0) / 10_000
            val clipsAllowed = mode == DetailVideo.CLIPS &&
                item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO) &&
                runtimeMs > 10 * 60_000 && source?.supportsDirectPlay != false
            if (!clipsAllowed) return@withContext
            p.setMediaItem(MediaItem.fromUri(repo.staticStreamUrl(item.id, source?.id)), randomPoint(runtimeMs))
            p.prepare()
            p.playWhenReady = true
            while (true) {
                if (!p.awaitPlaying()) return@withContext // can't decode this file here: stay on the artwork
                delay(350) // past the post-seek keyframe smear
                shown = true
                delay(CLIP_MS)
                shown = false
                delay(900) // let the fade finish before jumping
                p.seekTo(randomPoint(runtimeMs))
                delay(600) // let the player leave READY before waiting for it again
            }
        }
    }

    LaunchedEffect(player, playing) { player?.playWhenReady = playing }

    if (mode == DetailVideo.OFF) return
    Box(modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    texture = this
                }
            },
            update = { it.alpha = alpha },
            modifier = Modifier.cover(aspect),
        )
    }
}

/** Somewhere in the middle of the film: past the opening titles, well before the ending. */
private fun randomPoint(runtimeMs: Long) = (runtimeMs * Random.nextDouble(0.12, 0.72)).toLong()

/** Waits until the player is actually playing; false on error or after 20 s. */
private suspend fun Player.awaitPlaying(): Boolean {
    if (isPlaying) return true
    return withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            val l = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && cont.isActive) { removeListener(this); cont.resume(true) }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (cont.isActive) { removeListener(this); cont.resume(false) }
                }
            }
            addListener(l)
            cont.invokeOnCancellation { removeListener(l) }
        }
    } ?: false
}

/** Scales the child to cover the box at [aspect], centred, like ContentScale.Crop. */
private fun Modifier.cover(aspect: Float) = layout { measurable, c ->
    val w = c.maxWidth
    val h = c.maxHeight
    val (cw, ch) = if (w.toFloat() / h > aspect) w to (w / aspect).roundToInt() else (h * aspect).roundToInt() to h
    val placeable = measurable.measure(Constraints.fixed(cw, ch))
    layout(w, h) { placeable.place((w - cw) / 2, (h - ch) / 2) }
}
