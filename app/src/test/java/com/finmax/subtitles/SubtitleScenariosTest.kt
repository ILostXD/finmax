package com.finmax.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The subtitle scenarios finmax claims to handle, against realistic data: the reference track and
 * the subtitle under test are authored independently from the same dialogue (different line
 * splits, timing jitter, dropped and extra lines), and the subtitle goes through SRT text and the
 * parser, as it does in the app. Then it is broken on purpose in each way the assistant must spot.
 */
class SubtitleScenariosTest {
    /** When each line of dialogue is actually spoken in the video, and for how long. */
    private class Speech(val start: Long, val end: Long)

    private fun film(seed: Long, lengthMs: Long = 7_200_000): List<Speech> {
        val r = java.util.Random(seed)
        var t = 45_000L
        return buildList {
            while (t < lengthMs - 60_000) {
                val d = 900L + (r.nextDouble() * 4_200).toLong()
                add(Speech(t, t + d))
                // Conversation, with the odd long silent stretch (action, music).
                t += d + 150 + (if (r.nextDouble() < 0.06) 20_000 + r.nextInt(70_000) else r.nextInt(4_500))
            }
        }
    }

    /** One subtitler's take on the dialogue: their own timing errors, line splits, merges and omissions. */
    private fun author(speech: List<Speech>, seed: Long, jitterMs: Double, dropRate: Double, mergeRate: Double, splitRate: Double, extras: Double): List<Cue> {
        val r = java.util.Random(seed)
        val out = mutableListOf<Cue>()
        var i = 0
        while (i < speech.size) {
            val s = speech[i]
            val j = { (r.nextGaussian() * jitterMs).toLong() }
            when {
                r.nextDouble() < dropRate -> Unit
                i + 1 < speech.size && r.nextDouble() < mergeRate -> {
                    out += Cue(s.start + j(), speech[i + 1].end + j(), "Line $i and ${i + 1}")
                    i++
                }
                r.nextDouble() < splitRate -> {
                    val mid = (s.start + s.end) / 2
                    out += Cue(s.start + j(), mid, "Line $i, first half")
                    out += Cue(mid + 80, s.end + j(), "Line $i, second half")
                }
                else -> out += Cue(s.start + j(), s.end + j(), "Line $i")
            }
            if (r.nextDouble() < extras) out += Cue(s.end + 300, s.end + 1_800, "[MUSIC PLAYING]")
            i++
        }
        return out.sortedBy { it.start }
    }

    /** The embedded track: tight timing, a few hearing-impaired extras. */
    private fun embedded(speech: List<Speech>) = author(speech, 11, jitterMs = 90.0, dropRate = 0.03, mergeRate = 0.05, splitRate = 0.08, extras = 0.06)

    /** A downloaded file, written to SRT and parsed back like the app does. */
    private fun downloaded(speech: List<Speech>, seed: Long = 23, change: (Long) -> Long = { it }): List<Cue> {
        val cues = author(speech, seed, jitterMs = 200.0, dropRate = 0.10, mergeRate = 0.15, splitRate = 0.05, extras = 0.0)
            .map { Cue(change(it.start).coerceAtLeast(0), change(it.end).coerceAtLeast(1), it.text) }
        return SubtitleParser.parse(toSrt(cues), "srt")
    }

    private fun toSrt(cues: List<Cue>) = cues.mapIndexed { n, c -> "${n + 1}\r\n${stamp(c.start)} --> ${stamp(c.end)}\r\n${c.text}\r\n" }.joinToString("\r\n")

    private fun stamp(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)

    /** Share of lines that, after the correction, start within 0.4 s of when they are spoken. */
    private fun landed(cues: List<Cue>, speech: List<Speech>, t: Timing): Double {
        val starts = speech.map { it.start }
        return cues.count { c -> val x = t.apply(c.start); starts.any { abs(it - x) <= 400 } }.toDouble() / cues.size
    }

    @Test fun inSyncSubtitleIsLeftAlone() {
        val speech = film(1)
        val r = SyncAnalyzer.analyze(downloaded(speech), embedded(speech))
        assertEquals(SyncProblem.IN_SYNC, r.problem)
    }

