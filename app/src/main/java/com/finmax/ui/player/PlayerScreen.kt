package com.finmax.ui.player

import com.finmax.ui.components.Ic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.asImageBitmap
import coil3.toBitmap
import coil3.network.httpHeaders
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.inset
import com.finmax.ui.theme.Fm.SafeH
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.data.SegmentAction
import com.finmax.playback.PlayRequest
import com.finmax.playback.PlayerController
import com.finmax.playback.DisplayModes
import com.finmax.playback.findActivity
import com.finmax.subtitles.SyncAssistant
import com.finmax.subtitles.isTextSubtitle
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.components.IconButton
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.episodeLabel
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.MediaSegmentType

enum class Panel { NONE, TRACKS, OPTIONS, SUBTITLES, SPEED, ASSISTANT, LINES, QUEUE }

/** Paused and left alone this long, the controls give way to the title over a darkened picture. */
private const val REST_MS = 10_000L

/** Consecutive auto-played episodes without any remote input ("are you still watching?"). */
private var autoplayStreak = 0

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(route: Route.Player) {
    val context = LocalContext.current
    val app = FinmaxApp.instance
    val nav = LocalNav.current
    val session = app.sessions.session.value ?: return
    val scope = rememberCoroutineScope()
    val pc = remember(route) {
        val adopted = if (route.mpv) null else com.finmax.ui.components.PreviewHandOff.take(route.itemId)?.player
        PlayerController(context, app, session, adopted, mpv = route.mpv || app.settings.current.playerEngine == com.finmax.data.PlayerEngine.MPV)
    }
    // Styled subtitles chosen: carry on in mpv from the same point with the same tracks.
    LaunchedEffect(pc.switchToMpvAt) {
        val at = pc.switchToMpvAt ?: return@LaunchedEffect
        nav.replace(route.copy(mpv = true, startMs = at, audioIndex = pc.audioIndex, subtitleIndex = pc.subtitleIndex ?: -1, mediaSourceId = pc.source?.id))
    }
    val assistant = remember(pc) { SyncAssistant(app, session, pc, scope) }
    val settings = app.settings.current

    // Arriving from a Home preview (it hands over its playing player): no controls, the picture
    // just carries on full screen.
    val handOff = route.startMs != null
    var controls by remember { mutableStateOf(!handOff) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var info by remember { mutableStateOf(false) }
    var resting by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(0L) }
    // Scrubbing (Netflix): Left/Right pauses and picks a point on a thumbnail strip; OK jumps there.
    var scrub by remember { mutableStateOf<Long?>(null) }
    var scrubWasPlaying by remember { mutableStateOf(false) }
    var playFocused by remember { mutableStateOf(false) }
    val trick = remember(pc.item?.id, pc.source?.id) { Trickplay.of(pc, session) }
    var upNextDismissed by remember(route) { mutableStateOf(false) }
    val skipped = remember(route) { mutableSetOf<String>() }
    val playReq = remember { FocusRequester() }
    val skipReq = remember { FocusRequester() }

    DisposableEffect(pc) {
        pc.start(
            PlayRequest(
                itemId = route.itemId,
                startMs = route.startMs ?: if (route.fromStart) 0 else null,
                mediaSourceId = route.mediaSourceId,
                audioIndex = route.audioIndex,
                subtitleIndex = route.subtitleIndex,
                playlistId = route.playlistId,
            ),
        )
        onDispose { pc.release() }
    }
    // The TV runs at the film's frame rate (24p on 23.976 Hz, no judder). Playback holds while the
    // HDMI link resyncs; the next episode keeps the mode, leaving the player restores the TV's own.
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(pc.source?.id) {
        if (!settings.matchFrameRate || activity == null) return@LaunchedEffect
        val v = pc.source?.mediaStreams?.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO } ?: return@LaunchedEffect
        val fps = v.realFrameRate ?: v.averageFrameRate ?: return@LaunchedEffect
        val display = activity.window.decorView.display ?: return@LaunchedEffect
        val mode = DisplayModes.matching(display, fps) ?: return@LaunchedEffect
        if (mode.modeId == display.mode.modeId) return@LaunchedEffect
        pc.player.pause()
        DisplayModes.switchTo(activity, fps)
        pc.player.play()
    }
    DisposableEffect(Unit) {
        onDispose { if (activity != null && nav.top.route !is Route.Player) DisplayModes.reset(activity) }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                pc.player.pause()
                pc.reportProgress()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Clock for controls + segment logic (4 Hz is plenty; subtitles have their own frame loop).
    LaunchedEffect(pc) {
        while (true) {
            position = pc.positionMs
            if (controls && panel == Panel.NONE && !info && pc.isPlaying && System.currentTimeMillis() - lastInput > 6_000) controls = false
            delay(250)
        }
    }

    LaunchedEffect(pc.isPlaying, pc.loading, pc.buffering, lastInput, panel, pc.error, scrub) {
        resting = false
        // Only a real pause: not playing because it is loading or buffering doesn't count.
        if (pc.isPlaying || pc.loading || pc.buffering || panel != Panel.NONE || pc.error != null || scrub != null) return@LaunchedEffect
        delay(REST_MS)
        resting = true
        controls = true
    }

    // The age rating, shown for a moment once the picture starts (Netflix's "RATED 13+").
    var rated by remember(route) { mutableStateOf(false) }
    LaunchedEffect(pc.loading, pc.item?.id) {
        if (pc.loading || pc.item?.officialRating == null) return@LaunchedEffect
        delay(800)
        rated = true
        delay(6_000)
        rated = false
    }

    // Spinner only for waits worth seeing; an adopted preview is already playing.
    val waiting = (pc.loading && !pc.adoptedPlayer) || pc.buffering
    var spinner by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        if (waiting) { delay(300); spinner = true } else spinner = false
    }
    // While waiting there is only the spinner.
    LaunchedEffect(spinner) { if (spinner) controls = false }

    val segment = pc.segments.firstOrNull { position in (it.startTicks / 10_000) until (it.endTicks / 10_000) - 500 }
    val segAction = when (segment?.type) {
        MediaSegmentType.INTRO, MediaSegmentType.RECAP, MediaSegmentType.PREVIEW -> settings.skipIntro
        MediaSegmentType.OUTRO -> settings.skipCredits
        MediaSegmentType.COMMERCIAL -> SegmentAction.AUTO
        else -> SegmentAction.OFF
    }
    LaunchedEffect(segment?.id, segAction) {
        val s = segment ?: return@LaunchedEffect
        if (segAction == SegmentAction.AUTO && s.id.toString() !in skipped && !(s.type == MediaSegmentType.OUTRO && pc.nextEpisode != null)) {
            skipped += s.id.toString()
            pc.player.seekTo(s.endTicks / 10_000)
            pc.notice = "Skipped ${s.type.name.lowercase()}"
        }
    }
    val showSkip = segment != null && segAction == SegmentAction.ASK && segment.id.toString() !in skipped &&
        !(segment.type == MediaSegmentType.OUTRO && pc.nextEpisode != null)

    // Up next: from the credits segment, or the last 30 s when the server has no segment data.
    val duration = pc.durationMs
    val outroStart = pc.segments.firstOrNull { it.type == MediaSegmentType.OUTRO }?.startTicks?.div(10_000)
    val next = pc.nextEpisode
    val showUpNext = next != null && !upNextDismissed && duration > 0 &&
        (pc.ended || position >= (outroStart ?: (duration - 30_000)))
    val stillWatching = autoplayStreak >= 3

    LaunchedEffect(pc.ended) {
        if (pc.ended && (next == null || !settings.autoplayNext)) {
            if (next == null) nav.pop()
        }
    }

    fun close() = nav.pop()
    fun playNext() {
        val n = pc.nextEpisode ?: return
        nav.replace(Route.Player(n.id, fromStart = true, playlistId = route.playlistId))
    }
    fun playQueued(id: java.util.UUID) {
        if (id == route.itemId) { panel = Panel.NONE; return }
        nav.replace(Route.Player(id, playlistId = route.playlistId))
    }

    val focusedControls = controls && panel == Panel.NONE
    LaunchedEffect(focusedControls) {
        if (focusedControls) { delay(30); runCatching { playReq.requestFocus() } }
    }
    LaunchedEffect(showSkip, controls) {
        if (showSkip && !controls) { delay(30); runCatching { skipReq.requestFocus() } }
    }
    // With nothing on screen, the player surface itself holds focus so remote keys still arrive.
    val rootReq = remember { FocusRequester() }
    val idle = !controls && panel == Panel.NONE && !showSkip && !(showUpNext && panel == Panel.NONE) && pc.error == null
    LaunchedEffect(idle) {
        if (idle) { delay(30); runCatching { rootReq.requestFocus() } }
    }

    fun startScrub(forward: Boolean, repeat: Int) {
        scrubWasPlaying = pc.player.isPlaying
        pc.player.pause()
        controls = false
        scrub = scrubStep(pc.positionMs, forward, repeat, duration)
    }
    fun commitScrub() {
        val to = scrub ?: return
        scrub = null
        pc.player.seekTo(to)
        pc.player.play()
    }

    BackHandler {
        when {
            scrub != null -> { scrub = null; if (scrubWasPlaying) pc.player.play() }
            resting -> { resting = false; controls = true }
            panel == Panel.LINES || panel == Panel.ASSISTANT -> { assistant.cancelPreview(); panel = Panel.SUBTITLES }
            panel == Panel.SPEED || panel == Panel.SUBTITLES -> panel = Panel.OPTIONS
            panel != Panel.NONE -> panel = Panel.NONE
            info -> info = false
            controls -> controls = false
            else -> close()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                autoplayStreak = 0
                val code = e.nativeKeyEvent.keyCode
                // Any key lifts the paused title screen and then does its usual job (Back via the back handler).
                if (resting && code != KeyEvent.KEYCODE_BACK) resting = false
                val left = code == KeyEvent.KEYCODE_DPAD_LEFT
                val right = code == KeyEvent.KEYCODE_DPAD_RIGHT
                scrub?.let { at ->
                    when {
                        left || right -> scrub = scrubStep(at, right, e.nativeKeyEvent.repeatCount, duration)
                        code == KeyEvent.KEYCODE_BACK -> return@onPreviewKeyEvent false
                        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY -> commitScrub()
                    }
                    return@onPreviewKeyEvent true
                }
                // Left/Right with nothing on screen, or on the play button, starts scrubbing.
                if ((left || right) && duration > 0 && panel == Panel.NONE && pc.error == null && !spinner &&
                    (playFocused && controls || !controls && !showUpNext && !showSkip)
                ) {
                    startScrub(right, e.nativeKeyEvent.repeatCount)
                    return@onPreviewKeyEvent true
                }
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (pc.player.isPlaying) pc.player.pause() else pc.player.play(); controls = true; true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { pc.player.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { pc.player.pause(); controls = true; true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { pc.player.seekTo(pc.positionMs + 30_000); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { pc.player.seekTo(pc.positionMs - 10_000); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        val p = pc.previousEpisode
                        if (pc.positionMs > 10_000 || p == null) pc.player.seekTo(0) else nav.replace(Route.Player(p.id, fromStart = true, playlistId = route.playlistId))
                        true
                    }
                    KeyEvent.KEYCODE_CAPTIONS -> { panel = Panel.TRACKS; controls = true; true }
                    else -> {
                        if (controls || panel != Panel.NONE || showUpNext || showSkip || pc.error != null) return@onPreviewKeyEvent false
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                if (pc.player.isPlaying) pc.player.pause() else pc.player.play()
                                controls = true; true
                            }
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { controls = true; true }
                            else -> false
                        }
                    }
                }
            }
            .focusRequester(rootReq)
            .focusable(),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    // The view must never take focus, or it swallows D-pad keys meant for the Compose UI.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Embedded subtitles shown by the player itself (until finmax has their text): white with a soft shadow, like finmax's.
                    subtitleView?.setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                            if (settings.subtitleShadow == com.finmax.data.SubShadow.NONE) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
                            else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                            android.graphics.Color.BLACK, com.finmax.playback.SubtitleFont.typeface(it),
                        ),
                    )
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    player = pc.player
                    // An adopted player moves from the previous page's TextureView to this surface
                    // mid-stream; the decoder then runs on without a keyframe and shows garbage. Once
                    // the surface exists, re-seek to the same spot so decoding restarts cleanly.
                    if (pc.adoptedPlayer) (videoSurfaceView as? android.view.SurfaceView)?.holder?.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(h: android.view.SurfaceHolder) {
                            post {
                                h.removeCallback(this)
                                pc.player.seekTo(pc.player.currentPosition)
                            }
                        }
                        override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                        override fun surfaceDestroyed(h: android.view.SurfaceHolder) = Unit
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Subtitles step aside for the scrub view and the spinner.
        if (scrub == null && !spinner) SubtitleOverlay(pc, liftForControls = controls)

        // Paused a while: darkened picture with the title on the left, under the controls (Netflix).
        AnimatedVisibility(resting && scrub == null && !spinner, enter = fadeIn(tween(900)), exit = fadeOut(tween(250)), modifier = Modifier.fillMaxSize()) {
            RestScreen(pc)
        }

        AnimatedVisibility(spinner, enter = fadeIn(tween(250)), exit = fadeOut(tween(250)), modifier = Modifier.fillMaxSize()) {
            Spinner()
        }

        pc.item?.officialRating?.let { rating ->
            RatedTag(
                rating, rated && !controls && !resting && !spinner && scrub == null && panel == Panel.NONE,
                Modifier.align(Alignment.TopStart).padding(start = Fm.SafeH, top = 44.dp),
            )
        }

        AnimatedVisibility(scrub != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(250)), modifier = Modifier.fillMaxSize()) {
            // Keeps the last target while fading out.
            var shown by remember { mutableLongStateOf(scrub ?: 0L) }
            scrub?.let { shown = it }
            ScrubView(shown, duration, trick)
        }

        AnimatedVisibility(controls && !spinner && scrub == null && panel != Panel.LINES, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(
                pc, position, duration, playReq, onPlayFocus = { playFocused = it },
                onPanel = { panel = it }, onBack = ::close, onNext = ::playNext,
            )
        }

        if (showSkip && !controls) {
            PillButton(
                "Skip ${segmentName(segment!!.type)}",
                {
                    skipped += segment.id.toString()
                    pc.player.seekTo(segment.endTicks / 10_000)
                },
                primary = true,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 64.dp).focusRequester(skipReq),
            )
        }

        if (showUpNext && next != null && panel == Panel.NONE) {
            UpNext(
                next, pc,
                countdown = if (settings.autoplayNext && !stillWatching) settings.autoplayCountdownSec else null,
                ended = pc.ended,
                stillWatching = stillWatching,
                onPlay = { autoplayStreak = 0; playNext() },
                onAuto = { autoplayStreak++; playNext() },
                onDismiss = { upNextDismissed = true; if (pc.ended) close() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = if (controls) 170.dp else 56.dp),
            )
        }

        if (info) PlaybackInfo(pc, Modifier.align(Alignment.TopStart).padding(32.dp))

        when (panel) {
            Panel.TRACKS -> TracksPanel(pc)
            Panel.QUEUE -> QueuePanel(pc, session, ::playQueued)
            Panel.OPTIONS -> OptionsPanel(pc, onPanel = { panel = it }, onInfo = { info = !info; panel = Panel.NONE })
            Panel.SPEED -> TrackPanel("Playback speed", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { (it * 100).toInt() to "${it}×" }, (pc.speed * 100).toInt()) {
                pc.changeSpeed(it / 100f); panel = Panel.NONE
            }
            Panel.SUBTITLES -> SubtitlePanel(pc, settings.subtitleDelayStepMs.toLong(), onAssistant = { panel = Panel.ASSISTANT; assistant.run() }, onLines = { panel = Panel.LINES; controls = false }, onClose = { panel = Panel.NONE })
            Panel.ASSISTANT -> AssistantPanel(assistant, pc, onApplied = { panel = Panel.NONE })
            Panel.LINES -> LineSyncPanel(pc, assistant, onDone = { panel = Panel.SUBTITLES })
            Panel.NONE -> Unit
        }

        pc.notice?.let { n ->
            LaunchedEffect(n) { delay(4_000); if (pc.notice == n) pc.notice = null }
            Text(
                n, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    .background(Fm.Surface.copy(alpha = 0.92f), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        pc.error?.let { err ->
            Box(Modifier.fillMaxSize().background(Fm.Bg.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Playback problem", style = MaterialTheme.typography.headlineMedium)
                    Text(err, color = Fm.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val retry = remember { FocusRequester() }
                        LaunchedEffect(Unit) { delay(50); runCatching { retry.requestFocus() } }
                        PillButton("Try again", {
                            pc.start(PlayRequest(route.itemId, pc.positionMs.takeIf { it > 0 }, pc.source?.id, pc.audioIndex, pc.subtitleIndex ?: -1, forceTranscode = true))
                        }, primary = true, modifier = Modifier.focusRequester(retry))
                        PillButton("Back", ::close)
                    }
                }
            }
        }
    }
}

private fun segmentName(t: MediaSegmentType) = when (t) {
    MediaSegmentType.INTRO -> "Intro"
    MediaSegmentType.RECAP -> "Recap"
    MediaSegmentType.OUTRO -> "Credits"
    MediaSegmentType.PREVIEW -> "Preview"
    else -> "Ad"
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SubtitleOverlay(pc: PlayerController, liftForControls: Boolean) {
    val s = FinmaxApp.instance.settings.state.value
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pc) {
        while (true) {
            withFrameMillis { }
            val track = pc.previewCues ?: pc.cues
            text = track?.textAt(pc.positionMs, pc.previewTiming ?: pc.timing)
        }
    }
    val t = text ?: return
    Box(Modifier.fillMaxSize().padding(bottom = if (liftForControls) 180.dp else (540 * s.subtitleBottomPct / 100).dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            t,
            textAlign = TextAlign.Center,
            style = TextStyle(
                color = Color.White,
                fontFamily = com.finmax.ui.theme.BodyFont, // the app's face, like the Settings preview
                fontSize = (26 * s.subtitleScale).sp,
                fontWeight = FontWeight.Medium,
                lineHeight = (33 * s.subtitleScale).sp,
                shadow = if (s.subtitleBackground) null else s.subtitleShadow.shadow(s.subtitleScale),
            ),
            modifier = Modifier.widthIn(max = 820.dp)
                .then(if (s.subtitleBackground) Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 2.dp) else Modifier),
        )
    }
}

