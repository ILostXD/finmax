package com.finmax.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SubtitleSyncTest {
    /** ~1400 dialogue lines over a 2 h film with irregular gaps, like real speech. */
    private fun reference(): List<Cue> {
        val rnd = Random(7)
        var t = 30_000L
        return buildList {
            while (t < 7_000_000L) {
                val d = 800L + rnd.nextLong(3500)
                add(Cue(t, t + d, "line"))
                t += d + 200 + rnd.nextLong(6000)
            }
        }
    }

    private fun List<Cue>.shift(f: (Long) -> Long): List<Cue> = map { Cue(f(it.start), f(it.end), it.text) }

    @Test fun detectsConstantOffset() {
        val ref = reference()
        val late = ref.shift { it + 2_300 }
        val r = SyncAnalyzer.analyze(late, ref)
        assertEquals(SyncProblem.CONSTANT_OFFSET, r.problem)
        assertEquals(1.0, r.timing.scale, 0.0)
        assertTrue("offset ${r.timing.offsetMs}", abs(r.timing.offsetMs + 2_300) <= 60)
        assertTrue(r.confidence > 0.9)
    }

    @Test fun detectsFrameRateMismatch() {
        val ref = reference()
        // Subtitle timed for 25 fps (PAL speed-up) played against a 23.976 fps video.
        val pal = ref.shift { (it * 23.976 / 25.0).toLong() + 1_000 }
        val r = SyncAnalyzer.analyze(pal, ref)
        assertEquals(SyncProblem.FRAME_RATE, r.problem)
        assertEquals(25.0 / 23.976, r.timing.scale, 0.0005)
        assertTrue(r.confidence > 0.9)
    }

    @Test fun detectsDifferentCut() {
        val ref = reference()
        // Extended cut: a 40 s scene missing from the video after the 1 h mark.
        val extended = ref.shift { if (it > 3_600_000) it + 40_000 else it }
        val r = SyncAnalyzer.analyze(extended, ref)
        assertEquals(SyncProblem.DIFFERENT_CUT, r.problem)
    }

    @Test fun inSync() {
        val ref = reference()
        assertEquals(SyncProblem.IN_SYNC, SyncAnalyzer.analyze(ref.shift { it + 40 }, ref).problem)
    }

    @Test fun twoPointFixesDrift() {
        val t = Timing.twoPoint(sub1 = 600_000, video1 = 626_000, sub2 = 6_000_000, video2 = 6_257_000)!!
        assertEquals(626_000, t.apply(600_000))
        assertEquals(6_257_000, t.apply(6_000_000))
    }

    @Test fun parsesSrtVttAss() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:00,000 --> 00:01:02,000\r\nBye\r\n"
        val c = SubtitleParser.parse(srt)
        assertEquals(listOf(Cue(1500, 3000, "Hello\nthere"), Cue(60_000, 62_000, "Bye")), c)
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.500 align:start\nHi\n"
        assertEquals(listOf(Cue(1000, 2500, "Hi")), SubtitleParser.parse(vtt))
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:05.20,0:00:07.00,Default,,0,0,0,,{\\an8}Top, line\\Nnext\n"
        assertEquals(listOf(Cue(5200, 7000, "Top, line\nnext")), SubtitleParser.parse(ass))
    }

    @Test fun cueLookupHonoursTiming() {
        val track = CueTrack(listOf(Cue(10_000, 12_000, "A"), Cue(11_000, 13_000, "B")))
        assertEquals("A\nB", track.textAt(11_500, Timing()))
        assertEquals("A", track.textAt(12_500, Timing(offsetMs = 2_000)))
        assertEquals(null, track.textAt(9_000, Timing()))
    }

    @Test fun releaseMatcherFlagsEdition() {
        val media = "The.Movie.2003.EXTENDED.1080p.BluRay.x264-GRP"
        val good = ReleaseMatcher.score(media, "The.Movie.2003.EXTENDED.1080p.BluRay.x264-GRP.en.srt")
        val bad = ReleaseMatcher.score(media, "The.Movie.2003.Theatrical.720p.WEB-DL-OTHER")
        assertTrue(good.value > bad.value)
        assertTrue(bad.editionConflict)
    }
}
