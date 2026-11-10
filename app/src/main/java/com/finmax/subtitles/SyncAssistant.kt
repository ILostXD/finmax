package com.finmax.subtitles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.finmax.FinmaxApp
import com.finmax.data.Session
import com.finmax.playback.PlayerController
import com.finmax.ui.components.friendlyError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.itemRefreshApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MetadataRefreshMode
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

enum class Origin { TRACK, REMOTE, BAZARR }

class Candidate(
    val id: String,
    val title: String,
    val origin: Origin,
    val notes: List<String>,
    val score: Double,
    val track: MediaStream? = null,
    val remote: RemoteSubtitleInfo? = null,
    val bazarr: BazarrCandidate? = null,
) {
    var cues by mutableStateOf<List<Cue>?>(null)
    var result by mutableStateOf<SyncResult?>(null)
    var status by mutableStateOf<String?>(null)
}

data class SyncReport(
    val headline: String,
    val details: List<String>,
    /** A timing correction the analysis is confident about (preview before applying). */
    val fix: Timing?,
    val problem: SyncProblem?,
    val recommendation: String,
)

/**
 * "Fix subtitle sync": inspects the media and the selected subtitle, works out *what kind* of sync
 * problem exists where that's detectable, gathers alternatives (other tracks, server subtitle
 * providers, Bazarr), and lets the user preview any fix before applying it.
 *
 * Detection needs a reference: ideally a subtitle track embedded in the file (muxed tracks match the
 * video's cut and frame rate). Another external file can be compared too, but only tells how the two
 * differ, so the result is labelled as relative. Without any reference only weak clues remain
 * (duration vs runtime, release-name/edition mismatch), and the assistant says so instead of guessing.
 */
