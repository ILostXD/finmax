package com.finmax.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftBlurTest {
    @Test fun flatImageStaysFlat() {
        val px = IntArray(20 * 10) { 0xFF336699.toInt() }
        boxBlur(px, 20, 10, 3)
        assertTrue(px.all { it == 0xFF336699.toInt() })
    }

    @Test fun spreadsALineAndKeepsItsBrightness() {
        val w = 41
        val h = 5
        val px = IntArray(w * h) { if (it % w == 20) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        boxBlur(px, w, h, 2)
        val row = (0 until w).map { (px[2 * w + it] shr 16) and 255 }
        assertTrue(row[20] in 1..254) // softened
        assertTrue(row[24] > 0) // spread outwards
        assertEquals(row[18], row[22]) // symmetric
        assertTrue(row.sum() in 200..255) // energy kept (integer rounding loses a little)
    }
}