/** The text shadow for a [com.finmax.data.SubShadow] choice at a given text scale. */
fun com.finmax.data.SubShadow.shadow(scale: Float): Shadow? = when (this) {
    com.finmax.data.SubShadow.NONE -> null
    com.finmax.data.SubShadow.SOFT -> Shadow(Color.Black, blurRadius = 6f)
    com.finmax.data.SubShadow.DROP -> Shadow(Color.Black.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(3f * scale, 3f * scale), blurRadius = 7f * scale)
}

@Composable
fun SeekBar(position: Long, duration: Long, focused: Boolean, modifier: Modifier = Modifier, buffered: Long = 0L) {
    fun frac(ms: Long) = if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val pill = androidx.compose.foundation.shape.CircleShape
    // Pill track; the loaded and played parts are pills too, so the ends stay round.
    Box(modifier.fillMaxWidth().height(if (focused) 8.dp else 6.dp).clip(pill).background(Color.White.copy(alpha = 0.2f))) {
        if (buffered > position) Box(Modifier.fillMaxHeight().fillMaxWidth(frac(buffered)).clip(pill).background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac(position)).clip(pill).background(Fm.Accent))
    }
}

/** 10 s steps on a 10 s grid (Netflix's), faster while the key is held. */
private fun scrubStep(from: Long, forward: Boolean, repeat: Int, duration: Long): Long {
    val step = when { repeat > 30 -> 60_000L; repeat > 10 -> 30_000L; else -> 10_000L }
    val grid = 10_000L
    val snapped = if (forward) from / grid * grid + step else (from + grid - 1) / grid * grid - step
    return snapped.coerceIn(0L, (duration - 1_000).coerceAtLeast(0))
}

