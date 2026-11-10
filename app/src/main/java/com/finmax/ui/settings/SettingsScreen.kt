package com.finmax.ui.settings

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.finmax.BuildConfig
import com.finmax.FinmaxApp
import com.finmax.data.AppSettings
import com.finmax.data.HomeRows
import com.finmax.data.SegmentAction
import com.finmax.playback.DeviceCapabilities
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.components.BrandBackdrop
import com.finmax.ui.components.Ic
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.ScreensaverState
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.holdUpStaysInside
import com.finmax.ui.components.friendlyError
import com.finmax.ui.login.FmTextField
import com.finmax.ui.theme.Fm
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.popoverBlur
import com.finmax.update.Updater
import com.finmax.ui.player.shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun <T> List<T>.after(v: T) = this[(indexOf(v) + 1).mod(size)]

/** Opens Settings on a given section (e.g. Discover's "Set up" link). */
object SettingsLink {
    var section: Section? = null
}

enum class Section(val title: String, val blurb: String) {
    ACCOUNT("Account", "Who's watching, profile PINs and servers."),
    PLAYBACK("Playback", "Quality, frame rate, skipping and what plays next."),
    SUBTITLES("Subtitles", "Language and how subtitles look."),
    HOME("Home", "Which rows Home shows, and in what order."),
    DETAILS("Details pages", "The video behind a title's page."),
    SCREENSAVER("Screensaver", "Your library's artwork when the TV sits idle."),
    DISCOVER("Discover", "Seerr, for trending titles and requests."),
    BAZARR("Bazarr", "More subtitles and audio-based sync."),
    ABOUT("About & updates", "Version, device and app updates."),
}

@Composable
private fun Section.icon(): ImageVector = when (this) {
    Section.ACCOUNT -> Ic.Person
    Section.PLAYBACK -> Ic.Play
    Section.SUBTITLES -> Ic.Subtitles
    Section.HOME -> Ic.Home
    Section.DETAILS -> Ic.Monitor
    Section.SCREENSAVER -> Ic.Tv
    Section.DISCOVER -> Ic.Sparkles
    Section.BAZARR -> Ic.Download
    Section.ABOUT -> Ic.Info
}

@Composable
fun SettingsScreen() {
    var section by remember { mutableStateOf(SettingsLink.section ?: Section.ACCOUNT) }
    // Arriving through a link: focus goes straight to that section's settings.
    val linked = remember { SettingsLink.section != null }
    LaunchedEffect(Unit) { SettingsLink.section = null }

    val pick = SettingsPicker.open
    // Closing the picker hands focus back to the row that opened it (it would otherwise land on the top bar).
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(pick) {
        if (pick != null) returnTo = pick.back
        else returnTo?.let { r ->
            returnTo = null
            repeat(10) { delay(40); if (runCatching { r.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
        }
    }
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().popoverBlur(pick != null)) {
        BrandBackdrop()
        Column(Modifier.fillMaxSize()) {
            TopBar(Route.Settings)
            Row(Modifier.fillMaxSize().padding(start = Fm.SafeH, end = Fm.SafeH, top = 14.dp, bottom = Fm.SafeV).holdUpStaysInside()) {
                // Section list: focus picks the section, so the pane on the right follows the D-pad.
                // Coming back from the pane (Left) lands on the open section, not the one level with the row.
                val reqs = remember { Section.entries.associateWith { FocusRequester() } }
                Column(
                    Modifier.width(232.dp).focusProperties { onEnter = { reqs.getValue(section).requestFocus() } }.focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Section.entries.forEach { s ->
                        NavItem(s, s == section, reqs.getValue(s), initial = s == section && !linked) { section = s }
                    }
                }
                Spacer(Modifier.width(24.dp))
                // A long thin pill between the sections and their settings.
                Box(Modifier.fillMaxHeight().padding(vertical = 4.dp).width(4.dp).background(Color.White.copy(alpha = 0.1f), CircleShape))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(section, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "pane") { s ->
                        Pane(s, focusFirst = linked && s == section)
                    }
                }
            }
        }
      }
        pick?.let { p ->
            androidx.activity.compose.BackHandler { SettingsPicker.open = null }
            com.finmax.ui.library.SidePanel(p.title) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(p.options.size) { i ->
                        com.finmax.ui.components.PopoverItem(
                            p.options[i], { p.onPick(i); SettingsPicker.open = null }, primary = i == p.selected,
                            modifier = Modifier.fillMaxWidth().focusMemory("pick/${p.title}/$i", initial = i == p.selected),
                        )
                    }
                }
            }
        }
    }
}

