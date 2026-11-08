package com.finmax.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.finmax.BuildConfig
import com.finmax.FinmaxApp
import com.finmax.data.AppSettings
import com.finmax.data.SegmentAction
import com.finmax.playback.DeviceCapabilities
import com.finmax.ui.LocalNav
import com.finmax.ui.Route
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.TopBar
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.friendlyError
import com.finmax.ui.login.FmTextField
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.launch

private fun <T> List<T>.after(v: T) = this[(indexOf(v) + 1).mod(size)]

@Composable
fun SettingsScreen() {
    val app = FinmaxApp.instance
    val s by app.settings.state.collectAsState()
    val nav = LocalNav.current
    fun set(block: (AppSettings) -> AppSettings) = app.settings.update(block)
    val session = app.sessions.session.value

    Column(Modifier.fillMaxSize()) {
        TopBar(Route.Settings)
        LazyColumn(
            contentPadding = PaddingValues(start = Fm.SafeH, end = 200.dp, top = 8.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Header("Account") }
            item {
                Choice("Signed in", "${session?.user?.name} · ${session?.server?.name}", Modifier.focusMemory("acct", initial = true)) {
                    nav.push(Route.Profiles)
                }
            }

            item { Header("Playback") }
            item {
                Choice("Maximum streaming quality", if (s.maxBitrateMbps == 0) "Automatic" else "${s.maxBitrateMbps} Mbps") {
                    set { it.copy(maxBitrateMbps = listOf(0, 120, 80, 40, 20, 10, 4).after(it.maxBitrateMbps)) }
                }
            }
            item { Toggle("Play next episode automatically", s.autoplayNext) { set { it.copy(autoplayNext = !it.autoplayNext) } } }
            item {
                Choice("Next episode countdown", "${s.autoplayCountdownSec} s") {
                    set { it.copy(autoplayCountdownSec = listOf(5, 10, 15, 20, 30).after(it.autoplayCountdownSec)) }
                }
            }
            item { Choice("Intros & recaps", segLabel(s.skipIntro)) { set { it.copy(skipIntro = SegmentAction.entries.after(it.skipIntro)) } } }
            item { Choice("End credits", segLabel(s.skipCredits)) { set { it.copy(skipCredits = SegmentAction.entries.after(it.skipCredits)) } } }
            item {
                Choice("Rewind when resuming", if (s.resumeRewindSec == 0) "Off" else "${s.resumeRewindSec} s") {
                    set { it.copy(resumeRewindSec = listOf(0, 5, 10, 15, 30).after(it.resumeRewindSec)) }
                }
            }
            item {
                Choice("Preferred audio language", s.preferredAudioLang.ifEmpty { "Server default" }) {
                    set { it.copy(preferredAudioLang = langs.after(it.preferredAudioLang)) }
                }
            }

            item { Header("Subtitles") }
            item {
                Choice("Preferred subtitle language", s.preferredSubtitleLang.ifEmpty { "Server default" }) {
                    set { it.copy(preferredSubtitleLang = langs.after(it.preferredSubtitleLang)) }
                }
            }
            item {
                Choice("Text size", "${(s.subtitleScale * 100).toInt()}%") {
                    set { it.copy(subtitleScale = listOf(0.75f, 0.9f, 1f, 1.15f, 1.3f, 1.5f).after(it.subtitleScale).let { v -> v }) }
                }
            }
            item { Toggle("Background box", s.subtitleBackground) { set { it.copy(subtitleBackground = !it.subtitleBackground) } } }
            item {
                Choice("Distance from bottom", "${s.subtitleBottomPct}%") {
                    set { it.copy(subtitleBottomPct = listOf(4, 8, 12, 16, 20).after(it.subtitleBottomPct)) }
                }
            }
            item {
                Choice("Delay adjustment step", "${s.subtitleDelayStepMs} ms") {
                    set { it.copy(subtitleDelayStepMs = listOf(50, 100, 250, 500, 1000).after(it.subtitleDelayStepMs)) }
                }
            }

            item { Header("Details pages") }
            item {
                Choice("Background video", s.detailVideo.label) {
                    set { it.copy(detailVideo = com.finmax.data.DetailVideo.entries.after(it.detailVideo)) }
                }
            }
            item { Toggle("Background video sound", s.detailVideoSound) { set { it.copy(detailVideoSound = !it.detailVideoSound) } } }

            item { Header("Home screen") }
            item { Toggle("Continue Watching", s.homeContinue) { set { it.copy(homeContinue = !it.homeContinue) } } }
            item { Toggle("Next Up", s.homeNextUp) { set { it.copy(homeNextUp = !it.homeNextUp) } } }
            item { Toggle("Recently added", s.homeLatest) { set { it.copy(homeLatest = !it.homeLatest) } } }
            item { Toggle("My List", s.homeFavorites) { set { it.copy(homeFavorites = !it.homeFavorites) } } }
            item { Toggle("Collections", s.homeCollections) { set { it.copy(homeCollections = !it.homeCollections) } } }
            item { Toggle("Genre picks", s.homeGenres) { set { it.copy(homeGenres = !it.homeGenres) } } }

            item { Header("Bazarr (optional)") }
            item { BazarrSettings() }

            item { Header("About") }
            item {
                Text(
                    "finmax ${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
                    style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
                )
                Text(
                    "Hardware decoding: " + DeviceCapabilities.get(app).summary(),
                    style = MaterialTheme.typography.bodyMedium, color = Fm.TextFaint,
                )
            }
        }
    }
}

