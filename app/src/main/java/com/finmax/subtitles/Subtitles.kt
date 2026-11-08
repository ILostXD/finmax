package com.finmax.subtitles

import kotlin.math.abs
import kotlin.math.roundToLong

/** One subtitle line, times in milliseconds as written in the file (before any correction). */
data class Cue(val start: Long, val end: Long, val text: String)

/**
 * Linear timing correction: a cue written at time t is shown at `t * scale + offsetMs`.
 * Positive offset = subtitles appear later. Scale != 1 corrects frame-rate mismatches.
 */
data class Timing(val offsetMs: Long = 0, val scale: Double = 1.0) {
    fun apply(t: Long): Long = (t * scale).roundToLong() + offsetMs
    fun invert(videoMs: Long): Long = ((videoMs - offsetMs) / scale).roundToLong()
    val isIdentity get() = offsetMs == 0L && scale == 1.0

    companion object {
        /** Timing that makes subtitle time [subMs] land on video time [videoMs], keeping [scale]. */
        fun onePoint(subMs: Long, videoMs: Long, scale: Double = 1.0) =
            Timing(offsetMs = videoMs - (subMs * scale).roundToLong(), scale = scale)

        /** Exact linear fit through two (subtitle time, video time) pairs. Null when the pairs are unusable. */
        fun twoPoint(sub1: Long, video1: Long, sub2: Long, video2: Long): Timing? {
            if (abs(sub2 - sub1) < 60_000) return null // too close together to measure drift reliably
            val scale = (video2 - video1).toDouble() / (sub2 - sub1)
            if (scale !in 0.85..1.15) return null
            return Timing(offsetMs = (video1 - sub1 * scale).roundToLong(), scale = scale)
        }
    }
}

/** Sorted cue list with fast lookup of the line(s) visible at a video position under a [Timing]. */
class CueTrack(cues: List<Cue>) {
    val cues: List<Cue> = cues.sortedBy { it.start }
    private val maxDuration = this.cues.maxOfOrNull { it.end - it.start } ?: 0

    fun textAt(videoMs: Long, timing: Timing): String? {
        val t = timing.invert(videoMs)
        // Binary search for the last cue starting at or before t, then walk back over overlapping cues.
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null
        val lines = ArrayList<String>(2)
        var i = idx
        while (i >= 0 && t - cues[i].start <= maxDuration) {
            if (t < cues[i].end) lines.add(0, cues[i].text)
            i--
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Index of the first cue whose (corrected) start is at or after [videoMs]. */
    fun indexAfter(videoMs: Long, timing: Timing): Int {
        val t = timing.invert(videoMs)
        return cues.indexOfFirst { it.start >= t }.let { if (it < 0) cues.size else it }
    }

    val lastEnd: Long get() = cues.maxOfOrNull { it.end } ?: 0
}

object SubtitleParser {
    private val srtTime = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val vttTime = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{3})""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(content: String, formatHint: String? = null): List<Cue> {
        val text = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val hint = formatHint?.lowercase()
        return when {
            hint == "ass" || hint == "ssa" || text.contains("[Events]") -> parseAss(text)
            hint == "vtt" || hint == "webvtt" || text.trimStart().startsWith("WEBVTT") -> parseBlocks(text, vttTime)
            else -> parseBlocks(text, srtTime)
        }
    }

    private fun ms(m: MatchResult): Long {
        val g = m.groupValues
        val h = g[1].ifEmpty { "0" }.toLong()
        val frac = g[4].padEnd(3, '0').toLong()
        return ((h * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + frac
    }

    private fun clean(s: String) = s.replace(tags, "").replace("\\N", "\n").replace("\\n", "\n").trim()

    private fun parseBlocks(text: String, time: Regex): List<Cue> {
        val out = ArrayList<Cue>()
        for (block in text.split(Regex("\n{2,}"))) {
            val lines = block.lines()
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val (a, b) = lines[ti].split("-->").map { it.trim() }.takeIf { it.size == 2 } ?: continue
            val start = time.find(a)?.let(::ms) ?: continue
            val end = time.find(b)?.let(::ms) ?: continue
            val body = clean(lines.drop(ti + 1).joinToString("\n"))
            if (body.isNotEmpty() && end > start) out += Cue(start, end, body)
        }
        return out
    }

    private fun parseAss(text: String): List<Cue> {
        var format: List<String>? = null
        val out = ArrayList<Cue>()
        val assTime = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{1,3})""")
        for (line in text.lineSequence()) {
            if (line.startsWith("Format:")) format = line.removePrefix("Format:").split(',').map { it.trim().lowercase() }
            if (!line.startsWith("Dialogue:")) continue
            val f = format ?: listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            val parts = line.removePrefix("Dialogue:").split(',', limit = f.size)
            if (parts.size < f.size) continue
            val start = assTime.find(parts[f.indexOf("start")])?.let { assMs(it) } ?: continue
            val end = assTime.find(parts[f.indexOf("end")])?.let { assMs(it) } ?: continue
            val body = clean(parts[f.indexOf("text")])
            if (body.isNotEmpty() && end > start) out += Cue(start, end, body)
        }
        return out
    }

    // ASS uses centiseconds ("0:01:02.34").
    private fun assMs(m: MatchResult): Long {
        val g = m.groupValues
        val frac = when (g[4].length) { 1 -> g[4].toLong() * 100; 2 -> g[4].toLong() * 10; else -> g[4].toLong() }
        return ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + frac
    }
}