/** A setting's whole option list in a side panel, for lists too long to cycle through. */
private class Pick(val title: String, val options: List<String>, val selected: Int, val back: FocusRequester, val onPick: (Int) -> Unit)

private object SettingsPicker {
    var open by mutableStateOf<Pick?>(null)
}

@Composable
private fun <T> PickRow(title: String, desc: String?, options: List<T>, current: T, label: (T) -> String, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    val back = remember { FocusRequester() }
    ChoiceRow(title, desc, label(current), modifier.focusRequester(back)) {
        SettingsPicker.open = Pick(title, options.map(label), options.indexOf(current), back) { onPick(options[it]) }
    }
}

@Composable
private fun NavItem(s: Section, selected: Boolean, req: FocusRequester, initial: Boolean, onFocus: () -> Unit) {
    if (initial) LaunchedEffect(Unit) { delay(60); runCatching { req.requestFocus() } }
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onFocus,
        modifier = Modifier.fillMaxWidth().focusRequester(req).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.07f) else Color.Transparent,
            contentColor = if (selected) Fm.Text else Fm.TextDim,
            focusedContainerColor = Fm.Focus, focusedContentColor = Fm.OnFocus,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(s.icon(), null, Modifier.size(18.dp), tint = if (focused) Fm.OnFocus else if (selected) Fm.Accent else Fm.TextFaint)
            Spacer(Modifier.width(12.dp))
            Text(s.title, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun Pane(s: Section, focusFirst: Boolean) {
    val app = FinmaxApp.instance
    val st by app.settings.state.collectAsState()
    fun set(block: (AppSettings) -> AppSettings) = app.settings.update(block)
    val nav = LocalNav.current
    val first = remember { FocusRequester() }
    if (focusFirst) LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The first setting brings the page back to its top, so the title (and the subtitle preview,
    // which can't take focus) come back into view when you go up.
    val f = Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) scope.launch { list.animateScrollToItem(0) } }

    LazyColumn(state = list, contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(start = 18.dp, top = 2.dp, bottom = 14.dp)) {
                Text(s.title, style = MaterialTheme.typography.headlineSmall)
                Text(s.blurb, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
        when (s) {
            Section.ACCOUNT -> {
                val session = app.sessions.session.value
                item {
                    ActionRow(
                        "Profiles, PINs & servers", "Signed in as ${session?.user?.name} on ${session?.server?.name}", Ic.Person, f,
                    ) { nav.push(Route.Profiles) }
                }
            }
            Section.PLAYBACK -> {
                item {
                    PickRow("Streaming quality", "Lower it if playback stalls on Wi-Fi", listOf(0, 120, 80, 40, 20, 10, 4), st.maxBitrateMbps, { if (it == 0) "Automatic" else "$it Mbps" }, f) { v ->
                        set { it.copy(maxBitrateMbps = v) }
                    }
                }
                item {
                    ToggleRow("Match the film's frame rate", "The TV switches to 24 Hz for films, so motion is smooth. The screen blanks a moment when it switches.", st.matchFrameRate) {
                        set { it.copy(matchFrameRate = !it.matchFrameRate) }
                    }
                }
                item {
                    ChoiceRow(
                        "Player",
                        when (st.playerEngine) {
                            com.finmax.data.PlayerEngine.AUTO -> "mpv for styled (ASS) subtitles, ExoPlayer for everything else"
                            com.finmax.data.PlayerEngine.EXO -> "Always ExoPlayer: smoothest, styled subtitles show as plain text"
                            com.finmax.data.PlayerEngine.MPV -> "Always mpv: draws every subtitle format itself"
                        },
                        st.playerEngine.label,
                    ) { set { it.copy(playerEngine = com.finmax.data.PlayerEngine.entries.after(it.playerEngine)) } }
                }
                item { ToggleRow("Play the next episode automatically", null, st.autoplayNext) { set { it.copy(autoplayNext = !it.autoplayNext) } } }
                if (st.autoplayNext) item {
                    PickRow("Next episode countdown", null, listOf(5, 10, 15, 20, 30), st.autoplayCountdownSec, { "$it s" }) { v ->
                        set { it.copy(autoplayCountdownSec = v) }
                    }
                }
                item { ChoiceRow("Intros & recaps", null, segLabel(st.skipIntro)) { set { it.copy(skipIntro = SegmentAction.entries.after(it.skipIntro)) } } }
                item { ChoiceRow("End credits", null, segLabel(st.skipCredits)) { set { it.copy(skipCredits = SegmentAction.entries.after(it.skipCredits)) } } }
                item {
                    PickRow("Rewind when resuming", "Starts a little before where you stopped", listOf(0, 5, 10, 15, 30), st.resumeRewindSec, { if (it == 0) "Off" else "$it s" }) { v ->
                        set { it.copy(resumeRewindSec = v) }
                    }
                }
                item {
                    PickRow("Preferred audio language", null, langs, st.preferredAudioLang, ::langLabel) { v ->
                        set { it.copy(preferredAudioLang = v) }
                    }
                }
            }
            Section.SUBTITLES -> {
                item { SubtitlePreview(st) }
                item {
                    PickRow("Preferred language", null, langs, st.preferredSubtitleLang, ::langLabel, f) { v ->
                        set { it.copy(preferredSubtitleLang = v) }
                    }
                }
                item {
                    PickRow("Text size", null, listOf(0.75f, 0.9f, 1f, 1.15f, 1.3f, 1.5f), st.subtitleScale, { "${(it * 100).toInt()}%" }) { v ->
                        set { it.copy(subtitleScale = v) }
                    }
                }
                item { ToggleRow("Background box", null, st.subtitleBackground) { set { it.copy(subtitleBackground = !it.subtitleBackground) } } }
                if (!st.subtitleBackground) item {
                    ChoiceRow("Shadow", "Drop shadow is Netflix's look: it keeps text readable on bright scenes", st.subtitleShadow.label) {
                        set { it.copy(subtitleShadow = com.finmax.data.SubShadow.entries.after(it.subtitleShadow)) }
                    }
                }
                item {
                    PickRow("Distance from bottom", null, listOf(4, 8, 12, 16, 20), st.subtitleBottomPct, { "$it%" }) { v ->
                        set { it.copy(subtitleBottomPct = v) }
                    }
                }
                item {
                    PickRow("Timing step", "How far each press moves subtitles when you fix their sync", listOf(50, 100, 250, 500, 1000), st.subtitleDelayStepMs, { "$it ms" }) { v ->
                        set { it.copy(subtitleDelayStepMs = v) }
                    }
                }
            }
            Section.HOME -> {
                item { ChoiceRow("Featured title text", "Where the big title sits at the top of Home", if (st.heroRight) "Right" else "Left", f) { set { it.copy(heroRight = !it.heroRight) } } }
                item {
                    ToggleRow("Next Up inside Continue Watching", "One row: what you're halfway through, then the next episode of your shows", st.homeMerge) {
                        set { it.copy(homeMerge = !it.homeMerge) }
                    }
                }
                item { HomeRowsEditor(st) }
            }
            Section.DETAILS -> {
                item {
                    ChoiceRow("Background video", "Movies play the film itself behind their page, then carry on full screen", st.detailVideo.label, f) {
                        set { it.copy(detailVideo = com.finmax.data.DetailVideo.entries.after(it.detailVideo)) }
                    }
                }
                item { ToggleRow("Video sound", null, st.detailVideoSound) { set { it.copy(detailVideoSound = !it.detailVideoSound) } } }
            }
            Section.SCREENSAVER -> {
                item {
                    PickRow("Start after", "Backdrops from your library, slowly drifting, with a clock", listOf(0, 2, 5, 10, 15, 30), st.screensaverMin, { if (it == 0) "Off" else "$it min" }, f) { v ->
                        set { it.copy(screensaverMin = v) }
                    }
                }
                item { ActionRow("Show it now", "Any button brings you back", Ic.Play) { ScreensaverState.active = true } }
                item {
                    Note(
                        "finmax can also be the TV's own screensaver. Choose it in the TV's settings under System → Ambient mode / Screen saver, where your TV offers that.",
                    )
                }
            }
            Section.DISCOVER -> item { SeerrSettings(f) }
            Section.BAZARR -> item { BazarrSettings(f) }
            Section.ABOUT -> {
                item { UpdateRows(f) }
                item {
                    Note(
                        "finmax ${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                            "Plays: " + DeviceCapabilities.get(app).summary(),
                    )
                }
            }
        }
    }
}

// ---- Rows ---------------------------------------------------------------------------------------

private val RowShape = RoundedCornerShape(14.dp)

@Composable
private fun SettingRow(
    title: String,
    desc: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    trailing: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Fm.Text,
            focusedContainerColor = Fm.Focus, focusedContentColor = Fm.OnFocus,
            pressedContainerColor = Fm.Focus,
            pressedContentColor = Fm.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(32.dp)) {
                iconFor(title)?.let { Icon(it, null, Modifier.size(18.dp), tint = if (focused) Fm.OnFocus else Fm.TextDim) }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (desc != null) {
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = if (focused) Fm.OnFocus.copy(alpha = 0.65f) else Fm.TextFaint, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            trailing(focused)
        }
    }
}

