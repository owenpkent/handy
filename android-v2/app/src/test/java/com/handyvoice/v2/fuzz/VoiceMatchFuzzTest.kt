package com.handyvoice.v2.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.handyvoice.v2.voice.Dtw
import com.handyvoice.v2.voice.Mfcc
import com.handyvoice.v2.voice.MfccDtwEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.math.abs

/**
 * Jazzer fuzz target for the voice matcher: [Mfcc] feature extraction, [Dtw]
 * and [MfccDtwEngine] over arbitrary microphone audio. A match starts and stops
 * the device, so the maths must not produce a NaN or an exception that turns
 * into a wrong or missing command. The fuzzer picks two sample patterns and
 * how long each clip is (the pattern repeats to fill the clip, which reaches
 * silence, DC, clipping and odd lengths cheaply). Invariants:
 *
 * - extraction returns the documented number of frames, 13 finite coefficients
 *   each, and after mean normalization every coefficient averages to zero;
 * - a clip is at distance exactly 0 from itself, distance is symmetric, never
 *   negative or NaN, and finite for any two non-empty clips (the band clamp
 *   always leaves a path);
 * - a query identical to a stored take matches with score 0, and the runner-up
 *   is never better than the winner.
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class VoiceMatchFuzzTest {

    private val mfcc = Mfcc()

    @FuzzTest(maxDuration = "10m")
    fun featuresStayFiniteAndDistancesBehave(data: FuzzedDataProvider) {
        val lenA = data.consumeInt(0, MAX_SAMPLES)
        val lenB = data.consumeInt(0, MAX_SAMPLES)
        val split = data.consumeInt(0, 512)
        val a = features(clip(data.consumeShorts(split), lenA), lenA)
        val b = features(clip(data.consumeShorts(MAX_PATTERN), lenB), lenB)

        assertEquals(Double.POSITIVE_INFINITY, Dtw.distance(a, emptyArray()), "distance to nothing must be infinite")
        if (a.isEmpty() || b.isEmpty()) return

        assertEquals(0.0, Dtw.distance(a, a), "a clip is not at distance 0 from itself")
        val ab = Dtw.distance(a, b)
        assertEquals(ab, Dtw.distance(b, a), "distance is not symmetric")
        assertTrue(ab.isFinite() && ab >= 0.0, "distance $ab")

        val engine = MfccDtwEngine(mfcc)
        engine.addMfccTake("a", a)
        engine.addMfccTake("b", b)
        val match = engine.matchFeatures(a)
        assertNotNull(match, "no match with two takes stored")
        assertEquals(0.0, match!!.score, "a stored take did not match itself")
        assertTrue(match.runnerUp >= match.score, "runner-up ${match.runnerUp} beats the winner")
        if (ab > 0.0) assertEquals("a", match.label, "matched the wrong take")
    }

    /** [pattern] repeated to [length] samples; silence when the pattern is empty. */
    private fun clip(pattern: ShortArray, length: Int): ShortArray =
        ShortArray(length) { if (pattern.isEmpty()) 0 else pattern[it % pattern.size] }

    private fun features(samples: ShortArray, length: Int): Array<DoubleArray> {
        val out = mfcc.extract(samples)
        val expected = if (length < mfcc.frameSize) 0 else 1 + (length - mfcc.frameSize) / mfcc.frameHop
        assertEquals(expected, out.size, "frame count for $length samples")
        val sums = DoubleArray(mfcc.numCoeffs)
        for (frame in out) {
            assertEquals(mfcc.numCoeffs, frame.size)
            for (c in frame.indices) {
                assertTrue(frame[c].isFinite(), "coefficient $c is ${frame[c]}")
                sums[c] += frame[c]
            }
        }
        for (c in sums.indices) {
            if (out.isNotEmpty()) assertTrue(abs(sums[c] / out.size) < 1e-6, "coefficient $c is not mean-normalized")
        }
        return out
    }

    private companion object {
        /** Half a second at 16 kHz: about 48 frames, enough for the DTW band to matter. */
        const val MAX_SAMPLES = 8_000
        const val MAX_PATTERN = 2_048
    }
}