/**
 * Jellyfin's trickplay thumbnails: sheets of tiles, one every [TrickplayInfo.interval] ms, fetched
 * per sheet on demand. Null when the server has none for this file.
 */
private class Trickplay(
    val info: org.jellyfin.sdk.model.api.TrickplayInfo,
    private val urlOf: (Int) -> String,
    private val auth: String,
) {
    val sheets = androidx.compose.runtime.mutableStateMapOf<Int, androidx.compose.ui.graphics.ImageBitmap>()
    private val requested = mutableSetOf<Int>()
    private val perSheet = (info.tileWidth * info.tileHeight).coerceAtLeast(1)

    fun indexAt(ms: Long) = (ms / info.interval.coerceAtLeast(1)).toInt().coerceAtMost(info.thumbnailCount - 1)

    suspend fun load(context: android.content.Context, index: Int) {
        val sheet = index / perSheet
        if (!requested.add(sheet)) return
        val request = coil3.request.ImageRequest.Builder(context).data(urlOf(sheet))
            .httpHeaders(coil3.network.NetworkHeaders.Builder().set("Authorization", auth).build())
            .build()
        val image = (coil3.SingletonImageLoader.get(context).execute(request) as? coil3.request.SuccessResult)?.image
        if (image == null) { requested.remove(sheet); return }
        sheets[sheet] = image.toBitmap().asImageBitmap()
    }

    /** The sheet and the tile's top-left corner in it, or null until that sheet has loaded. */
    fun tile(index: Int): Pair<androidx.compose.ui.graphics.ImageBitmap, androidx.compose.ui.unit.IntOffset>? {
        val sheet = sheets[index / perSheet] ?: return null
        val within = index % perSheet
        return sheet to androidx.compose.ui.unit.IntOffset(within % info.tileWidth * info.width, within / info.tileWidth * info.height)
    }

    companion object {
        fun of(pc: PlayerController, session: com.finmax.data.Session): Trickplay? {
            val item = pc.item ?: return null
            val source = pc.source?.id ?: item.mediaSources?.firstOrNull()?.id ?: return null
            val sizes = item.trickplay?.get(source) ?: item.trickplay?.values?.firstOrNull() ?: return null
            // The smallest set that is still sharp at ~220 dp.
            val info = sizes.values.filter { it.width >= 300 }.minByOrNull { it.width } ?: sizes.values.maxByOrNull { it.width } ?: return null
            if (info.thumbnailCount <= 0 || info.tileWidth <= 0) return null
            val base = session.server.url.trimEnd('/')
            return Trickplay(info, { sheet -> "$base/Videos/${item.id}/Trickplay/${info.width}/$sheet.jpg?mediaSourceId=$source" }, session.authHeader)
        }
    }
}