@Composable
private fun ChoiceRow(title: String, desc: String?, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    SettingRow(title, desc, modifier, onClick) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.titleSmall, color = if (focused) Fm.OnFocus else Fm.Accent)
            Icon(Ic.Chevron, null, Modifier.padding(start = 6.dp).size(16.dp), tint = if (focused) Fm.OnFocus.copy(alpha = 0.6f) else Fm.TextFaint)
        }
    }

@Composable
private fun ToggleRow(title: String, desc: String?, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    SettingRow(title, desc, modifier, onClick) { focused -> Switch(on, focused) }

@Composable
private fun ActionRow(title: String, desc: String?, @Suppress("UNUSED_PARAMETER") icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) =
    SettingRow(title, desc, modifier, onClick) { focused ->
        Icon(Ic.Chevron, null, Modifier.size(16.dp), tint = if (focused) Fm.OnFocus.copy(alpha = 0.6f) else Fm.TextFaint)
    }

/** Each setting's icon, by its title (Home rows and other untitled rows get none, but keep the indent). */
@Composable
private fun iconFor(title: String): ImageVector? = when {
    title.startsWith("Profiles") -> Ic.Users
    title == "Streaming quality" -> Ic.Gauge
    title.startsWith("Match the film") -> Ic.Monitor
    title == "Player" -> Ic.Clapperboard
    title.startsWith("Play the next") -> Ic.SkipNext
    title.endsWith("countdown") -> Ic.Timer
    title.startsWith("Intros") -> Ic.FastForward
    title == "End credits" -> Ic.ListEnd
    title.startsWith("Rewind") -> Ic.Replay
    title.contains("language") -> Ic.Languages
    title == "Text size" -> Ic.TextSize
    title == "Background box" -> Ic.Square
    title == "Shadow" -> Ic.Layers
    title.startsWith("Distance") -> Ic.Move
    title == "Timing step" -> Ic.TimerReset
    title.startsWith("Featured") -> Ic.PanelRight
    title.startsWith("Next Up inside") -> Ic.Merge
    title == "Background video" -> Ic.Video
    title == "Video sound" -> Ic.Volume
    title == "Start after" -> Ic.Clock
    title == "Show it now" -> Ic.Play
    title.startsWith("Check for updates") -> Ic.Refresh
    title.startsWith("Install") || title.startsWith("Downloading") -> Ic.Download
    else -> null
}

@Composable
private fun Switch(on: Boolean, focused: Boolean) {
    val track by animateColorAsState(
        when {
            on -> Fm.Amethyst
            focused -> Fm.OnFocus.copy(alpha = 0.25f)
            else -> Color.White.copy(alpha = 0.18f)
        }, label = "track",
    )
    val x by animateDpAsState(if (on) 18.dp else 0.dp, tween(160), label = "knob")
    Box(Modifier.size(width = 42.dp, height = 24.dp).background(track, CircleShape).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(18.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = Fm.TextFaint, modifier = Modifier.padding(start = 50.dp, end = 18.dp, top = 10.dp, bottom = 10.dp))

private fun segLabel(a: SegmentAction) = when (a) {
    SegmentAction.ASK -> "Show a skip button"
    SegmentAction.AUTO -> "Skip automatically"
    SegmentAction.OFF -> "Don't offer"
}

private val langs = listOf("", "eng", "spa", "fre", "ger", "ita", "por", "dut", "swe", "nor", "dan", "fin", "pol", "rus", "jpn", "kor", "chi", "ara", "hun", "ukr")

@Suppress("DEPRECATION")
private fun langLabel(code: String) =
    if (code.isEmpty()) "Server default" else java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifEmpty { code }

// ---- Subtitle preview ---------------------------------------------------------------------------

/** A small frame of "video" with a line styled the way the player will draw it. */
@Composable
private fun SubtitlePreview(st: AppSettings) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B2140), Color(0xFF0F1A24), Color(0xFF3A2A1E)))),
    ) {
        // A scaled-down screen: text size, shadow and position are the player's, times the frame's share of 960 dp.
        val k = maxWidth.value / 960f
        Text(
            "Where were you last night?",
            fontSize = (26 * st.subtitleScale * k).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = Color.White, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge.copy(shadow = if (st.subtitleBackground) null else st.subtitleShadow.shadow(st.subtitleScale * k)),
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = (540f * k * st.subtitleBottomPct / 100f).dp)
                .then(if (st.subtitleBackground) Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp)) else Modifier)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

