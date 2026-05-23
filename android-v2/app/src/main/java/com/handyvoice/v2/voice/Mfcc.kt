package com.handyvoice.v2.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mel-Frequency Cepstral Coefficients extractor.
 *
 * Standard pipeline: pre-emphasis -> framing -> Hamming -> radix-2 FFT ->
 * magnitude spectrum -> mel filterbank -> log -> DCT-II, keep first
 * [numCoeffs] coefficients. 13 coefficients with 25 ms frames and a 10 ms
 * hop is the textbook setup for ASR features.
 *
 * Designed for speaker-dependent template matching: small vocabulary,
 * one speaker. Coefficients are not mean-normalized; downstream DTW does
 * the comparison directly on these vectors.
 */
class Mfcc(
    val sampleRate: Int = 16_000,
    val frameSizeMs: Int = 25,
    val frameHopMs: Int = 10,
    val numMelFilters: Int = 26,
    val numCoeffs: Int = 13,
    val melLowHz: Double = 20.0,
    val melHighHz: Double = 8_000.0,
    val preemphasis: Double = 0.97,
) {
    val frameSize: Int = (sampleRate * frameSizeMs / 1000.0).toInt()  // 400 @ 16k/25ms
    val frameHop: Int = (sampleRate * frameHopMs / 1000.0).toInt()    // 160 @ 16k/10ms
    val fftSize: Int = nextPow2(frameSize)                            // 512 @ 400

    private val hamming: DoubleArray = DoubleArray(frameSize) { i ->
        0.54 - 0.46 * cos(2.0 * PI * i / (frameSize - 1))
    }

    private val melFilters: Array<DoubleArray> = buildMelFilters(
        fftSize = fftSize,
        sampleRate = sampleRate,
        numFilters = numMelFilters,
        lowHz = melLowHz,
        highHz = max(melLowHz + 1, kotlin.math.min(melHighHz, sampleRate / 2.0)),
    )

    private val dctMatrix: Array<DoubleArray> = buildDctMatrix(numCoeffs, numMelFilters)

    /** Extract MFCCs. Returns a matrix of shape [numFrames][numCoeffs]. */
    fun extract(samples: ShortArray): Array<DoubleArray> {
        if (samples.size < frameSize) return emptyArray()
        val pre = preemphasize(samples)
        val numFrames = 1 + (pre.size - frameSize) / frameHop
        val out = Array(numFrames) { DoubleArray(numCoeffs) }
        val frameBuf = DoubleArray(fftSize)
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val powers = DoubleArray(fftSize / 2 + 1)
        val mel = DoubleArray(numMelFilters)

        for (f in 0 until numFrames) {
            val start = f * frameHop
            // Apply Hamming + zero-pad into fft buffer.
            for (i in 0 until frameSize) frameBuf[i] = pre[start + i] * hamming[i]
            for (i in frameSize until fftSize) frameBuf[i] = 0.0

            System.arraycopy(frameBuf, 0, re, 0, fftSize)
            java.util.Arrays.fill(im, 0.0)
            fftInPlace(re, im)

            for (k in 0..fftSize / 2) {
                powers[k] = (re[k] * re[k] + im[k] * im[k]) / fftSize
            }

            // Mel filterbank + log.
            for (m in 0 until numMelFilters) {
                var sum = 0.0
                val filter = melFilters[m]
                for (k in filter.indices) sum += filter[k] * powers[k]
                mel[m] = ln(max(sum, 1e-12))
            }

            // DCT-II.
            val coeffs = out[f]
            for (c in 0 until numCoeffs) {
                var sum = 0.0
                val row = dctMatrix[c]
                for (m in 0 until numMelFilters) sum += row[m] * mel[m]
                coeffs[c] = sum
            }
        }
        // Cepstral Mean Normalization. Subtracting each coefficient's mean
        // across the whole utterance removes the static channel / loudness
        // offset. Without CMN the energy-carrying c0 dominates DTW
        // distance, so two similarly-loud words ("start" vs "stop") match
        // each other's templates better than their own.
        if (out.isNotEmpty()) {
            val means = DoubleArray(numCoeffs)
            for (frame in out) for (c in 0 until numCoeffs) means[c] += frame[c]
            val n = out.size.toDouble()
            for (c in 0 until numCoeffs) means[c] /= n
            for (frame in out) for (c in 0 until numCoeffs) frame[c] -= means[c]
        }
        return out
    }

    private fun preemphasize(samples: ShortArray): DoubleArray {
        val out = DoubleArray(samples.size)
        if (samples.isEmpty()) return out
        out[0] = samples[0] / 32768.0
        for (i in 1 until samples.size) {
            out[i] = (samples[i] / 32768.0) - preemphasis * (samples[i - 1] / 32768.0)
        }
        return out
    }

    private fun buildMelFilters(
        fftSize: Int,
        sampleRate: Int,
        numFilters: Int,
        lowHz: Double,
        highHz: Double,
    ): Array<DoubleArray> {
        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(m: Double): Double = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)

        val melLow = hzToMel(lowHz)
        val melHigh = hzToMel(highHz)
        val points = DoubleArray(numFilters + 2) { i ->
            melToHz(melLow + i * (melHigh - melLow) / (numFilters + 1))
        }
        val bins = IntArray(points.size) { i ->
            kotlin.math.floor((fftSize + 1) * points[i] / sampleRate).toInt()
        }
        val bands = fftSize / 2 + 1
        return Array(numFilters) { m ->
            val left = bins[m]
            val center = bins[m + 1]
            val right = bins[m + 2]
            DoubleArray(bands) { k ->
                when {
                    k < left || k > right -> 0.0
                    k < center -> if (center == left) 0.0 else (k - left).toDouble() / (center - left)
                    else -> if (right == center) 0.0 else (right - k).toDouble() / (right - center)
                }
            }
        }
    }

    private fun buildDctMatrix(numCoeffs: Int, numMel: Int): Array<DoubleArray> {
        val norm0 = sqrt(1.0 / numMel)
        val normN = sqrt(2.0 / numMel)
        return Array(numCoeffs) { c ->
            DoubleArray(numMel) { m ->
                val coef = cos(PI * c * (m + 0.5) / numMel)
                (if (c == 0) norm0 else normN) * coef
            }
        }
    }

    private fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /**
     * Iterative radix-2 Cooley-Tukey FFT, in-place.
     * Operates on arrays whose length is a power of two.
     */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // Bit reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit ushr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // Butterflies.
        var size = 2
        while (size <= n) {
            val half = size / 2
            val theta = -2.0 * PI / size
            val wRe = cos(theta)
            val wIm = sin(theta)
            var start = 0
            while (start < n) {
                var cRe = 1.0
                var cIm = 0.0
                for (k in 0 until half) {
                    val i1 = start + k
                    val i2 = i1 + half
                    val tRe = cRe * re[i2] - cIm * im[i2]
                    val tIm = cRe * im[i2] + cIm * re[i2]
                    re[i2] = re[i1] - tRe
                    im[i2] = im[i1] - tIm
                    re[i1] += tRe
                    im[i1] += tIm
                    val nRe = cRe * wRe - cIm * wIm
                    val nIm = cRe * wIm + cIm * wRe
                    cRe = nRe
                    cIm = nIm
                }
                start += size
            }
            size = size shl 1
        }
    }
}