    @Test fun lateSubtitleGetsOneDelay() {
        val speech = film(2)
        val r = SyncAnalyzer.analyze(downloaded(speech) { it + 2_300 }, embedded(speech))
        assertEquals(SyncProblem.CONSTANT_OFFSET, r.problem)
        assertEquals(1.0, r.timing.scale, 0.0)
        assertTrue("offset ${r.timing.offsetMs}", abs(r.timing.offsetMs + 2_300) <= 150)
    }

    @Test fun earlySubtitleGetsOneDelay() {
        val speech = film(3)
        val r = SyncAnalyzer.analyze(downloaded(speech) { it - 1_700 }, embedded(speech))
        assertEquals(SyncProblem.CONSTANT_OFFSET, r.problem)
        assertTrue("offset ${r.timing.offsetMs}", abs(r.timing.offsetMs - 1_700) <= 150)
    }

    @Test fun palSubtitleOnFilmVideoNeedsStretch() {
        val speech = film(4)
        // Timed for a 25 fps (PAL) release, played against 23.976 fps video: 4% fast, drifting ~5 min by the end.
        val target = downloaded(speech) { (it * 23.976 / 25.0).roundToLong() }
        val r = SyncAnalyzer.analyze(target, embedded(speech))
        assertEquals(SyncProblem.FRAME_RATE, r.problem)
        assertEquals(25.0 / 23.976, r.timing.scale, 0.0005)
        assertTrue("after fix ${landed(target, speech, r.timing)}", landed(target, speech, r.timing) > 0.85)
        // No single delay could have done it.
        assertTrue(landed(target, speech, Timing(offsetMs = r.timing.offsetMs)) < 0.3)
    }

    @Test fun subtleDriftFrom24To23976IsCaught() {
        val speech = film(5)
        // 0.1 % slow: only ~7 s off by the end, in sync at the start.
        val target = downloaded(speech) { (it * 24.0 / 23.976).roundToLong() }
        val r = SyncAnalyzer.analyze(target, embedded(speech))
        assertEquals(SyncProblem.FRAME_RATE, r.problem)
        assertTrue("after fix ${landed(target, speech, r.timing)}", landed(target, speech, r.timing) > 0.85)
    }

    @Test fun extendedCutIsCalledADifferentCut() {
        val speech = film(6)
        // Made for an extended edition: a 40 s scene the video doesn't have, at the 1 h mark.
        val r = SyncAnalyzer.analyze(downloaded(speech) { if (it > 3_600_000) it + 40_000 else it }, embedded(speech))
        assertEquals(SyncProblem.DIFFERENT_CUT, r.problem)
    }

    @Test fun shorterCutIsCalledADifferentCut() {
        val speech = film(7)
        // Made for a version with a longer intro: everything before 20 min is 12 s later, then lines up.
        val r = SyncAnalyzer.analyze(downloaded(speech) { if (it < 1_200_000) it + 12_000 else it }, embedded(speech))
        assertEquals(SyncProblem.DIFFERENT_CUT, r.problem)
    }

    @Test fun subtitleForAnotherFilmIsNotGivenAFakeFix() {
        val r = SyncAnalyzer.analyze(downloaded(film(8)), embedded(film(9)))
        assertTrue("got ${r.problem}", r.problem == SyncProblem.UNKNOWN || r.problem == SyncProblem.DIFFERENT_CUT)
        assertTrue(r.confidence < 0.5)
    }

    @Test fun episodeLengthWorksToo() {
        val speech = film(10, lengthMs = 1_320_000) // 22 min
        val r = SyncAnalyzer.analyze(downloaded(speech) { it + 900 }, embedded(speech))
        assertEquals(SyncProblem.CONSTANT_OFFSET, r.problem)
        assertTrue("offset ${r.timing.offsetMs}", abs(r.timing.offsetMs + 900) <= 150)
    }

    // ---- Sync to a line: the user presses OK when they hear a listed line -------------------

    /** What SyncAssistant.markLine does: the press minus 250 ms for reaction time. */
    private fun press(spokenAt: Long, reactionMs: Long) = spokenAt + reactionMs - 250