// ---- Home rows ----------------------------------------------------------------------------------

/**
 * Row order and visibility. OK shows or hides a row; Right picks it up, Up/Down moves it,
 * OK (or Left/Back) puts it down.
 */
@Composable
private fun HomeRowsEditor(st: AppSettings) {
    val app = FinmaxApp.instance
    var moving by remember { mutableStateOf<String?>(null) }
    val order = HomeRows.normalized(st.homeOrder)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Rows  ·  OK shows or hides  ·  → then ↑ ↓ moves",
            style = MaterialTheme.typography.labelMedium, color = Fm.TextFaint, modifier = Modifier.padding(start = 50.dp, top = 14.dp, bottom = 4.dp),
        )
        order.forEach { k ->
            androidx.compose.runtime.key(k) {
                val merged = k == "nextup" && st.homeMerge
                val lifted = moving == k
                val on = st.rowOn(k)
                SettingRow(
                    HomeRows.labels.getValue(k),
                    when {
                        lifted -> "Moving: ↑ ↓, then OK"
                        merged -> "Shown inside Continue Watching"
                        else -> null
                    },
                    Modifier
                        .then(if (lifted) Modifier.border(BorderStroke(2.dp, Fm.Accent), RowShape) else Modifier)
                        .onPreviewKeyEvent { e ->
                            val code = e.key.nativeKeyCode
                            if (moving == k) {
                                val i = order.indexOf(k)
                                val to = when (code) {
                                    KeyEvent.KEYCODE_DPAD_UP -> i - 1
                                    KeyEvent.KEYCODE_DPAD_DOWN -> i + 1
                                    else -> null
                                }
                                if (to != null) {
                                    if (e.type == KeyEventType.KeyDown && to in order.indices) {
                                        val o = order.toMutableList().apply { add(to, removeAt(i)) }
                                        app.settings.update { it.copy(homeOrder = o) }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                // OK, Left or Back puts it down; Right (whose release follows the pick-up) does nothing.
                                if (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) {
                                    if (e.type == KeyEventType.KeyUp) moving = null
                                    return@onPreviewKeyEvent true
                                }
                                code == KeyEvent.KEYCODE_DPAD_RIGHT
                            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                if (e.type == KeyEventType.KeyDown) moving = k
                                true
                            } else false
                        },
                    onClick = { if (!merged) app.settings.update { it.toggleRow(k) } },
                ) { focused ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (focused || lifted) Icon(Ic.Move, null, Modifier.padding(end = 14.dp).size(16.dp), tint = if (focused) Fm.OnFocus.copy(alpha = 0.6f) else Fm.Accent)
                        Switch(on || merged, focused)
                    }
                }
            }
        }
    }
}

