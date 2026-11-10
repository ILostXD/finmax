package com.finmax.subtitles

import kotlin.math.abs
import kotlin.math.roundToLong

enum class SyncProblem {
    /** Already lines up with the reference. */
    IN_SYNC,
    /** Consistently early/late by the same amount: a single delay fixes it. */
    CONSTANT_OFFSET,
    /** Drift that grows over the runtime: made for another frame rate (e.g. 23.976 vs 25 fps). */
    FRAME_RATE,
    /** Offset jumps between sections: subtitle was made for a different cut/edit. A delay cannot fix it. */
    DIFFERENT_CUT,
    /** Not enough evidence either way. */
    UNKNOWN,
}

data class SyncResult(
    val problem: SyncProblem,
    val timing: Timing,
    /** Fraction of subtitle lines that land within 0.4 s of a reference line after correction (0..1). */
    val confidence: Double,
    /** Offset measured in each sixth of the runtime, for explaining DIFFERENT_CUT to the user. */
    val sectionOffsetsMs: List<Long?>,
)

/**
 * Aligns a subtitle against a reference subtitle that is known to match the video (e.g. a track
 * muxed into the file). Language does not matter: dialogue timing is what is compared.
 *
 * Method: for each plausible frame-rate scale, every (subtitle line, nearby reference line) pair
 * votes for the offset that would join them; the true correction produces a sharp peak while
 * wrong ones scatter. The winner is then refined with a least-squares fit over matched pairs.
 */
object SyncAnalyzer {
    /** Common frame-rate conversions: 23.976/24/25/29.97 in both directions. */
    val SCALES = listOf(
        1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0,
        29.97 / 23.976, 23.976 / 29.97, 29.97 / 25.0, 25.0 / 29.97,
    )
    private const val BIN = 100L
    private const val MAX_OFFSET = 180_000L
    private const val MATCH_WINDOW = 400L

    fun analyze(target: List<Cue>, reference: List<Cue>): SyncResult {
        val t = target.map { it.start }.sorted()
        val r = reference.map { it.start }.sorted()
        if (t.size < 20 || r.size < 20) return SyncResult(SyncProblem.UNKNOWN, Timing(), 0.0, emptyList())

        var best = Triple(1.0, 0L, -1) // scale, offset, votes
        for (s in SCALES) {
            val (off, votes) = peakOffset(t, r, s)
            // Prefer scale 1.0 unless another scale is clearly better (avoids false frame-rate calls).
            val adj = if (s == 1.0) votes * 1.15 else votes.toDouble()
            val bestAdj = if (best.first == 1.0) best.third * 1.15 else best.third.toDouble()
            if (adj > bestAdj) best = Triple(s, off, votes)
        }
        val coarse = Timing(best.second, best.first)
        val fitted = refine(t, r, coarse) ?: coarse
        val confidence = matchedFraction(t, r, fitted)
        val sections = sectionOffsets(t, r, fitted.scale)

        val problem = when {
            confidence < 0.25 -> if (sectionsDisagree(sections)) SyncProblem.DIFFERENT_CUT else SyncProblem.UNKNOWN
            sectionsDisagree(sections) -> SyncProblem.DIFFERENT_CUT
            abs(fitted.scale - 1.0) > 0.0005 -> SyncProblem.FRAME_RATE
            abs(fitted.offsetMs) <= 150 -> SyncProblem.IN_SYNC
            else -> SyncProblem.CONSTANT_OFFSET
        }
        return SyncResult(problem, if (problem == SyncProblem.IN_SYNC) Timing() else fitted, confidence, sections)
    }

    private fun peakOffset(t: List<Long>, r: List<Long>, scale: Double): Pair<Long, Int> {
        val votes = HashMap<Long, Int>()
        var lo = 0
        for (ts in t) {
            val x = (ts * scale).roundToLong()
            while (lo < r.size && r[lo] < x - MAX_OFFSET) lo++
            var j = lo
            while (j < r.size && r[j] <= x + MAX_OFFSET) {
                val bin = Math.floorDiv(r[j] - x, BIN)
                votes[bin] = (votes[bin] ?: 0) + 1
                j++
            }
        }
        // Smooth over neighbouring bins so jitter of ±100 ms does not split the peak.
        var bestBin = 0L
        var bestVotes = -1
        for (bin in votes.keys) {
            val v = (votes[bin - 1] ?: 0) + votes[bin]!! + (votes[bin + 1] ?: 0)
            if (v > bestVotes) { bestVotes = v; bestBin = bin }
        }
        return bestBin * BIN + BIN / 2 to bestVotes
    }

    private fun nearest(r: List<Long>, x: Long): Long? {
        var i = r.binarySearch(x)
        if (i >= 0) return r[i]
        i = -i - 1
        val a = r.getOrNull(i - 1)
        val b = r.getOrNull(i)
        return listOfNotNull(a, b).minByOrNull { abs(it - x) }
    }