    @Test fun onePressFixesAConstantDelay() {
        val speech = film(12)
        val target = downloaded(speech) { it + 3_000 }
        val line = target[200]
        val spoken = speech.minBy { abs(it.start - (line.start - 3_000)) }.start
        val t = Timing.onePoint(line.start, press(spoken, reactionMs = 380))
        // Lands within ~0.3 s of the true -3 s: the press carries the viewer's reaction error and
        // that one line's own timing, so it gets close and a small nudge (or a second press) finishes it.
        assertTrue("offset ${t.offsetMs}", abs(t.offsetMs + 3_000) <= 400)
    }

    @Test fun twoPressesFarApartFixDrift() {
        val speech = film(13)
        val change = { ms: Long -> (ms * 23.976 / 25.0).roundToLong() + 1_500 }
        val target = downloaded(speech) { change(it) }
        fun spokenFor(c: Cue) = speech.minBy { abs(change(it.start) - c.start) }.start
        val a = target[100]
        val b = target[target.size - 150]
        val t = Timing.twoPoint(a.start, press(spokenFor(a), 300), b.start, press(spokenFor(b), 420))!!
        // The exact inverse of the drift, and how close the two presses get to it across the film.
        val exact = Timing(offsetMs = (-1_500 * 25.0 / 23.976).roundToLong(), scale = 25.0 / 23.976)
        assertEquals(exact.scale, t.scale, 0.001)
        val worst = listOf(0L, 3_600_000L, 7_000_000L).maxOf { abs(t.apply(it) - exact.apply(it)) }
        assertTrue("worst $worst ms", worst <= 500)
        // One press alone leaves the far end of the film minutes off.
        assertTrue(landed(target, speech, Timing.onePoint(a.start, press(spokenFor(a), 300))) < 0.3)
    }

    @Test fun twoPressesTooCloseAreRefused() {
        assertNull(Timing.twoPoint(600_000, 600_500, 630_000, 630_400))
    }

    // ---- Ranking alternatives ---------------------------------------------------------------

    @Test fun releaseNameMatchBeatsOtherReleases() {
        val media = "Heat.1995.Directors.Definitive.Edition.2160p.UHD.BluRay.REMUX.HDR.HEVC-FGT.mkv"
        val same = ReleaseMatcher.score(media, "Heat.1995.Directors.Definitive.Edition.2160p.UHD.BluRay.REMUX-FGT")
        val web = ReleaseMatcher.score(media, "Heat.1995.1080p.WEB-DL.DD5.1.H264-NTb")
        assertTrue(same.value > web.value)
        assertTrue(web.editionConflict)
        assertTrue(same.notes.any { it.startsWith("Same release group") })
    }

    @Test fun hashMatchOutranksAnyNameMatch() {
        val media = "Heat.1995.1080p.BluRay.x264-SPARKS.mkv"
        val bestName = ReleaseMatcher.score(media, "Heat.1995.1080p.BluRay.x264-SPARKS").value
        val unrelatedName = ReleaseMatcher.score(media, "Heat.1995.720p.WEB").value
        assertTrue(SyncAssistant.remoteScore(hashMatch = true, nameScore = unrelatedName, downloads = 0) > SyncAssistant.remoteScore(hashMatch = false, nameScore = bestName, downloads = 50_000))
    }

    @Test fun styledAssBecomesPlainRetimableText() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:01:02.50,0:01:04.00,Sign,,0,0,0,,{\\pos(320,50)\\fs40\\c&H00FFFF&}Tokyo, 1999\n" +
            "Dialogue: 0,0:01:05.00,0:01:07.25,Default,,0,0,0,,{\\i1}I never{\\i0} said that.\\NNever.\n"
        val cues = SubtitleParser.parse(ass)
        assertEquals(listOf(Cue(62_500, 64_000, "Tokyo, 1999"), Cue(65_000, 67_250, "I never said that.\nNever.")), cues)
        assertNotEquals(cues, cues.map { Cue(Timing(offsetMs = 500).apply(it.start), Timing(offsetMs = 500).apply(it.end), it.text) })
    }
}