private val ThumbWidth = 224.dp

/**
 * Netflix's scrubbing view: the paused picture, a strip of thumbnails 10 s apart with the target
 * centred and outlined, and the timeline underneath. Nothing else on screen.
 */
@Composable
private fun ScrubView(target: Long, duration: Long, trick: Trickplay?) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (trick != null) {
                val h = ThumbWidth * (trick.info.height.toFloat() / trick.info.width)
                val gap = 12.dp
                Box(Modifier.fillMaxWidth().height(h + 8.dp), contentAlignment = Alignment.Center) {
                    for (k in -3..3) {
                        val ms = target + k * 10_000L
                        if (ms < 0 || ms > duration) continue
                        val index = trick.indexAt(ms)
                        LaunchedEffect(index / (trick.info.tileWidth * trick.info.tileHeight)) { trick.load(context, index) }
                        val tile = trick.tile(index)
                        val shape = RoundedCornerShape(8.dp)
                        androidx.compose.foundation.Canvas(
                            Modifier.offset(x = (ThumbWidth + gap) * k).width(ThumbWidth).height(h)
                                .clip(shape).background(Color.White.copy(alpha = 0.08f))
                                .then(if (k == 0) Modifier.border(2.5.dp, Fm.Focus, shape) else Modifier.graphicsLayer { alpha = 0.8f }),
                        ) {
                            tile?.let { (sheet, at) ->
                                drawImage(
                                    sheet, srcOffset = at, srcSize = androidx.compose.ui.unit.IntSize(trick.info.width, trick.info.height),
                                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
            Row(Modifier.padding(horizontal = Fm.SafeH), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(target), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp), textAlign = TextAlign.End)
                SeekBar(target, duration, focused = false, Modifier.weight(1f).padding(horizontal = 16.dp))
                Text(fmt(duration - target), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp))
            }
        }
    }
}

/**
 * "RATED 13+" with Netflix's entrance: the accent line grows from its middle, then the text rises
 * into view from below its own edge. It leaves the same way, reversed.
 */
