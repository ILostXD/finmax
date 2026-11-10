package com.finmax.ui.components

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
private const val SETTLE_MS = 800L
private const val CLIP_MS = 28_000L
private const val HAND_OFF_MS = 12_000L
/** How much more of a Home clip the details page plays before it stops. */
private const val CARRIED_CLIP_MS = 20_000L

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
 * @param reveal false loads and buffers the video but keeps it hidden and paused.
 * @param resumeMs play the title itself from here instead of a trailer or clips (Continue Watching).
 * @param onHandOff with [resumeMs]: called after [HAND_OFF_MS] of playback. Calling the `handOver`
 *   it gets parks this still-playing player in [PreviewHandOff] for the player screen to adopt and
 *   returns its position, so playback carries on instead of restarting.
 * @param fit 0 crops the video to fill the box, 1 letterboxes it like the player does (hand-off).
 * @param carryOver asked when the page leaves the screen: true parks the playing video in
 *   [PreviewHandOff] for the next page (Home card -> details) instead of stopping it.
 *
 * A player parked for this item is adopted instead of starting a new one: a clip plays on a little
 * and stops, the film itself (resume footage) carries on toward [onHandOff].
 */
@OptIn(UnstableApi::class)
@Composable
fun AmbientVideo(
    item: BaseItemDto,
    playing: Boolean,
    modifier: Modifier = Modifier,
    settleMs: Long = SETTLE_MS,
    resumeMs: Long? = null,
    onHandOff: ((handOver: () -> Long) -> Unit)? = null,
    reveal: Boolean = true,
    fit: Float = 0f,
    carryOver: () -> Boolean = { false },
) {
    if (LocalInspectionMode.current) return // previews and screenshot tests: artwork only
    @Suppress("NAME_SHADOWING") val playing = playing && !ScreensaverState.active // paused under the screensaver
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
    var handedOver by remember { mutableStateOf(false) }
    var adopted by remember { mutableStateOf<PreviewHandOff.Parked?>(null) }
    var snapIn by remember { mutableStateOf(false) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var shown by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val alpha by animateFloatAsState(
        if (shown && playing) 1f else 0f,
        if (snapIn) androidx.compose.animation.core.snap() else tween(if (shown) 1100 else 600), label = "ambient",
    )

    val live = mode != DetailVideo.OFF && onTop && foreground
    DisposableEffect(live, item.id) {
        if (!live) return@DisposableEffect onDispose { }
        val parked = PreviewHandOff.take(item.id)
        adopted = parked
        val p = parked?.player ?: newPlayer(context, app, sound)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlayerError(error: PlaybackException) { shown = false }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) shown = false }
        })
        player = p
        onDispose {
            val carry = carryOver() && !handedOver && shown
            shown = false
            player = null
            adopted = null
            when {
                handedOver -> Unit // a handed-over player now belongs to the player screen
                carry -> PreviewHandOff.put(item.id, p, film = resumeMs != null || parked?.film == true)
                else -> p.release()
            }
        }
    }

    // Loading starts right away; the picture (and sound) waits for [reveal], so the wait hides the load.
    val revealNow by rememberUpdatedState(reveal && playing)
    LaunchedEffect(player, texture) {
        val p = player ?: return@LaunchedEffect
        val view = texture ?: return@LaunchedEffect
        // ExoPlayer must only be touched on the main thread, whatever the effect's dispatcher.
        withContext(Dispatchers.Main.immediate) {
            p.setVideoTextureView(view)
            fun handOff() = onHandOff?.invoke {
                handedOver = true
                PreviewHandOff.put(item.id, p, film = true)
                p.currentPosition
            }
            val parked = adopted
            if (parked != null) {
                // Already playing on the page before: show it at once and carry on.
                p.videoSize.let { if (it.width > 0 && it.height > 0) aspect = it.width * it.pixelWidthHeightRatio / it.height }
                p.playWhenReady = playing
                snapIn = true
                shown = true
                delay(200)
                snapIn = false
                if (parked.film) {
                    delay(HAND_OFF_MS)
                    handOff()
                } else {
                    delay(CARRIED_CLIP_MS)
                    shown = false
                    delay(700)
                    p.pause()
                }
                return@withContext
            }
            delay(settleMs)
            /** Buffers paused, waits to be revealed, then plays; false if this file can't play here. */
            suspend fun begin(): Boolean {
                p.playWhenReady = false
                p.prepare()
                if (!p.awaitState { it.playbackState == Player.STATE_READY }) return false
                snapshotFlow { revealNow }.first { it }
                p.playWhenReady = true
                return p.awaitState { it.isPlaying }
            }
            val repo = app.repo
            if (resumeMs != null) {
                p.setMediaItem(MediaItem.fromUri(repo.staticStreamUrl(item.id)), resumeMs)
                if (!begin()) return@withContext // can't decode this file here: stay on the artwork
                delay(350) // past the post-seek keyframe smear
                shown = true
                if (onHandOff != null) {
                    delay(HAND_OFF_MS)
                    handOff()
                }
                return@withContext
            }
            val trailer = runCatching { repo.localTrailers(item.id).firstOrNull() }.getOrNull()
            if (trailer != null) {
                p.setMediaItem(MediaItem.fromUri(repo.staticStreamUrl(trailer.id)))
                if (begin()) shown = true
                return@withContext
            }
            val source = item.mediaSources?.firstOrNull()
            val runtimeMs = (item.runTimeTicks ?: 0) / 10_000
            val clipsAllowed = mode == DetailVideo.CLIPS &&
                item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO) &&
                runtimeMs > 10 * 60_000 && source?.supportsDirectPlay != false
            if (!clipsAllowed) return@withContext
            p.setMediaItem(MediaItem.fromUri(repo.staticStreamUrl(item.id, source?.id)), randomPoint(runtimeMs))
            if (!begin()) return@withContext // can't decode this file here: stay on the artwork
            while (true) {
                delay(350) // past the post-seek keyframe smear
                shown = true
                delay(CLIP_MS)
                shown = false
                delay(900) // let the fade finish before jumping
                p.seekTo(randomPoint(runtimeMs))
                delay(600) // let the player leave READY before waiting for it again
                if (!p.awaitState { it.isPlaying }) return@withContext
            }
        }
    }

    LaunchedEffect(player, playing) { player?.playWhenReady = playing }

    if (mode == DetailVideo.OFF) return
    Box(modifier.fillMaxSize().clipToBounds().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = fit))) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    texture = this
                }
            },
            // Faded by Compose, not View.alpha: GlassBackdrop(live) records this view, and a recording ignores View.alpha.
            modifier = Modifier.cover(aspect, fit).graphicsLayer { this.alpha = alpha },
        )
    }
}

