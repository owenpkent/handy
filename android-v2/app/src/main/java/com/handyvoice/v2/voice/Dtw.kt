package com.handyvoice.v2.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Banded Dynamic Time Warping between two MFCC sequences.
 *
 * Each sequence is shape [numFrames][numCoeffs]. The local distance is
 * Euclidean across the coefficient vectors. The Sakoe-Chiba band limits
 * how far the warping path can stray from the diagonal, which both speeds
 * the search and avoids degenerate matches (one frame stretched to span
 * the whole template).
 *
 * Returns the distance normalized by path length, so templates of different
 * lengths compare fairly. Lower is better; identical sequences score 0.
 */
object Dtw {

    private const val INF = Double.POSITIVE_INFINITY

    /**
     * Path-length-normalized DTW distance.
     *
     * @param bandRatio fraction of the longer sequence allowed off-diagonal.
     *   0.15 = "the path can wander up to 15% of the longer sequence".
     */
    fun distance(a: Array<DoubleArray>, b: Array<DoubleArray>, bandRatio: Double = 0.15): Double {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return INF
        val w = max((bandRatio * max(n, m)).toInt(), abs(n - m) + 1)

        // Two-row rolling buffer.
        var prev = DoubleArray(m + 1) { INF }
        var curr = DoubleArray(m + 1) { INF }
        prev[0] = 0.0

        for (i in 1..n) {
            java.util.Arrays.fill(curr, INF)
            val jStart = max(1, i - w)
            val jEnd = min(m, i + w)
            for (j in jStart..jEnd) {
                val cost = euclid(a[i - 1], b[j - 1])
                val best = min(prev[j], min(curr[j - 1], prev[j - 1]))
                curr[j] = cost + best
            }
            val swap = prev; prev = curr; curr = swap
        }

        val raw = prev[m]
        if (raw == INF) return INF
        // Path length is bounded by n + m; normalize by that for length-fairness.
        return raw / (n + m)
    }

    private fun euclid(x: DoubleArray, y: DoubleArray): Double {
        var sum = 0.0
        val len = min(x.size, y.size)
        for (k in 0 until len) {
            val d = x[k] - y[k]
            sum += d * d
        }
        return sqrt(sum)
    }
}
