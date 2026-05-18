package com.handyvoice.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtwTest {

    private fun seq(values: DoubleArray): Array<DoubleArray> =
        Array(values.size) { i -> doubleArrayOf(values[i]) }

    @Test fun self_distance_is_zero() {
        val a = seq(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(0.0, Dtw.distance(a, a), 1e-12)
    }

    @Test fun distance_is_symmetric() {
        val a = seq(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0))
        val b = seq(doubleArrayOf(1.0, 3.0, 3.0, 4.0, 6.0))
        val ab = Dtw.distance(a, b)
        val ba = Dtw.distance(b, a)
        assertEquals(ab, ba, 1e-12)
    }

    @Test fun similar_beats_dissimilar() {
        val ref = seq(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 2.0, 1.0, 0.0))
        val similar = seq(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 2.0, 1.0, 0.0, 0.0))  // padded
        val different = seq(doubleArrayOf(5.0, 5.0, 5.0, 0.0, 0.0, 5.0, 5.0))
        val dSim = Dtw.distance(ref, similar)
        val dDif = Dtw.distance(ref, different)
        assertTrue("similar=$dSim must beat different=$dDif", dSim < dDif)
    }

    @Test fun length_invariance_through_normalization() {
        // Same shape, doubled length. Normalized distance should stay near zero.
        val short = seq(doubleArrayOf(0.0, 1.0, 2.0, 1.0, 0.0))
        val long = seq(doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 1.0, 1.0, 0.0, 0.0))
        val d = Dtw.distance(short, long)
        assertTrue("expected small normalized distance, got $d", d < 0.2)
    }

    @Test fun empty_returns_inf() {
        val empty: Array<DoubleArray> = emptyArray()
        val nonempty = seq(doubleArrayOf(1.0))
        assertTrue(Dtw.distance(empty, nonempty).isInfinite())
        assertTrue(Dtw.distance(nonempty, empty).isInfinite())
    }
}
