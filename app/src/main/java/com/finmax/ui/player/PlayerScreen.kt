package com.finmax.ui.player

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
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

enum class Panel { NONE, AUDIO, SUBTITLES, SPEED, ASSISTANT, LINES }

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
    val pc = remember(route) { PlayerController(context, app, session) }
    val assistant = remember(pc) { SyncAssistant(app, session, pc, scope) }
    val settings = app.settings.current

    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var info by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(0L) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var upNextDismissed by remember(route) { mutableStateOf(false) }
    val skipped = remember(route) { mutableSetOf<String>() }
    val playReq = remember { FocusRequester() }
    val seekReq = remember { FocusRequester() }
    val skipReq = remember { FocusRequester() }

    DisposableEffect(pc) {
        pc.start(
            PlayRequest(
                itemId = route.itemId,
                startMs = if (route.fromStart) 0 else null,
                mediaSourceId = route.mediaSourceId,
                audioIndex = route.audioIndex,
                subtitleIndex = route.subtitleIndex,
            ),
        )
        onDispose { pc.release() }
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
        nav.replace(Route.Player(n.id, fromStart = true))
    }

    val focusedControls = controls && panel == Panel.NONE
    LaunchedEffect(focusedControls) {
        if (focusedControls) { delay(30); runCatching { seekReq.requestFocus() } }
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

    BackHandler {
        when {
            panel == Panel.LINES || panel == Panel.ASSISTANT -> { assistant.cancelPreview(); panel = Panel.SUBTITLES }
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
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (pc.player.isPlaying) pc.player.pause() else pc.player.play(); controls = true; true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { pc.player.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { pc.player.pause(); controls = true; true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { pc.player.seekTo(pc.positionMs + 30_000); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { pc.player.seekTo(pc.positionMs - 10_000); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        val p = pc.previousEpisode
                        if (pc.positionMs > 10_000 || p == null) pc.player.seekTo(0) else nav.replace(Route.Player(p.id, fromStart = true))
                        true
                    }
                    KeyEvent.KEYCODE_CAPTIONS -> { panel = Panel.SUBTITLES; controls = true; true }
                    else -> {
                        if (controls || panel != Panel.NONE || showUpNext || showSkip || pc.error != null) return@onPreviewKeyEvent false
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { pc.player.seekTo(pc.positionMs - 10_000); seekPreview = pc.positionMs; true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { pc.player.seekTo(pc.positionMs + 10_000); seekPreview = pc.positionMs; true }
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
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    player = pc.player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        SubtitleOverlay(pc, liftForControls = controls)

        if (pc.loading || pc.buffering) {
            Text(if (pc.loading) "Loading…" else "Buffering…", color = Fm.TextDim, modifier = Modifier.align(Alignment.Center))
        }

        seekPreview?.let { p ->
            LaunchedEffect(p) { delay(1_500); seekPreview = null }
            if (!controls) {
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 96.dp, vertical = 40.dp)) {
                    Text(fmt(pc.positionMs) + " / " + fmt(duration), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SeekBar(pc.positionMs, duration, focused = false)
                }
            }
        }

        AnimatedVisibility(controls && panel != Panel.LINES, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(
                pc, position, duration, seekReq, playReq,
                onPanel = { panel = it },
                onInfo = { info = !info },
                onNext = ::playNext,
                onPrev = { pc.previousEpisode?.let { nav.replace(Route.Player(it.id, fromStart = true)) } },
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
            Panel.AUDIO -> TrackPanel("Audio", pc.audioStreams.map { it.index to (it.displayTitle ?: it.language ?: "Track ${it.index}") }, pc.audioIndex) {
                pc.selectAudio(it); panel = Panel.NONE
            }
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
    Box(Modifier.fillMaxSize().padding(bottom = if (liftForControls) 150.dp else (540 * s.subtitleBottomPct / 100).dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            t,
            textAlign = TextAlign.Center,
            style = TextStyle(
                color = Color.White,
                fontSize = (26 * s.subtitleScale).sp,
                fontWeight = FontWeight.Medium,
                lineHeight = (33 * s.subtitleScale).sp,
                shadow = if (s.subtitleBackground) null else Shadow(Color.Black, blurRadius = 6f),
            ),
            modifier = Modifier.widthIn(max = 820.dp)
                .then(if (s.subtitleBackground) Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 2.dp) else Modifier),
        )
    }
}

@Composable
fun SeekBar(position: Long, duration: Long, focused: Boolean, modifier: Modifier = Modifier) {
    val f = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(if (focused) 8.dp else 5.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Fm.Accent, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun Controls(
    pc: PlayerController,
    position: Long,
    duration: Long,
    seekReq: FocusRequester,
    playReq: FocusRequester,
    onPanel: (Panel) -> Unit,
    onInfo: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    val item = pc.item
    var seekFocused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
        Column(Modifier.padding(start = 56.dp, top = 32.dp)) {
            item?.seriesName?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Fm.TextDim) }
            Text(listOfNotNull(item?.episodeLabel(), item?.name).joinToString("  "), style = MaterialTheme.typography.headlineMedium)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 56.dp, vertical = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(position), style = MaterialTheme.typography.labelLarge)
                Box(
                    Modifier.weight(1f).padding(horizontal = 16.dp).height(24.dp)
                        .focusRequester(seekReq)
                        .onFocusChanged { seekFocused = it.isFocused }
                        .onKeyEvent { e ->
                            if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                            when (e.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> { pc.player.seekTo(pc.positionMs - 10_000); true }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> { pc.player.seekTo(pc.positionMs + 10_000); true }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                    if (pc.player.isPlaying) pc.player.pause() else pc.player.play(); true
                                }
                                else -> false
                            }
                        }
                        .focusable(),
                    contentAlignment = Alignment.Center,
                ) {
                    SeekBar(position, duration, seekFocused, if (seekFocused) Modifier.border(1.dp, Fm.Accent, RoundedCornerShape(4.dp)) else Modifier)
                }
                Text("-" + fmt(duration - position), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(if (pc.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (pc.isPlaying) "Pause" else "Play", {
                    if (pc.player.isPlaying) pc.player.pause() else pc.player.play()
                }, Modifier.focusRequester(playReq))
                IconButton(Icons.Filled.Replay10, "Back 10 seconds", { pc.player.seekTo(pc.positionMs - 10_000) })
                IconButton(Icons.Filled.Forward10, "Forward 10 seconds", { pc.player.seekTo(pc.positionMs + 10_000) })
                if (pc.previousEpisode != null) IconButton(Icons.Filled.SkipPrevious, "Previous episode", onPrev)
                if (pc.nextEpisode != null) IconButton(Icons.Filled.SkipNext, "Next episode", onNext)
                Spacer(Modifier.weight(1f))
                PillButton("Audio", { onPanel(Panel.AUDIO) }, icon = Icons.AutoMirrored.Filled.VolumeUp)
                PillButton(
                    if (pc.timing.isIdentity) "Subtitles" else "Subtitles · ${PlayerController.describe(pc.timing)}",
                    { onPanel(Panel.SUBTITLES) }, icon = Icons.Filled.ClosedCaption,
                )
                PillButton(if (pc.speed == 1f) "Speed" else "${pc.speed}×", { onPanel(Panel.SPEED) }, icon = Icons.Filled.Speed)
                IconButton(Icons.Filled.Info, "Playback info", onInfo)
            }
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
                PillButton(label, { onPick(id) }, primary = id == selected, modifier = Modifier.fillMaxWidth().focusRequester(req))
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
    val v = pc.player.videoFormat
    val a = pc.player.audioFormat
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
                it.isTextSubtitle -> "FINMAX renderer"
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
                item { PillButton("Fix subtitle sync…", onAssistant, primary = true, modifier = Modifier.fillMaxWidth()) }
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
            item { PillButton("Off", { pc.selectSubtitle(-1); onClose() }, primary = pc.subtitleIndex == null, modifier = Modifier.fillMaxWidth()) }
            items(pc.subtitleStreams.size) { i ->
                val s = pc.subtitleStreams[i]
                val label = listOfNotNull(
                    s.displayTitle ?: s.language,
                    "external".takeIf { s.isExternal },
                    "image".takeIf { !s.isTextSubtitle },
                ).joinToString(" · ")
                PillButton(label, { pc.selectSubtitle(s.index) }, primary = s.index == pc.subtitleIndex, modifier = Modifier.fillMaxWidth())
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(440.dp).background(Fm.Surface.copy(alpha = 0.94f)).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sync to a line", style = MaterialTheme.typography.headlineMedium)
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