// ---- Discover (Seerr) ---------------------------------------------------------------------------

@Composable
private fun SeerrSettings(first: Modifier) {
    val app = FinmaxApp.instance
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(app.settings.current.seerrUrl) }
    var key by remember { mutableStateOf(app.settings.seerrApiKey.orEmpty()) }
    var status by remember { mutableStateOf<String?>(if (app.seerr.isConfigured) "Connected" else null) }
    ServiceForm(
        intro = "Discover uses Jellyseerr or Overseerr to show what's trending and let you request titles that aren't in your library. " +
            "Find the API key in Seerr → Settings → General.",
        url = url, onUrl = { url = it }, urlLabel = "Seerr address (e.g. http://192.168.1.20:5055)",
        key = key, onKey = { key = it }, status = status, first = first,
        onSave = {
            app.settings.update { it.copy(seerrUrl = url.trim()) }
            app.settings.seerrApiKey = key.trim()
            status = "Testing…"
            scope.launch { status = runCatching { "Connected to Seerr ${app.seerr.version()}" }.getOrElse { "Failed: ${friendlyError(it)}" } }
        },
        onRemove = {
            url = ""; key = ""
            app.settings.update { it.copy(seerrUrl = "") }
            app.settings.seerrApiKey = null
            status = "Disconnected"
        },
    )
}