/** A light player for background video: FFmpeg audio (the device decodes no AC3/E-AC3/DTS), stereo, small buffers. */
@OptIn(UnstableApi::class)
private fun newPlayer(context: android.content.Context, app: FinmaxApp, sound: Boolean): ExoPlayer {
    val repo = app.repo
    val renderers = object : DefaultRenderersFactory(context) {
        // Fold surround tracks to stereo here: left to the device mixer, 5.1 previews came out silent.
        override fun buildAudioSink(ctx: android.content.Context, floatOutput: Boolean, playbackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(stereoDownmix())).build()
        // FFmpeg is for audio only: software video decoding would swamp this device.
        override fun buildVideoRenderers(
            ctx: android.content.Context, mode: Int, selector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
            fallback: Boolean, handler: android.os.Handler, listener: androidx.media3.exoplayer.video.VideoRendererEventListener,
            joiningMs: Long, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
        ) {
            // FFmpeg is for audio only (above). This player may be handed to the next page's surface:
            // the Chromecast's decoder, moved to a new surface, keeps showing stale frames (smears,
            // pictures inside pictures), so build a fresh decoder on every surface change instead.
            @Suppress("DEPRECATION")
            out += object : androidx.media3.exoplayer.video.MediaCodecVideoRenderer(
                ctx, selector, joiningMs, fallback, handler, listener, 50,
            ) {
                override fun codecNeedsSetOutputSurfaceWorkaround(name: String) = true
            }
        }
    }.setEnableDecoderFallback(true)
        // The device decodes none of AC3, E-AC3 or DTS (most of the library); FFmpeg does.
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    val p = ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(app.http).setDefaultRequestProperties(mapOf("Authorization" to repo.authHeader)),
            ),
        )
        // Small buffers: start fast and stay light next to the UI on a 2 GB device.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_500, 10_000, 1_000, 1_000).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        .build()
    p.volume = if (sound) 1f else 0f
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !sound)
        .setPreferredAudioLanguage(app.settings.current.preferredAudioLang.ifEmpty { null })
        .build()
    return p
}