    private fun refine(t: List<Long>, r: List<Long>, timing: Timing): Timing? {
        val pairs = t.mapNotNull { ts ->
            val x = timing.apply(ts)
            nearest(r, x)?.takeIf { abs(it - x) <= MATCH_WINDOW }?.let { ts.toDouble() to it.toDouble() }
        }
        if (pairs.size < 10) return null
        val n = pairs.size
        val mx = pairs.sumOf { it.first } / n
        val my = pairs.sumOf { it.second } / n
        val sxx = pairs.sumOf { (it.first - mx) * (it.first - mx) }
        val sxy = pairs.sumOf { (it.first - mx) * (it.second - my) }
        if (sxx == 0.0) return null
        var a = sxy / sxx
        // Snap to exact scale when within noise, so "constant offset" stays a pure delay.
        if (abs(a - timing.scale) < 0.0004) a = timing.scale
        return Timing(offsetMs = (my - a * mx).roundToLong(), scale = a)
    }

    fun matchedFraction(t: List<Long>, r: List<Long>, timing: Timing): Double {
        if (t.isEmpty()) return 0.0
        val hits = t.count { ts -> nearest(r, timing.apply(ts))?.let { abs(it - timing.apply(ts)) <= MATCH_WINDOW } == true }
        return hits.toDouble() / t.size
    }

    private fun sectionOffsets(t: List<Long>, r: List<Long>, scale: Double): List<Long?> {
        val end = t.last()
        return (0 until 6).map { k ->
            val chunk = t.filter { it >= end * k / 6 && it < end * (k + 1) / 6 + 1 }
            if (chunk.size < 8) null else peakOffset(chunk, r, scale).let { (off, v) -> if (v >= chunk.size / 3) off else null }
        }
    }

    /** Stepwise jumps (> 1.5 s) between well-measured sections suggest a different cut. */
    private fun sectionsDisagree(offsets: List<Long?>): Boolean {
        val known = offsets.filterNotNull()
        if (known.size < 3) return false
        return known.max() - known.min() > 1_500
    }

    /** Duration-only heuristic when no reference exists: how far past the video's end the subtitles run. */
    fun overrunMs(cues: List<Cue>, runtimeMs: Long): Long = (cues.maxOfOrNull { it.end } ?: 0) - runtimeMs
}

/** Release-name similarity used to rank alternative subtitle files against the media file. */
object ReleaseMatcher {
    private val editionTokens = setOf("extended", "unrated", "directors", "dc", "theatrical", "remastered", "imax", "uncut", "special", "final")
    private val sourceTokens = setOf("bluray", "bdrip", "brrip", "webdl", "webrip", "web", "hdtv", "dvdrip", "remux", "hdrip", "amzn", "nf", "dsnp", "hmax", "atvp")
    private val noise = setOf("x264", "x265", "h264", "h265", "hevc", "avc", "aac", "ac3", "dts", "ddp", "dd", "atmos", "10bit", "hdr", "sdr", "eng", "english", "srt", "sub", "subs", "mkv", "mp4")

    fun tokens(name: String): Set<String> =
        name.lowercase().replace("web-dl", "webdl").replace("director's", "directors")
            .split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()

    data class Score(val value: Double, val editionConflict: Boolean, val notes: List<String>)

    fun score(mediaName: String, subtitleName: String): Score {
        val m = tokens(mediaName)
        val s = tokens(subtitleName)
        val notes = mutableListOf<String>()
        val mEd = m intersect editionTokens
        val sEd = s intersect editionTokens
        val editionConflict = mEd != sEd && (mEd.isNotEmpty() || sEd.isNotEmpty())
        if (editionConflict) notes += "Edition differs (${sEd.ifEmpty { setOf("standard") }.joinToString()} vs ${mEd.ifEmpty { setOf("standard") }.joinToString()})"
        val sharedSource = m intersect s intersect sourceTokens
        if (sharedSource.isNotEmpty()) notes += "Same source: ${sharedSource.joinToString()}"
        val res = Regex("(\\d{3,4})p")
        val mRes = m.firstOrNull { res.matches(it) }
        if (mRes != null && mRes in s) notes += "Same resolution"
        // The release group is usually the last token of a scene name, before the file extension
        // (taking the extension itself meant real file names never matched a group).
        val group = m.lastOrNull { it !in noise && it !in sourceTokens && !res.matches(it) }
        val groupMatch = group != null && group in s
        if (groupMatch) notes += "Same release group ($group)"
        val useful = (m - noise)
        val overlap = if (useful.isEmpty()) 0.0 else (useful intersect s).size.toDouble() / useful.size
        val value = overlap + (if (groupMatch) 0.5 else 0.0) + 0.2 * sharedSource.size - (if (editionConflict) 0.8 else 0.0)
        return Score(value, editionConflict, notes)
    }
}