@Composable
private fun RatedTag(rating: String, visible: Boolean, modifier: Modifier) {
    val t = androidx.compose.animation.core.updateTransition(visible, label = "rated")
    val ease = androidx.compose.animation.core.FastOutSlowInEasing
    val line by t.animateFloat({ if (targetState) tween(450, easing = ease) else tween(350, delayMillis = 250, easing = ease) }, label = "line") { if (it) 1f else 0f }
    val text by t.animateFloat({ if (targetState) tween(550, delayMillis = 300, easing = ease) else tween(300, easing = ease) }, label = "text") { if (it) 1f else 0f }
    if (line == 0f && text == 0f) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(4.dp).height(40.dp)
                .graphicsLayer { scaleY = line; alpha = line.coerceAtMost(1f) }
                .background(Fm.Accent, RoundedCornerShape(2.dp)),
        )
        Box(Modifier.padding(start = 14.dp).clipToBounds()) {
            Text(
                "RATED $rating", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.graphicsLayer { translationY = size.height * (1f - text); alpha = text },
            )
        }
    }
}

/** Waiting: the picture darkens and an amethyst arc turns on a faint ring. No words. */
@Composable
private fun Spinner() {
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
            val w = 5.dp.toPx()
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            inset(w / 2) {
                drawCircle(Color.White.copy(alpha = 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                drawArc(Fm.Accent, startAngle = angle, sweepAngle = 110f, useCenter = false, style = stroke)
            }
        }
    }
}

/** Paused for a while: the picture darkens and the title (its logo when there is one) sits on the left. */
@Composable
private fun RestScreen(pc: PlayerController) {
    val item = pc.item ?: return
    val logo = remember(item.id) { FinmaxApp.instance.repo.logo(item) }
    val episode = item.type == org.jellyfin.sdk.model.api.BaseItemKind.EPISODE
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.82f), 0.75f to Color.Black.copy(alpha = 0.45f)))) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = Fm.SafeH).width(600.dp)) {
            com.finmax.ui.components.Overline("You're watching")
            Spacer(Modifier.height(14.dp))
            if (logo != null) {
                AsyncImage(logo, item.name, contentScale = androidx.compose.ui.layout.ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.height(120.dp).width(440.dp))
            } else {
                com.finmax.ui.home.BigTitle((if (episode) item.seriesName else null) ?: item.name ?: "")
            }
            if (episode) {
                Text(
                    listOfNotNull(item.episodeLabel(), item.name).joinToString("  "),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp),
                )
            }
            com.finmax.ui.components.MetaLine(
                listOfNotNull(item.productionYear?.toString(), item.runTimeTicks?.let { com.finmax.ui.home.formatRuntime(it / 10_000) }),
                badge = item.officialRating, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Netflix's player controls, top bar mirrored: the title top left, Back and Play from beginning top
 * right; at the bottom a play state dot, the timeline (Left/Right seek, OK plays and pauses) and a
 * row of audio/subtitle choices, Other… for every track and a gear for the rest.
 */
@Composable
private fun Controls(
    pc: PlayerController,
    position: Long,
    duration: Long,
    playReq: FocusRequester,
    onPlayFocus: (Boolean) -> Unit,
    onPanel: (Panel) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val item = pc.item
    var hint by remember { mutableStateOf<String?>(null) }
    fun Modifier.hint(label: String) = onFocusChanged { if (it.isFocused) hint = label else if (hint == label) hint = null }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))))

        Column(Modifier.padding(start = Fm.SafeH, top = 36.dp, end = 300.dp)) {
            val episode = item?.type == org.jellyfin.sdk.model.api.BaseItemKind.EPISODE
            Text((if (episode) item?.seriesName else null) ?: item?.name ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            if (episode) {
                Text(
                    listOfNotNull(item?.episodeLabel(), item?.name).joinToString("  "),
                    style = MaterialTheme.typography.labelLarge, color = Fm.TextDim, maxLines = 1, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(end = Fm.SafeH, top = 30.dp), horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(Ic.Back, "Back", onBack, Modifier.hint("Back"))
                IconButton(Ic.Replay, "Play from beginning", { pc.player.seekTo(0); pc.player.play() }, Modifier.hint("Play from beginning"))
            }
            Text(hint ?: "", style = MaterialTheme.typography.labelMedium, color = Fm.TextDim, modifier = Modifier.padding(top = 8.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(start = Fm.SafeH, end = Fm.SafeH, bottom = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Focus rests here (Netflix): OK plays/pauses, Left/Right scrubs (handled by the screen).
                IconButton(
                    if (pc.isPlaying) androidx.compose.material.icons.Icons.Rounded.Pause else androidx.compose.material.icons.Icons.Rounded.PlayArrow, if (pc.isPlaying) "Pause" else "Play",
                    { if (pc.player.isPlaying) pc.player.pause() else pc.player.play() },
                    Modifier.focusRequester(playReq).onFocusChanged { onPlayFocus(it.isFocused) },
                    size = 52.dp,
                )
                Text(fmt(position), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 18.dp))
                SeekBar(position, duration, focused = false, Modifier.weight(1f).padding(horizontal = 16.dp), buffered = pc.player.bufferedPosition)
                Text(fmt(duration - position), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(20.dp))
            TrackChips(pc, onPanel, onNext, Modifier.fillMaxWidth())
        }
    }
}

private class Preset(val label: String, val audio: Int, val subtitle: Int)

/**
 * Netflix-style one-press choices: the main audio language, the same with subtitles (preferred
 * subtitle language, else the audio's), and one other dubbed language. The rest is under Other….
 */
private fun trackPresets(pc: PlayerController): List<Preset> {
    val audios = pc.audioStreams.sortedBy { it.index }
    if (audios.isEmpty()) return emptyList()
    val main = audios.firstOrNull { it.isDefault } ?: audios.first()
    val other = audios.firstOrNull { (it.language ?: it.displayTitle) != (main.language ?: main.displayTitle) }
    val subLang = FinmaxApp.instance.settings.current.preferredSubtitleLang.ifEmpty { main.language ?: "" }
    val pool = pc.subtitleStreams.filter { !it.isForced }
    val subs = if (subLang.isEmpty()) pool else pool.filter { it.language.equals(subLang, true) }
    val sub = subs.firstOrNull { it.isTextSubtitle } ?: subs.firstOrNull()
    return listOfNotNull(
        Preset(langName(main), main.index, -1),
        sub?.let {
            val same = it.language.equals(main.language, true)
            Preset(langName(main) + if (same) " with subtitles" else " with ${langName(it)} subtitles", main.index, it.index)
        },
        other?.let { Preset(langName(it), it.index, -1) },
    )
}

private fun langName(s: org.jellyfin.sdk.model.api.MediaStream): String {
    val code = s.language?.takeIf { it.isNotBlank() && !it.equals("und", true) }
    @Suppress("DEPRECATION")
    val name = code?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH) }?.takeIf { it.isNotEmpty() && !it.equals(code, true) }
    // Untagged: the file's main track is its original language; anything else goes by its title.
    return name ?: code?.uppercase() ?: if (s.isDefault) "Original" else s.displayTitle?.substringBefore(" - ") ?: "Track ${s.index}"
}

