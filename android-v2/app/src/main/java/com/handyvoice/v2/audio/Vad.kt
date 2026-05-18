package com.handyvoice.v2.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Energy + zero-crossing-rate voice activity detector.
 *
 * Designed for the "always-on listening" mode: we slide small blocks
 * (e.g. 30 ms) past Vad, and when several consecutive blocks look like
 * speech we open a capture window that the matcher scores.
 *
 * The thresholds here are conservative on purpose; tune via Settings.
 */
class Vad(
    private val energyThreshold: Double = 0.012,
    private val zcrLow: Double = 0.02,
    private val zcrHigh: Double = 0.35,
) {
    /** True if this block looks like voiced speech. */
    fun isSpeech(samples: ShortArray): Boolean {
        if (samples.isEmpty()) return false

        var sumSq = 0.0
        var crossings = 0
        var prev: Short = 0
        for (i in samples.indices) {
            val s = samples[i]
            val f = s / 32768.0
            sumSq += f * f
            if (i > 0 && ((s.toInt() xor prev.toInt()) and 0x8000) != 0) crossings++
            prev = s
        }
        val rms = sqrt(sumSq / samples.size)
        val zcr = crossings.toDouble() / samples.size

        // Speech: enough energy AND zero-crossing rate in the voiced range.
        // Hiss / fan noise tends to be high-zcr; thumps are high-energy / low-zcr.
        return rms > energyThreshold && zcr in zcrLow..zcrHigh
    }

    /** Pure peak helper, exposed for the training UI's level meter. */
    fun peak(samples: ShortArray): Int {
        var p = 0
        for (s in samples) {
            val v = abs(s.toInt())
            if (v > p) p = v
        }
        return p
    }
}