class SyncAssistant(
    private val app: FinmaxApp,
    private val session: Session,
    private val pc: PlayerController,
    private val scope: CoroutineScope,
) {
    var running by mutableStateOf(false); private set
    val steps = mutableStateListOf<String>()
    var report by mutableStateOf<SyncReport?>(null); private set
    val candidates = mutableStateListOf<Candidate>()
    var previewing by mutableStateOf<Candidate?>(null); private set
    var previewingFix by mutableStateOf(false); private set
    var busy by mutableStateOf<String?>(null); private set
    private var reference: Pair<MediaStream, List<Cue>>? = null
    private var job: Job? = null

    private val fileName: String
        get() = pc.source?.path?.substringAfterLast('/')?.substringAfterLast('\\') ?: pc.source?.name ?: pc.item?.name ?: ""

    private fun subName(s: MediaStream) = listOfNotNull(
        s.path?.substringAfterLast('/')?.substringAfterLast('\\'), s.title, s.displayTitle,
    ).joinToString(" ")

    fun run() {
        job?.cancel()
        steps.clear()
        candidates.clear()
        report = null
        reference = null
        cancelPreview()
        running = true
        job = scope.launch {
            try { analyze() } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                report = SyncReport("The analysis stopped", listOf(friendlyError(e)), null, null, "Try again, or adjust the delay manually.")
            }
            running = false
        }
    }

    private suspend fun analyze() {
        val item = pc.item ?: return
        val runtimeMs = (item.runTimeTicks ?: 0) / 10_000
        val video = pc.streams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val fps = video?.realFrameRate ?: video?.averageFrameRate
        val current = pc.currentSubtitle
        steps += "Media: $fileName" + (fps?.let { " · %.3f fps".format(it) } ?: "") + " · ${runtimeMs / 60_000} min"

        val details = mutableListOf<String>()
        var problem: SyncProblem? = null
        var fix: Timing? = null
        val currentCues = pc.cues?.cues

        if (current == null || currentCues == null) {
            steps += when {
                current == null -> "No subtitle selected"
                current.isTextSubtitle -> "The subtitle's text is still coming from the server (embedded tracks can take a few minutes). Try again shortly"
                else -> "Selected subtitle is image-based (${current.codec}) and can't be retimed"
            }
        } else {
            steps += "Subtitle: ${current.displayTitle ?: current.language} · ${current.codec}${if (current.isExternal) " · external file" else " · embedded"} · ${currentCues.size} lines"

            // 1) Edition / release clues.
            if (current.isExternal) {
                val m = ReleaseMatcher.score(fileName, subName(current))
                if (m.editionConflict) details += "The subtitle's name suggests a different edition of the film. ${m.notes.first()}."
            }

            // 2) Compare against tracks embedded in the file (muxed tracks match the video's cut and frame rate).
            //    Failing that, another external file still tells us how the two differ, but not which is right.
            val embedded = pc.subtitleStreams.filter { it.index != current.index && !it.isExternal && it.isTextSubtitle }
            val relative = embedded.isEmpty()
            val refs = embedded.ifEmpty { pc.subtitleStreams.filter { it.index != current.index && it.isTextSubtitle } }
            if (embedded.isEmpty()) steps += "No embedded text subtitles to use as a timing reference"
            var best: Pair<MediaStream, SyncResult>? = null
            var bestCues: List<Cue>? = null
            for (ref in refs.take(3)) {
                steps += "Comparing timing with ${if (relative) "the other subtitle file" else "embedded track"}: ${ref.displayTitle ?: ref.language}"
                val refCues = runCatching { app.subtitles.cues(session, item.id, pc.source!!.id!!, ref.index) }.getOrNull() ?: continue
                val r = SyncAnalyzer.analyze(currentCues, refCues)
                if (best == null || r.confidence > best.second.confidence) {
                    best = ref to r
                    bestCues = refCues
                }
            }
            // Only a muxed track is trusted to rank alternatives; a relative comparison could mislead.
            if (!relative && best != null) reference = best.first to bestCues!!
            if (best != null) {
                val (ref, r) = best
                problem = r.problem
                val pct = (r.confidence * 100).toInt()
                val refName = if (relative) "the ${langName(ref.language) ?: "other"} subtitle file" else "the embedded ${langName(ref.language) ?: ""} track"
                if (relative) {
                    details += "There's no embedded track to trust, so this compares against $refName. " +
                        "That's only a guide if that file is in sync, so preview to judge against the dialogue."
                }
                when (r.problem) {
                    SyncProblem.IN_SYNC -> details += "Timing matches $refName ($pct% of lines align)."
                    SyncProblem.CONSTANT_OFFSET -> {
                        fix = r.timing
                        details += "Compared with $refName, every line is off by the same amount: ${PlayerController.describe(r.timing)} ($pct% of lines align after correction)."
                    }
                    SyncProblem.FRAME_RATE -> {
                        fix = r.timing
                        details += "Compared with $refName, the gap grows through the film: it was timed for a different frame rate (×%.4f). A single delay can't fix this; a speed correction can.".format(r.timing.scale)
                    }
                    SyncProblem.DIFFERENT_CUT -> {
                        val offs = r.sectionOffsetsMs.mapIndexed { i, o -> o?.let { "${i * runtimeMs / 6 / 60_000} min: %+.1f s".format(it / 1000.0) } }.filterNotNull()
                        details += "Compared with $refName, timing jumps between sections (${offs.joinToString(", ")}). It was made for a different cut, so no delay or speed setting fixes it everywhere."
                    }
                    SyncProblem.UNKNOWN -> details += "Couldn't find a consistent match with $refName (it may be a very different translation)."
                }
            } else {
                // 3) No reference at all: weaker, duration-based clue.
                val overrun = SyncAnalyzer.overrunMs(currentCues, runtimeMs)
                if (runtimeMs > 0 && overrun > 30_000) {
                    details += "The last subtitle line appears %.0f s after the video ends, typical of a frame-rate mismatch or a longer cut.".format(overrun / 1000.0)
                }
                details += "Without a reference track, timing can't be measured automatically. Use 'Sync to a line' or Bazarr's audio sync."
            }
        }

        // 4) Alternatives.
        findCandidates(current)

        val best = candidates.maxByOrNull { it.score }
        val recommendation = when {
            problem == SyncProblem.IN_SYNC -> "This subtitle already matches. If a scene still looks off, try 'Sync to a line' there."
            fix != null -> "Preview the correction below, then apply it if dialogue lines up."
            problem == SyncProblem.DIFFERENT_CUT && best != null -> "Try '${best.title}': it's the closest match among the alternatives."
            problem == SyncProblem.DIFFERENT_CUT -> "Look for a subtitle made for this release (search below or in Bazarr)."
            best != null && best.notes.any { it.startsWith("Made for this exact file") } -> "'${best.title}' was made for this exact file (hash match). Preview it."
            app.bazarr.isConfigured && current?.isExternal == true -> "Ask Bazarr to sync this subtitle to the audio, or preview an alternative."
            else -> "Use 'Sync to a line': pick a line the moment you hear it. Do it twice, far apart, to also fix drift."
        }
        val headline = when (problem) {
            SyncProblem.IN_SYNC -> "Subtitles look in sync"
            SyncProblem.CONSTANT_OFFSET -> "Subtitles are consistently ${if ((fix?.offsetMs ?: 0) > 0) "early" else "late"}"
            SyncProblem.FRAME_RATE -> "Frame-rate mismatch detected"
            SyncProblem.DIFFERENT_CUT -> "Subtitle is for a different cut"
            SyncProblem.UNKNOWN, null -> if (current == null) "Choose a subtitle" else "Couldn't measure timing automatically"
        }
        report = SyncReport(headline, details, fix, problem, recommendation)
    }

    private suspend fun findCandidates(current: MediaStream?) {
        val item = pc.item ?: return
        val lang = current?.language ?: app.settings.current.preferredSubtitleLang.ifEmpty { "eng" }

        // Other text tracks in the same language on the server.
        pc.subtitleStreams.filter { it.index != current?.index && it.isTextSubtitle && (it.language == lang || it.language == null) }.forEach { s ->
            val notes = mutableListOf(if (s.isExternal) "External file on server" else "Embedded in the video file")
            var score = if (s.isExternal) 0.5 else 1.0
            if (s.isExternal) {
                val m = ReleaseMatcher.score(fileName, subName(s))
                notes += m.notes
                score += m.value
            }
            candidates += Candidate("t${s.index}", s.displayTitle ?: s.title ?: "Track ${s.index}", Origin.TRACK, notes, score, track = s)
        }

        // Server-side subtitle providers (e.g. OpenSubtitles plugin).
        steps += "Searching the server's subtitle providers ($lang)"
        val remote = runCatching { app.subtitles.searchRemote(session, item.id, lang) }
        remote.onFailure {
            steps += if (it is InvalidStatusException && it.status == 403) {
                "Server subtitle search needs the 'Allow subtitle management' permission for your user (Jellyfin Dashboard, Users)"
            } else {
                "Server subtitle search unavailable: ${friendlyError(it)}"
            }
        }
        remote.getOrNull().orEmpty().take(12).forEach { r ->
            val name = r.name ?: r.id ?: "?"
            val m = ReleaseMatcher.score(fileName, name)
            val notes = mutableListOf<String>()
            if (r.isHashMatch == true) notes += "Made for this exact file (hash match)"
            notes += m.notes
            r.downloadCount?.let { notes += "$it downloads" }
            r.providerName?.let { notes += "via $it" }
            val score = remoteScore(r.isHashMatch == true, m.value, r.downloadCount ?: 0)
            candidates += Candidate("r${r.id}", name, Origin.REMOTE, notes, score, remote = r)
        }
        if (remote.isSuccess && remote.getOrNull().isNullOrEmpty()) steps += "No results from server providers (needs a subtitle plugin such as OpenSubtitles)"

        // Bazarr.
        if (app.bazarr.isConfigured) {
            steps += "Searching Bazarr providers"
            runCatching {
                val media = bazarrMedia() ?: error("This title isn't in Bazarr")
                val lang2 = toTwoLetter(lang)
                app.bazarr.search(media).filter { lang2 == null || it.language.startsWith(lang2, true) }.take(12).forEach { c ->
                    val m = ReleaseMatcher.score(fileName, c.releaseInfo.joinToString(" "))
                    val notes = buildList {
                        add("Bazarr score ${c.score} · ${c.provider}")
                        if ("hash" in c.matches) add("Made for this exact file (hash match)")
                        if (c.matches.isNotEmpty()) add("Matches: ${c.matches.joinToString()}")
                        addAll(m.notes)
                        if (c.hearingImpaired) add("Hearing impaired")
                    }
                    candidates += Candidate("b${c.provider}${c.subtitleId}", c.releaseInfo.firstOrNull() ?: c.provider, Origin.BAZARR, notes, c.score / 100.0 + m.value, bazarr = c)
                }
            }.onFailure { steps += "Bazarr: ${friendlyError(it)}" }
        }
        candidates.sortByDescending { it.score }

        // With a trusted reference, measure the top text candidates directly.
        val ref = reference ?: return
        for (c in candidates.filter { it.origin != Origin.BAZARR }.take(4)) {
            c.status = "Checking timing…"
            runCatching { loadCues(c) }.onSuccess { cues ->
                val r = SyncAnalyzer.analyze(cues, ref.second)
                c.result = r
                c.status = when (r.problem) {
                    SyncProblem.IN_SYNC -> "In sync with the video"
                    SyncProblem.CONSTANT_OFFSET, SyncProblem.FRAME_RATE -> "Fixable: ${PlayerController.describe(r.timing)}"
                    SyncProblem.DIFFERENT_CUT -> "Different cut"
                    SyncProblem.UNKNOWN -> "Timing unclear"
                }
            }.onFailure { c.status = "Couldn't download: ${friendlyError(it)}" }
        }
        candidates.sortByDescending {
            it.score + when (it.result?.problem) {
                SyncProblem.IN_SYNC -> 5.0
                SyncProblem.CONSTANT_OFFSET, SyncProblem.FRAME_RATE -> 3.0
                SyncProblem.DIFFERENT_CUT -> -3.0
                else -> 0.0
            }
        }
    }

    private suspend fun loadCues(c: Candidate): List<Cue> {
        c.cues?.let { return it }
        val item = pc.item!!
        val cues = when (c.origin) {
            Origin.TRACK -> app.subtitles.cues(session, item.id, pc.source!!.id!!, c.track!!.index)
            Origin.REMOTE -> app.subtitles.previewRemote(session, c.remote!!)
            Origin.BAZARR -> error("Bazarr subtitles must be downloaded before previewing")
        }
        c.cues = cues
        return cues
    }

    // ---- Preview / apply ---------------------------------------------------------------------

    fun previewFix() {
        val fix = report?.fix ?: return
        previewing = null
        previewingFix = true
        pc.previewCues = null
        pc.previewTiming = fix
    }

    fun preview(c: Candidate) = scope.launch {
        busy = "Loading ${c.title}…"
        runCatching { loadCues(c) }.onSuccess { cues ->
            previewing = c
            previewingFix = false
            pc.previewCues = CueTrack(cues)
            pc.previewTiming = c.result?.takeIf { it.problem == SyncProblem.CONSTANT_OFFSET || it.problem == SyncProblem.FRAME_RATE }?.timing ?: Timing()
        }.onFailure { c.status = friendlyError(it) }
        busy = null
    }

    fun cancelPreview() {
        previewing = null
        previewingFix = false
        pc.clearPreview()
    }

    /** Commits whatever is being previewed. Server-side changes (downloads) happen only here. */
    fun apply() = scope.launch {
        val timing = pc.previewTiming ?: Timing()
        val c = previewing
        when {
            previewingFix || c == null -> { pc.applyTiming(timing); cancelPreview() }
            c.origin == Origin.TRACK -> {
                pc.item?.id?.let { app.subtitles.rememberTiming(it, c.track!!.syncKey(), timing) }
                cancelPreview()
                pc.selectSubtitle(c.track!!.index)
            }
            c.origin == Origin.REMOTE -> {
                busy = "Saving subtitle to the server…"
                val result = runCatching {
                    val before = pc.subtitleStreams.map { it.syncKey() }.toSet()
                    app.subtitles.downloadRemote(session, pc.item!!.id, c.remote!!)
                    selectNewTrack(before, timing)
                }
                result.onFailure { c.status = "Download failed: ${friendlyError(it)}" }
                busy = null
            }
            else -> Unit
        }
    }

    /** Bazarr candidates are saved on the server first (Bazarr writes next to the video), then selected. */
    fun downloadBazarr(c: Candidate) = scope.launch {
        busy = "Bazarr is downloading ${c.title}…"
        runCatching {
            val media = bazarrMedia() ?: error("This title isn't in Bazarr")
            val before = pc.subtitleStreams.map { it.syncKey() }.toSet()
            app.bazarr.download(media, c.bazarr!!)
            selectNewTrack(before, Timing())
        }.onFailure { c.status = "Failed: ${friendlyError(it)}" }
        busy = null
    }

    /** Runs Bazarr's ffsubsync on the current external subtitle file (rewrites the file on the server). */
    fun bazarrAudioSync() = scope.launch {
        val sub = pc.currentSubtitle ?: return@launch
        busy = "Bazarr is syncing to the audio track (this can take a minute)…"
        runCatching {
            val media = bazarrMedia() ?: error("This title isn't in Bazarr")
            val file = sub.path?.substringAfterLast('/')?.substringAfterLast('\\')
            val target = media.subtitles.firstOrNull { file != null && it.path?.endsWith(file) == true }
                ?: error("Bazarr doesn't manage this subtitle file (only external files Bazarr knows can be synced)")
            app.bazarr.syncToAudio(media, target)
            pc.item?.id?.let { app.subtitles.rememberTiming(it, sub.syncKey(), Timing()) }
            pc.reloadForNewSubtitle(sub.index)
            steps += "Bazarr synced the subtitle file to the audio"
        }.onFailure { steps += "Bazarr sync failed: ${friendlyError(it)}" }
        busy = null
    }

    private suspend fun selectNewTrack(before: Set<String>, timing: Timing) {
        val itemId = pc.item!!.id
        runCatching {
            session.api.itemRefreshApi.refreshItem(itemId = itemId, metadataRefreshMode = MetadataRefreshMode.DEFAULT, replaceAllMetadata = false, replaceAllImages = false)
        }
        // The server needs a moment to notice the new file.
        repeat(15) {
            delay(2_000)
            val streams = runCatching { app.repo.item(itemId).mediaSources?.firstOrNull { it.id == pc.source?.id }?.mediaStreams }.getOrNull().orEmpty()
            val added = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.syncKey() !in before }
            if (added != null) {
                app.subtitles.rememberTiming(itemId, added.syncKey(), timing)
                cancelPreview()
                pc.reloadForNewSubtitle(added.index)
                steps += "Added '${added.displayTitle}' and switched to it"
                return
            }
        }
        error("Saved, but the server hasn't picked the file up yet. It will appear after the next library scan.")
    }

    private suspend fun bazarrMedia(): BazarrMedia? {
        val item = pc.item ?: return null
        return if (item.type == BaseItemKind.EPISODE) {
            val series = item.seriesId?.let { app.repo.item(it) }
            app.bazarr.findEpisode(
                series?.providerIds?.get("Imdb"), series?.providerIds?.get("Tvdb"), item.seriesName,
                item.parentIndexNumber ?: return null, item.indexNumber ?: return null,
            )
        } else {
            app.bazarr.findMovie(item.providerIds?.get("Imdb"), item.name, item.productionYear)
        }
    }

    // ---- Manual "sync to a line" (one or two reference points) ---------------------------------

    val points = mutableStateListOf<Pair<Long, Long>>()

    /** The user pressed OK on [cue] the moment it was spoken. 250 ms allows for reaction time. */
    fun markLine(cue: Cue) {
        val video = pc.positionMs - 250
        points += cue.start to video
        if (points.size > 2) points.removeAt(0)
        val t = if (points.size == 2) {
            val (a, b) = points
            Timing.twoPoint(a.first, a.second, b.first, b.second) ?: Timing.onePoint(cue.start, video, pc.timing.scale)
        } else {
            Timing.onePoint(cue.start, video, pc.timing.scale)
        }
        pc.applyTiming(t)
        pc.notice = if (points.size == 2 && abs(t.scale - 1) > 0.0001) "Synced using two points: ${PlayerController.describe(t)}" else "Synced to that line: ${PlayerController.describe(t)}"
    }

    fun resetPoints() = points.clear()

    companion object {
        /** A server provider's result: a hash match (made for this exact file) outweighs any name match. */
        fun remoteScore(hashMatch: Boolean, nameScore: Double, downloads: Int): Double =
            (if (hashMatch) 3.0 else 0.0) + nameScore + log10(downloads + 1.0) / 10

        private val bCodes = mapOf(
            "ger" to "de", "fre" to "fr", "chi" to "zh", "dut" to "nl", "cze" to "cs", "gre" to "el", "per" to "fa",
            "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
            "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy",
        )
        private val tCodes by lazy {
            Locale.getISOLanguages().associateBy { runCatching { Locale(it).isO3Language }.getOrDefault("") }
        }

        fun langName(code: String?): String? = toTwoLetter(code)?.let { Locale(it).displayLanguage.ifEmpty { null } } ?: code

        fun toTwoLetter(code: String?): String? = when {
            code == null -> null
            code.length == 2 -> code.lowercase()
            else -> bCodes[code.lowercase()] ?: tCodes[code.lowercase()]
        }
    }
}