@Composable
private fun TrackChips(pc: PlayerController, onPanel: (Panel) -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val presets = remember(pc.source) { trackPresets(pc) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (pc.nextEpisode != null) PillButton(if (pc.queueIsPlaylist) "Next" else "Next episode", onNext, icon = Ic.SkipNext)
        if (pc.queue.size > 1) PillButton(if (pc.queueIsPlaylist) "Playlist" else "Episodes", { onPanel(Panel.QUEUE) }, icon = Ic.Playlist)
        presets.forEach { t ->
            val on = t.audio == pc.audioIndex && t.subtitle == (pc.subtitleIndex ?: -1)
            PillButton(t.label, { pc.selectTracks(t.audio, t.subtitle) }, icon = if (on) Ic.Check else null)
        }
        PillButton("Other…", { onPanel(Panel.TRACKS) })
        IconButton(Ic.Settings, "Options", { onPanel(Panel.OPTIONS) })
    }
}

/** The show's episodes (or the playlist) with this one marked; picking another plays it. */
@Composable
private fun QueuePanel(pc: PlayerController, session: com.finmax.data.Session, onPlay: (java.util.UUID) -> Unit) {
    val current = pc.item?.id
    com.finmax.ui.library.SidePanel(if (pc.queueIsPlaylist) "Playlist" else pc.item?.seriesName ?: "Episodes", width = 560.dp) {
        val list = androidx.compose.foundation.lazy.rememberLazyListState(pc.queue.indexOfFirst { it.id == current }.coerceAtLeast(0))
        androidx.compose.foundation.lazy.LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pc.queue.size) { i ->
                val e = pc.queue[i]
                val req = remember { FocusRequester() }
                if (e.id == current) LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
                QueueRow(e, e.id == current, pc.queueIsPlaylist, session, { onPlay(e.id) }, Modifier.fillMaxWidth().focusRequester(req))
            }
        }
    }
}

@Composable
private fun QueueRow(
    e: org.jellyfin.sdk.model.api.BaseItemDto, playing: Boolean, playlist: Boolean, session: com.finmax.data.Session,
    onClick: () -> Unit, modifier: Modifier,
) {
    val repo = com.finmax.FinmaxApp.instance.repo
    var focused by remember { mutableStateOf(false) }
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = Fm.Text,
            focusedContainerColor = Fm.Focus, focusedContentColor = Fm.OnFocus,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(Fm.Surface)) {
                repo.landscape(e, 300)?.let {
                    coil3.compose.AsyncImage(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                e.userData?.playedPercentage?.takeIf { it > 0 && e.userData?.played != true }?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((p / 100).toFloat()).height(3.dp).background(Fm.Accent))
                }
                if (playing) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))) {
                    androidx.tv.material3.Icon(Ic.Play, "Playing", Modifier.align(Alignment.Center).size(26.dp), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            Column(Modifier.weight(1f)) {
                val dim = if (focused) Fm.OnFocus.copy(alpha = 0.7f) else Fm.TextDim
                val over = if (playlist) e.seriesName ?: e.productionYear?.toString()
                    else listOfNotNull(e.parentIndexNumber?.let { "S$it" }, e.indexNumber?.let { "E$it" }).joinToString(":").ifEmpty { null }
                over?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (playing && !focused) Fm.Accent else dim) }
                Text(e.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                e.runTimeTicks?.let { Text("${it / 600_000_000} min", style = MaterialTheme.typography.labelMedium, color = dim) }
            }
        }
    }
}