private val langs = listOf("", "eng", "spa", "fre", "ger", "ita", "por", "dut", "swe", "nor", "dan", "fin", "pol", "rus", "jpn", "kor", "chi", "ara", "hun", "ukr")

private fun segLabel(a: SegmentAction) = when (a) {
    SegmentAction.ASK -> "Show skip button"
    SegmentAction.AUTO -> "Skip automatically"
    SegmentAction.OFF -> "Don't offer"
}

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, color = Fm.Accent, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))

@Composable
private fun Choice(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Fm.Surface, contentColor = Fm.Text,
            focusedContainerColor = Fm.SurfaceHigh, focusedContentColor = Fm.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Fm.Accent), shape = RoundedCornerShape(10.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium, color = Fm.Accent)
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) = Choice(label, if (on) "On" else "Off", onClick = onClick)

@Composable
private fun BazarrSettings() {
    val app = FinmaxApp.instance
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(app.settings.current.bazarrUrl) }
    var key by remember { mutableStateOf(app.settings.bazarrApiKey.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Lets the subtitle assistant search Bazarr's providers, download alternatives, and run Bazarr's audio-based sync. " +
                "Find the API key in Bazarr → Settings → General.",
            style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            FmTextField(url, { url = it }, "Bazarr address (e.g. http://192.168.1.20:6767)", Modifier.width(380.dp))
            FmTextField(key, { key = it }, "API key", Modifier.width(300.dp), password = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Save & test", {
                app.settings.update { it.copy(bazarrUrl = url.trim()) }
                app.settings.bazarrApiKey = key.trim()
                status = "Testing…"
                scope.launch {
                    status = runCatching { "Connected to Bazarr ${app.bazarr.version()}" }.getOrElse { "Failed: ${friendlyError(it)}" }
                }
            }, primary = true)
            if (url.isNotEmpty()) PillButton("Remove", {
                url = ""; key = ""
                app.settings.update { it.copy(bazarrUrl = "") }
                app.settings.bazarrApiKey = null
                status = "Bazarr disconnected"
            })
            status?.let { Spacer(Modifier.width(8.dp)); Text(it, color = if (it.startsWith("Failed")) Fm.Error else Fm.Success) }
        }
    }
}