@Composable
private fun BazarrSettings(first: Modifier) {
    val app = FinmaxApp.instance
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(app.settings.current.bazarrUrl) }
    var key by remember { mutableStateOf(app.settings.bazarrApiKey.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    ServiceForm(
        intro = "Lets the subtitle assistant search Bazarr's providers, download alternatives and run Bazarr's audio-based sync. " +
            "Find the API key in Bazarr → Settings → General.",
        url = url, onUrl = { url = it }, urlLabel = "Bazarr address (e.g. http://192.168.1.20:6767)",
        key = key, onKey = { key = it }, status = status, first = first,
        onSave = {
            app.settings.update { it.copy(bazarrUrl = url.trim()) }
            app.settings.bazarrApiKey = key.trim()
            status = "Testing…"
            scope.launch { status = runCatching { "Connected to Bazarr ${app.bazarr.version()}" }.getOrElse { "Failed: ${friendlyError(it)}" } }
        },
        onRemove = {
            url = ""; key = ""
            app.settings.update { it.copy(bazarrUrl = "") }
            app.settings.bazarrApiKey = null
            status = "Disconnected"
        },
    )
}

@Composable
private fun ServiceForm(
    intro: String,
    url: String, onUrl: (String) -> Unit, urlLabel: String,
    key: String, onKey: (String) -> Unit,
    status: String?, first: Modifier,
    onSave: () -> Unit, onRemove: () -> Unit,
) {
    Column(Modifier.padding(start = 50.dp, end = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(intro, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim)
        FmTextField(url, onUrl, urlLabel, first.width(460.dp))
        FmTextField(key, onKey, "API key", Modifier.width(460.dp), password = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Save & test", onSave, primary = true)
            if (url.isNotEmpty()) PillButton("Remove", onRemove)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Failed")) Fm.Error else Fm.Success) }
        }
    }
}

// ---- Updates ------------------------------------------------------------------------------------

@Composable
private fun UpdateRows(first: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val rel = Updater.available
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!Updater.configured) {
            Note("Update checks aren't set up for this build.")
            return@Column
        }
        if (rel != null) {
            ActionRow(
                if (progress != null) "Downloading… ${((progress ?: 0f) * 100).toInt()}%" else "Install finmax ${rel.versionName}",
                rel.notes.ifBlank { "A new version is ready" }, Ic.Download, first,
            ) {
                if (progress != null) return@ActionRow
                progress = 0f
                scope.launch {
                    runCatching { Updater.install(context, rel) { p -> progress = p } }
                        .onFailure { status = "Failed: ${friendlyError(it)}" }
                    progress = null
                }
            }
        }
        ActionRow("Check for updates", status ?: "You have ${BuildConfig.VERSION_NAME}", Ic.Refresh, if (rel == null) first else Modifier) {
            status = "Checking…"
            scope.launch {
                status = runCatching { Updater.check() }.fold(
                    { r -> if (r == null) "finmax is up to date" else "finmax ${r.versionName} is available" },
                    { "Failed: ${friendlyError(it)}" },
                )
            }
        }
    }
}