/** Every audio and subtitle track, picked without closing (Back closes). */
@Composable
private fun TracksPanel(pc: PlayerController) {
    com.finmax.ui.library.SidePanel("Audio & subtitles", width = 460.dp) {
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Audio", style = MaterialTheme.typography.titleMedium, color = Fm.Accent) }
            items(pc.audioStreams.size) { i ->
                val a = pc.audioStreams[i]
                val req = remember { FocusRequester() }
                if (a.index == pc.audioIndex || (pc.audioIndex == null && i == 0)) LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
                com.finmax.ui.components.PopoverItem(
                    a.displayTitle ?: a.language ?: "Track ${a.index}", { pc.selectAudio(a.index) },
                    primary = a.index == pc.audioIndex, modifier = Modifier.fillMaxWidth().focusRequester(req),
                )
            }
            item { Text("Subtitles", style = MaterialTheme.typography.titleMedium, color = Fm.Accent, modifier = Modifier.padding(top = 10.dp)) }
            item { com.finmax.ui.components.PopoverItem("Off", { pc.selectSubtitle(-1) }, primary = pc.subtitleIndex == null, modifier = Modifier.fillMaxWidth()) }
            items(pc.subtitleStreams.size) { i ->
                val s = pc.subtitleStreams[i]
                val label = listOfNotNull(s.displayTitle ?: s.language, "external".takeIf { s.isExternal }, "image".takeIf { !s.isTextSubtitle }).joinToString(" · ")
                com.finmax.ui.components.PopoverItem(label, { pc.selectSubtitle(s.index) }, primary = s.index == pc.subtitleIndex, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The gear: speed, subtitle timing and sync, and the technical playback info. */
@Composable
private fun OptionsPanel(pc: PlayerController, onPanel: (Panel) -> Unit, onInfo: () -> Unit) {
    com.finmax.ui.library.SidePanel("Options") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
            com.finmax.ui.components.PopoverItem("Playback speed · ${pc.speed}×", { onPanel(Panel.SPEED) }, modifier = Modifier.fillMaxWidth().focusRequester(first))
            com.finmax.ui.components.PopoverItem(
                if (pc.timing.isIdentity) "Subtitle timing & sync" else "Subtitle timing · ${PlayerController.describe(pc.timing)}",
                { onPanel(Panel.SUBTITLES) }, modifier = Modifier.fillMaxWidth(),
            )
            com.finmax.ui.components.PopoverItem("Playback info", onInfo, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UpNext(
    next: org.jellyfin.sdk.model.api.BaseItemDto,
    pc: PlayerController,
    countdown: Int?,
    ended: Boolean,
    stillWatching: Boolean,
    onPlay: () -> Unit,
    onAuto: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    var left by remember(next.id) { mutableIntStateOf(countdown ?: 0) }
    // Count down only once the episode has ended or credits are rolling with no time left to watch.
    LaunchedEffect(next.id, ended, countdown) {
        if (countdown == null) return@LaunchedEffect
        if (!ended) {
            while (pc.durationMs - pc.positionMs > countdown * 1000L) delay(500)
        }
        while (left > 0) { delay(1_000); left-- }
        onAuto()
    }
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
    val img = remember(next.id) { FinmaxApp.instance.repo.landscape(next, 480) }
    Row(
        modifier.background(Fm.Surface.copy(alpha = 0.95f), RoundedCornerShape(14.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (img != null) AsyncImage(img, null, modifier = Modifier.width(176.dp).height(99.dp).background(Fm.SurfaceHigh, RoundedCornerShape(8.dp)))
        Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (stillWatching) "Still watching?" else "Next episode", style = MaterialTheme.typography.labelMedium, color = Fm.Accent)
            Text(listOfNotNull(next.episodeLabel(), next.name).joinToString("  "), style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (countdown != null && left < countdown) "Play now · $left" else "Play now", onPlay, primary = true, modifier = Modifier.focusRequester(req))
                PillButton(if (ended) "Back" else "Hide", onDismiss)
            }
        }
    }
}

@Composable
private fun TrackPanel(title: String, options: List<Pair<Int, String>>, selected: Int?, onPick: (Int) -> Unit) {
    com.finmax.ui.library.SidePanel(title) {
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options.size) { i ->
                val (id, label) = options[i]
                val req = remember { FocusRequester() }
                if (id == selected || (selected == null && i == 0)) LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
                com.finmax.ui.components.PopoverItem(label, { onPick(id) }, primary = id == selected, modifier = Modifier.fillMaxWidth().focusRequester(req))
            }
        }
    }
}

@Composable
private fun PlaybackInfo(pc: PlayerController, modifier: Modifier) {
    LaunchedEffect(pc) {
        while (true) { pc.refreshTranscodeInfo(); delay(5_000) }
    }
    val src = pc.source
    val v = (pc.player as? androidx.media3.exoplayer.ExoPlayer)?.videoFormat
    val a = (pc.player as? androidx.media3.exoplayer.ExoPlayer)?.audioFormat
    val sub = pc.currentSubtitle
    val ti = pc.transcodeInfo
    val lines = listOfNotNull(
        "Method: " + when {
            pc.method == org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY -> "Direct Play (original file)"
            ti != null && ti.isVideoDirect && ti.isAudioDirect -> "Direct Stream (remuxed, nothing converted)"
            ti != null && ti.isVideoDirect -> "Direct Stream (video untouched, audio converted)"
            ti != null && ti.isAudioDirect -> "Transcoding video (audio untouched)"
            pc.method == org.jellyfin.sdk.model.api.PlayMethod.DIRECT_STREAM -> "Direct Stream (remuxed by server)"
            else -> "Transcoding"
        },
        "Container: ${src?.container}" + (src?.bitrate?.let { " · %.1f Mbps".format(it / 1e6) } ?: ""),
        v?.let { "Video: ${it.sampleMimeType?.substringAfter('/')} ${it.width}×${it.height}" + (if (it.frameRate > 0) " @ %.3f fps".format(it.frameRate) else "") },
        pc.streams.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO }?.let { "Source video: ${it.displayTitle}" + (it.videoRangeType?.let { r -> " · ${r.serialName}" } ?: "") },
        a?.let { "Audio: ${it.sampleMimeType?.substringAfter('/')} · ${it.channelCount} ch · ${it.sampleRate} Hz" },
        pc.audioStreams.firstOrNull { it.index == pc.audioIndex }?.let { "Audio track: ${it.displayTitle}" },
        "Subtitles: " + (sub?.let {
            "${it.displayTitle} · " + when {
                it.isTextSubtitle -> "finmax renderer"
                pc.method == org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY -> "player (image)"
                else -> "burned in by server"
            }
        } ?: "off"),
        if (!pc.timing.isIdentity) "Subtitle sync: ${PlayerController.describe(pc.timing)}" else null,
        ti?.let { "Server: video ${if (it.isVideoDirect) "copied" else "→ ${it.videoCodec}"}, audio ${if (it.isAudioDirect) "copied" else "→ ${it.audioCodec}"}" + (it.framerate?.let { f -> " · %.0f fps encode".format(f) } ?: "") },
        ti?.transcodeReasons?.takeIf { it.isNotEmpty() }?.let { "Why: " + it.joinToString { r -> r.serialName } },
        "Buffered: ${(pc.player.bufferedPosition - pc.positionMs).coerceAtLeast(0) / 1000} s",
    )
    Column(modifier.background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp)).padding(18.dp).widthIn(max = 520.dp)) {
        Text("Playback info", style = MaterialTheme.typography.titleMedium, color = Fm.Accent)
        Spacer(Modifier.height(6.dp))
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fm.Text) }
    }
}

private val com.finmax.subtitles.Cue.oneLine get() = text.replace('\n', ' ')

