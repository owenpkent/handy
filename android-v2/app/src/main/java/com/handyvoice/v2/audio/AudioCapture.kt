package com.handyvoice.v2.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.math.abs
import kotlin.math.max

/**
 * Thin wrapper around AudioRecord for 16 kHz mono int16 capture.
 *
 * Matches Vega's audio_io.MicStream: the rest of the pipeline (MFCC, DTW,
 * template references) all assume 16 kHz mono signed 16-bit PCM.
 *
 * Two capture modes:
 *   - readWindow(seconds): blocking, returns a fixed-length window.
 *   - stream(blockSamples) { ... }: callback per block for VAD / always-on.
 *
 * Caller is responsible for RECORD_AUDIO permission; methods are annotated
 * so missing permissions surface as a lint warning rather than a SecurityException
 * at runtime.
 */
class AudioCapture(
    val sampleRate: Int = SAMPLE_RATE,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun readWindow(
        seconds: Double,
        onPeak: ((Int) -> Unit)? = null,
    ): ShortArray {
        val target = (sampleRate * seconds).toInt()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        val bufSize = max(minBuf, target * 2)
        // MIC, not VOICE_RECOGNITION: the latter has tighter routing
        // requirements and on the Android emulator's virtual mic it often
        // returns silence even when the host mic is enabled. MIC is the
        // basic uncoloured input that every audio source on Android maps
        // to, so it works in the emulator and on every real device.
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, CHANNEL, ENCODING, bufSize,
        )
        return try {
            record.startRecording()
            val out = ShortArray(target)
            // ~50 ms reads so the peak callback updates ~20×/sec while
            // capture is running, without sacrificing too much throughput.
            val chunk = sampleRate / 20
            var off = 0
            while (off < target) {
                val want = minOf(chunk, target - off)
                val n = record.read(out, off, want)
                if (n <= 0) break
                if (onPeak != null) onPeak(peakInRange(out, off, off + n))
                off += n
            }
            out
        } finally {
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
    }

    private fun peakInRange(arr: ShortArray, from: Int, to: Int): Int {
        var p = 0
        for (i in from until to) {
            val a = abs(arr[i].toInt())
            if (a > p) p = a
        }
        return p
    }

    /**
     * Stream blocks until [shouldContinue] returns false. The lambda is called
     * with a fresh ShortArray per block (do not retain across calls).
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun stream(blockSamples: Int, shouldContinue: () -> Boolean, onBlock: (ShortArray) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        val bufSize = max(minBuf, blockSamples * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, CHANNEL, ENCODING, bufSize,
        )
        try {
            record.startRecording()
            val block = ShortArray(blockSamples)
            while (shouldContinue()) {
                var off = 0
                while (off < blockSamples) {
                    val n = record.read(block, off, blockSamples - off)
                    if (n <= 0) return
                    off += n
                }
                onBlock(block.copyOf())
            }
        } finally {
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
    }
}