/**
 * Mixes 1-8 channel PCM (Android channel order) down to stereo: fronts to their side, centre to
 * both, surrounds to their side at -3 dB, LFE dropped. Scaled down so a full mix doesn't clip.
 */
@OptIn(UnstableApi::class)
private fun stereoDownmix() = ChannelMixingAudioProcessor().apply {
    val c = 0.7071f
    val layouts = mapOf(
        1 to listOf(c to c),
        2 to listOf(1f to 0f, 0f to 1f),
        3 to listOf(1f to 0f, 0f to 1f, c to c),
        4 to listOf(1f to 0f, 0f to 1f, c to 0f, 0f to c),
        5 to listOf(1f to 0f, 0f to 1f, c to c, c to 0f, 0f to c),
        6 to listOf(1f to 0f, 0f to 1f, c to c, 0f to 0f, c to 0f, 0f to c),
        7 to listOf(1f to 0f, 0f to 1f, c to c, 0f to 0f, 0.5f to 0.5f, c to 0f, 0f to c),
        8 to listOf(1f to 0f, 0f to 1f, c to c, 0f to 0f, c to 0f, 0f to c, c to 0f, 0f to c),
    )
    for ((n, rows) in layouts) {
        val gain = if (n <= 2) 1f else 1f / rows.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f) * 1.4f
        // Coefficient for input i to output o sits at i * outputs + o.
        putChannelMixingMatrix(ChannelMixingMatrix(n, 2, rows.flatMap { (l, r) -> listOf(l * gain, r * gain) }.toFloatArray()))
    }
}

/**
 * A still-playing background player on its way to the next page (Home card -> details, Home or
 * details -> player), which adopts it so nothing restarts.
 */
object PreviewHandOff {
    /** [film]: it plays the title itself (not a trailer or a random clip), so the player can take it over. */
    class Parked(val player: ExoPlayer, val film: Boolean)

    private var held: Pair<java.util.UUID, Parked>? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun put(id: java.util.UUID, player: ExoPlayer, film: Boolean) {
        held?.second?.player?.release()
        val parked = Parked(player, film)
        held = id to parked
        // Unclaimed (the next page failed to load, or Back came first): don't leave it playing.
        main.postDelayed({ if (held?.second === parked) { held = null; player.release() } }, 8_000)
    }

    /** The parked player if it is playing [id]; anything else parked is released. */
    fun take(id: java.util.UUID): Parked? {
        val h = held ?: return null
        held = null
        if (h.first == id) return h.second
        h.second.player.release()
        return null
    }
}

/** Somewhere in the middle of the film: past the opening titles, well before the ending. */
private fun randomPoint(runtimeMs: Long) = (runtimeMs * Random.nextDouble(0.12, 0.72)).toLong()

/** Waits until [done] holds for the player; false on error or after 20 s. */
private suspend fun Player.awaitState(done: (Player) -> Boolean): Boolean {
    if (done(this)) return true
    return withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            val l = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (done(player) && cont.isActive) { removeListener(this); cont.resume(true) }
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

/** Scales the child to cover the box at [aspect], centred, like ContentScale.Crop; [fit] 1 fits it instead. */
private fun Modifier.cover(aspect: Float, fit: Float) = layout { measurable, c ->
    val w = c.maxWidth
    val h = c.maxHeight
    val wide = w.toFloat() / h > aspect
    val (cw, ch) = if (wide) w to (w / aspect).roundToInt() else (h * aspect).roundToInt() to h
    val (fw, fh) = if (wide) (h * aspect).roundToInt() to h else w to (w / aspect).roundToInt()
    val tw = androidx.compose.ui.util.lerp(cw, fw, fit)
    val th = androidx.compose.ui.util.lerp(ch, fh, fit)
    val placeable = measurable.measure(Constraints.fixed(tw, th))
    layout(w, h) { placeable.place((w - tw) / 2, (h - th) / 2) }
}