@Composable
private fun SubtitlePanel(pc: PlayerController, step: Long, onAssistant: () -> Unit, onLines: () -> Unit, onClose: () -> Unit) {
    com.finmax.ui.library.SidePanel("Subtitles", width = 460.dp) {
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pc.cues != null) {
                item {
                    Column(Modifier.background(Fm.SurfaceHigh, RoundedCornerShape(12.dp)).padding(14.dp)) {
                        Text("Timing", style = MaterialTheme.typography.labelMedium, color = Fm.Accent)
                        Text(
                            if (pc.timing.isIdentity) "In original timing" else PlayerController.describe(pc.timing),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text("+ shows lines later, − earlier", style = MaterialTheme.typography.labelSmall, color = Fm.TextFaint)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val first = remember { FocusRequester() }
                            LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
                            PillButton("−${step} ms", { pc.nudgeDelay(-step) }, modifier = Modifier.focusRequester(first))
                            PillButton("+${step} ms", { pc.nudgeDelay(step) })
                            PillButton("Reset", { pc.applyTiming(com.finmax.subtitles.Timing()) }, enabled = !pc.timing.isIdentity)
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("−1 s", { pc.nudgeDelay(-1000) })
                            PillButton("+1 s", { pc.nudgeDelay(1000) })
                        }
                    }
                }
                item { com.finmax.ui.components.PopoverItem("Fix subtitle sync…", onAssistant, primary = true, modifier = Modifier.fillMaxWidth()) }
                item { PillButton("Sync to a line…", onLines, modifier = Modifier.fillMaxWidth()) }
            } else if (pc.cuesLoading) {
                item { Text("Loading subtitles…", color = Fm.TextDim) }
            } else if (pc.currentSubtitle != null) {
                item {
                    Text(
                        "This is an image-based subtitle (${pc.currentSubtitle?.codec}). Its timing can't be adjusted; try a text subtitle or 'Find alternatives'.",
                        color = Fm.TextDim, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item { PillButton("Find alternatives…", onAssistant, modifier = Modifier.fillMaxWidth()) }
            } else {
                item { PillButton("Find subtitles…", onAssistant, modifier = Modifier.fillMaxWidth()) }
            }
            item { Text("Tracks", style = MaterialTheme.typography.titleMedium, color = Fm.Accent, modifier = Modifier.padding(top = 10.dp)) }
            item { com.finmax.ui.components.PopoverItem("Off", { pc.selectSubtitle(-1); onClose() }, primary = pc.subtitleIndex == null, modifier = Modifier.fillMaxWidth()) }
            items(pc.subtitleStreams.size) { i ->
                val s = pc.subtitleStreams[i]
                val label = listOfNotNull(
                    s.displayTitle ?: s.language,
                    "external".takeIf { s.isExternal },
                    "image".takeIf { !s.isTextSubtitle },
                ).joinToString(" · ")
                com.finmax.ui.components.PopoverItem(label, { pc.selectSubtitle(s.index) }, primary = s.index == pc.subtitleIndex, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AssistantPanel(a: SyncAssistant, pc: PlayerController, onApplied: () -> Unit) {
    val app = FinmaxApp.instance
    if (a.previewing != null || a.previewingFix) {
        // Minimised while previewing so the picture stays visible.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.padding(bottom = 40.dp).background(Fm.Surface.copy(alpha = 0.95f), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Previewing " + (a.previewing?.title ?: "correction") + (pc.previewTiming?.takeIf { !it.isIdentity }?.let { " · " + PlayerController.describe(it) } ?: ""),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.widthIn(max = 380.dp),
                )
                val req = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
                PillButton(if (a.previewing?.origin == com.finmax.subtitles.Origin.REMOTE) "Save & use" else "Apply", {
                    val keepOpen = a.previewing?.origin == com.finmax.subtitles.Origin.REMOTE // download progress shows in the panel
                    a.apply()
                    if (!keepOpen) onApplied()
                }, primary = true, modifier = Modifier.focusRequester(req))
                PillButton("Revert", { a.cancelPreview() })
            }
        }
        return
    }
    com.finmax.ui.library.SidePanel("Subtitle sync assistant", width = 600.dp) {
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(a.steps.size) { i -> Text("· " + a.steps[i], style = MaterialTheme.typography.bodyMedium, color = Fm.TextFaint) }
            if (a.running) item { Text("Analysing…", color = Fm.TextDim) }
            a.busy?.let { b -> item { Text(b, color = Fm.Accent) } }
            a.report?.let { r ->
                item {
                    Column(Modifier.background(Fm.SurfaceHigh, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.headline, style = MaterialTheme.typography.titleLarge)
                        r.details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim) }
                        Text(r.recommendation, style = MaterialTheme.typography.bodyMedium, color = Fm.Accent)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val req = remember { FocusRequester() }
                            LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
                            if (r.fix != null) {
                                PillButton("Preview fix", { a.previewFix() }, primary = true, modifier = Modifier.focusRequester(req))
                            } else {
                                PillButton("Run again", { a.run() }, modifier = Modifier.focusRequester(req))
                            }
                            if (app.bazarr.isConfigured && pc.currentSubtitle?.isExternal == true) {
                                PillButton("Bazarr audio sync", { a.bazarrAudioSync() })
                            }
                        }
                    }
                }
                if (a.candidates.isNotEmpty()) item { Text("Alternatives", style = MaterialTheme.typography.titleMedium, color = Fm.Accent) }
                items(a.candidates.size) { i ->
                    val c = a.candidates[i]
                    Column(Modifier.background(Fm.SurfaceHigh.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(12.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(
                            (listOf(when (c.origin) { com.finmax.subtitles.Origin.TRACK -> "On server"; com.finmax.subtitles.Origin.REMOTE -> "Online (server provider)"; com.finmax.subtitles.Origin.BAZARR -> "Bazarr" }) + c.notes).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = Fm.TextFaint, maxLines = 3,
                        )
                        c.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Fm.Accent) }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (c.origin == com.finmax.subtitles.Origin.BAZARR) {
                                PillButton("Download via Bazarr", { a.downloadBazarr(c) })
                            } else {
                                PillButton("Preview", { a.preview(c) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineSyncPanel(pc: PlayerController, a: SyncAssistant, onDone: () -> Unit) {
    val track = pc.cues ?: return
    var window by remember { mutableStateOf(emptyList<com.finmax.subtitles.Cue>()) }
    LaunchedEffect(track) {
        while (true) {
            val idx = track.indexAfter(pc.positionMs, pc.timing)
            window = track.cues.subList((idx - 4).coerceAtLeast(0), (idx + 5).coerceAtMost(track.cues.size))
            delay(700)
        }
    }
    com.finmax.ui.library.SidePanel("Sync to a line", width = 460.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Press OK on a line the moment you hear it spoken. One line fixes a constant delay; a second line at least a minute later also fixes drift.",
                style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
            )
            Text("Points set: ${a.points.size}/2 · ${PlayerController.describe(pc.timing)}", style = MaterialTheme.typography.labelMedium, color = Fm.Accent)
            window.forEachIndexed { i, cue ->
                val req = remember(cue) { FocusRequester() }
                if (i == 0) LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
                PillButton(cue.oneLine.take(70), { a.markLine(cue) }, modifier = Modifier.fillMaxWidth().focusRequester(req))
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Done", onDone, primary = true)
                PillButton("Clear points", { a.resetPoints() })
            }
        }
    }
}
