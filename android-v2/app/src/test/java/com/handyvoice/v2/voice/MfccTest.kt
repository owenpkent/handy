package com.handyvoice.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MfccTest {

    private fun sine(freq: Double, durationSec: Double, sampleRate: Int = 16_000): ShortArray {
        val n = (durationSec * sampleRate).toInt()
        return ShortArray(n) { i ->
            (sin(2.0 * PI * freq * i / sampleRate) * 10_000).toInt().toShort()
        }
    }

    @Test fun extract_shape_matches_window_count() {
        val mfcc = Mfcc()
        val samples = sine(440.0, 1.0)
        val out = mfcc.extract(samples)
        // 1 second @ 16 kHz, 25 ms frame / 10 ms hop => about 98 frames
        assertTrue("expected ~98 frames, got ${out.size}", out.size in 95..100)
        assertEquals(13, out[0].size)
    }

    @Test fun extract_is_deterministic() {
        val mfcc = Mfcc()
        val samples = sine(440.0, 0.5)
        val a = mfcc.extract(samples)
        val b = mfcc.extract(samples)
        assertEquals(a.size, b.size)
        for (i in a.indices) for (j in a[i].indices) assertEquals(a[i][j], b[i][j], 1e-12)
    }

    @Test fun different_frequencies_yield_different_features() {
        val mfcc = Mfcc()
        val a = mfcc.extract(sine(440.0, 0.5))
        val b = mfcc.extract(sine(880.0, 0.5))
        // Look at a single frame's coefficients in the middle of the signal.
        val mid = a.size / 2
        var diff = 0.0
        for (k in 1 until 13) diff += kotlin.math.abs(a[mid][k] - b[mid][k])
        assertNotEquals("sine 440 and 880 must produce distinguishable features", 0.0, diff)
    }

    @Test fun empty_input_returns_empty_matrix() {
        val mfcc = Mfcc()
        assertEquals(0, mfcc.extract(ShortArray(0)).size)
    }

    @Test fun short_input_returns_empty_matrix() {
        val mfcc = Mfcc()
        // less than one frame
        assertEquals(0, mfcc.extract(ShortArray(100)).size)
    }
}
