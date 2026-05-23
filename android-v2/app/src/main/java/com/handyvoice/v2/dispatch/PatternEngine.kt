package com.handyvoice.v2.dispatch

import com.handyvoice.v2.api.HandyV2ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Time-based motion-envelope engine. Ported from
 * `homeassistant/custom_components/handy/patterns.py`.
 *
 * Each pattern is a `suspend` function that loops forever, driving the
 * device via [HandyV2ApiClient.setSpeed]. The caller's `intensity` (0-100)
 * is the pattern's *peak* velocity; the envelope multiplies it down per
 * step. Cancellation stops the loop immediately and the engine sends
 * `hamp/stop` to the device.
 *
 * Why HAMP and not HRPP/HSSP: see HA `patterns.py` header.
 */
class PatternEngine(
    private val api: HandyV2ApiClient,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var currentName: String? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    val current: String?
        get() = currentName

    /**
     * Start [name] at the given [intensity] (0-100). Cancels any running
     * pattern first. Ensures the device is in motion (`hamp/start`) before
     * the first velocity write. Returns false if the pattern is unknown or
     * if `hamp/start` failed (typically: not connected) — caller can
     * surface this to the UI.
     */
    suspend fun start(name: String, intensity: Int): Boolean {
        val patternFn = PATTERNS[name] ?: return false
        cancel()
        val peak = intensity.coerceIn(0, 100)
        if (!api.isRunning.value) {
            if (!api.start()) return false
        }
        currentName = name
        job = scope.launch {
            try {
                patternFn(api, peak)
            } catch (_: CancellationException) {
                // expected on stop
            } catch (_: Throwable) {
                // Don't let a transient failure inside a pattern bring down
                // the whole lifecycle scope. The pattern just dies; user can
                // restart it.
            }
        }
        return true
    }

    /** Cancel any running pattern and stop the device. */
    suspend fun stop() {
        cancel()
        api.stop()
    }

    /** Cancel the pattern coroutine without touching the device. */
    suspend fun cancel() {
        job?.let { j ->
            if (j.isActive) {
                j.cancel()
                runCatching { j.join() }
            }
        }
        job = null
        currentName = null
    }

    companion object {
        /** Floor for any delay sent to the device, matches HA's safety floor. */
        private const val MIN_STEP_MS = 50L

        private val PATTERNS: Map<String, suspend (HandyV2ApiClient, Int) -> Unit> = mapOf(
            // From HA `patterns.py`.
            "heartbeat" to ::heartbeat,
            "wave"      to ::wave,
            "surge"     to ::surge,
            "breathe"   to ::breathe,
            "storm"     to ::storm,
            // Original to android-v2; envelopes designed to feel distinct
            // from each other and from the HA patterns above.
            "pulse"     to ::pulse,
            "tease"     to ::tease,
            "edge"      to ::edge,
            "rush"      to ::rush,
            "throb"     to ::throb,
            "bounce"    to ::bounce,
            "climax"    to ::climax,
        )

        /** Lub-dub rhythm: two pulses then a long rest. ~2 s cycle. */
        private suspend fun heartbeat(api: HandyV2ApiClient, intensity: Int) {
            while (coroutineContext.isActive) {
                emit(api, 0.85, intensity, 150)  // lub
                emit(api, 0.0, intensity, 180)   // gap
                emit(api, 0.7, intensity, 150)   // dub
                emit(api, 0.0, intensity, 1500)  // rest
            }
        }

        /** Smooth sine swell, 0 -> peak -> 0. 8 s cycle. */
        private suspend fun wave(api: HandyV2ApiClient, intensity: Int) {
            val periodMs = 8000L
            val steps = 32
            val dt = periodMs / steps
            while (coroutineContext.isActive) {
                for (i in 0 until steps) {
                    val phase = (2.0 * PI * i) / steps
                    val env = 0.5 - 0.5 * cos(phase)
                    emit(api, env, intensity, dt)
                }
            }
        }

        /** Long linear ramp to peak, brief hold, drop, pause. ~24 s cycle. */
        private suspend fun surge(api: HandyV2ApiClient, intensity: Int) {
            val steps = 24
            while (coroutineContext.isActive) {
                for (i in 1..steps) {
                    emit(api, i.toDouble() / steps, intensity, 500)
                }
                emit(api, 1.0, intensity, 4000)  // hold at peak
                emit(api, 0.0, intensity, 3000)  // rest
            }
        }

        /** Gentle in-out, never zero, never peak. 6 s cycle. */
        private suspend fun breathe(api: HandyV2ApiClient, intensity: Int) {
            val periodMs = 6000L
            val steps = 24
            val dt = periodMs / steps
            while (coroutineContext.isActive) {
                for (i in 0 until steps) {
                    val phase = (2.0 * PI * i) / steps
                    // Range 0.10..0.60 — soft baseline, never silent, never intense.
                    val env = 0.10 + 0.50 * (0.5 - 0.5 * cos(phase))
                    emit(api, env, intensity, dt)
                }
            }
        }

        /** Random walk: unpredictable bursts and pauses. */
        private suspend fun storm(api: HandyV2ApiClient, intensity: Int) {
            while (coroutineContext.isActive) {
                val env = Random.nextDouble()
                val durationMs = (400 + Random.nextDouble() * 1200).toLong()  // 0.4..1.6 s
                emit(api, env, intensity, durationMs)
            }
        }

        /** Steady metronome: even high-low pulses, no rest. ~400 ms cycle. */
        private suspend fun pulse(api: HandyV2ApiClient, intensity: Int) {
            while (coroutineContext.isActive) {
                emit(api, 0.75, intensity, 200)
                emit(api, 0.0, intensity, 200)
            }
        }

        /** Sparse light touches with long silences in between. ~5 s cycle. */
        private suspend fun tease(api: HandyV2ApiClient, intensity: Int) {
            while (coroutineContext.isActive) {
                emit(api, 0.40, intensity, 500)
                emit(api, 0.0, intensity, 2000)
                emit(api, 0.30, intensity, 300)
                emit(api, 0.0, intensity, 3000)
            }
        }

        /**
         * Slow climb to almost-peak, hold there, then drop completely.
         * 5 s ramp / 6 s hold at 0.95 / 4 s rest. ~15 s cycle.
         */
        private suspend fun edge(api: HandyV2ApiClient, intensity: Int) {
            val rampSteps = 20
            val rampDtMs = 250L  // 5 s ramp
            while (coroutineContext.isActive) {
                for (i in 1..rampSteps) {
                    emit(api, 0.95 * i / rampSteps, intensity, rampDtMs)
                }
                emit(api, 0.95, intensity, 6000)  // hold near peak
                emit(api, 0.0, intensity, 4000)   // drop
            }
        }

        /** Aggressive: sustained near-peak. Looped in ~500 ms slices so the
         *  pattern is cancellable. */
        private suspend fun rush(api: HandyV2ApiClient, intensity: Int) {
            while (coroutineContext.isActive) {
                emit(api, 0.95, intensity, 500)
            }
        }

        /** Heavy single pulses, slower and more emphatic than pulse. ~1.1 s cycle. */
        private suspend fun throb(api: HandyV2ApiClient, intensity: Int) {
            while (coroutineContext.isActive) {
                emit(api, 0.90, intensity, 400)
                emit(api, 0.10, intensity, 700)
            }
        }

        /** Smooth oscillation between mid and high, no zero, no peak. 2 s cycle. */
        private suspend fun bounce(api: HandyV2ApiClient, intensity: Int) {
            val periodMs = 2000L
            val steps = 16
            val dt = periodMs / steps
            while (coroutineContext.isActive) {
                for (i in 0 until steps) {
                    val phase = (2.0 * PI * i) / steps
                    val env = 0.40 + 0.50 * (0.5 - 0.5 * cos(phase))  // 0.40..0.90
                    emit(api, env, intensity, dt)
                }
            }
        }

        /** Stairstep climb: each level held longer, peak held for a long
         *  finale, then a brief rest before climbing again. ~30 s cycle. */
        private suspend fun climax(api: HandyV2ApiClient, intensity: Int) {
            while (coroutineContext.isActive) {
                emit(api, 0.30, intensity, 3000)
                emit(api, 0.50, intensity, 3000)
                emit(api, 0.70, intensity, 3000)
                emit(api, 0.85, intensity, 3000)
                emit(api, 1.0,  intensity, 8000)  // long peak hold
                emit(api, 0.0,  intensity, 2000)  // brief reset
            }
        }

        /**
         * Single envelope step: clamp envelope to [0,1], scale by intensity
         * to a device velocity, push to the device, and sleep for `durationMs`.
         * API failures are swallowed so a transient error doesn't kill the loop —
         * the next iteration tries again, and a truly-dead device will raise
         * elsewhere.
         */
        private suspend fun emit(
            api: HandyV2ApiClient,
            envelope: Double,
            intensity: Int,
            durationMs: Long,
        ) {
            val env = envelope.coerceIn(0.0, 1.0)
            val velocity = (env * intensity).toInt().coerceIn(0, 100)
            runCatching { api.setSpeed(velocity) }
            delay(durationMs.coerceAtLeast(MIN_STEP_MS))
        }
    }
}
