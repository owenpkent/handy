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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Adds subtle, organic variation to *regular* (non-pattern) motion so a
 * steady speed doesn't feel metronomic. While active it owns the device's
 * velocity and stroke-window output, continuously nudging both around the
 * base the user asked for:
 *
 *  - **Speed:** an eased base velocity × a smooth multiplier built from two
 *    slow, incommensurate sines plus a slow random walk (so it never sounds
 *    periodic). Swing scales with [strengthPct].
 *  - **Depth:** the stroke window is inset from the user's band by a small,
 *    slowly-varying amount at each end — the deep end sometimes pulls back,
 *    the shallow end sometimes rises — so the reach breathes. Insets only
 *    (never past the user's band) so it always has room, even at a full band.
 *    Written via [HandyV2ApiClient.setStrokeRangeTransient] so the Manual
 *    sliders keep showing the chosen band while the device wanders within it.
 *
 * Where the base comes from:
 *  - Speed base is [targetSpeed], set by begin / faster / slower / the Manual
 *    speed slider. The loop eases toward it, so a target change glides.
 *  - Depth base is read live from [HandyV2ApiClient.strokeMin]/[strokeMax]
 *    each depth tick, so deeper / shallow / the Manual stroke sliders retune
 *    the drift centre for free (transient writes never touch that cache).
 *
 * Disabled when [strengthPct] == 0; callers fall back to the plain
 * setSpeed / rampSpeed path. A pattern, a stop, or speed 0 cancels it.
 * Mirrors [PatternEngine]'s lifecycle (single job, cancel joins).
 */
class HumanizeEngine(
    private val api: HandyV2ApiClient,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    @Volatile private var targetSpeed: Int = 0
    @Volatile private var strengthPct: Int = 0

    /** True when humanize is turned on at all (strength > 0). */
    val enabled: Boolean get() = strengthPct > 0

    /** True while the modulation loop is live. */
    val isRunning: Boolean get() = job?.isActive == true

    /** The steady base speed the loop is centred on (stable; use this instead
     *  of the wobbling device velocity when stepping faster / slower). */
    val currentTarget: Int get() = targetSpeed

    fun setStrength(pct: Int) {
        strengthPct = pct.coerceIn(0, 100)
    }

    /** Retarget the speed the modulation centres on (faster / slower / slider).
     *  Also reflects it in the UI readout so the shown speed stays steady. */
    fun setTarget(velocity: Int) {
        targetSpeed = velocity.coerceIn(0, 100)
        api.reflectBaseSpeed(targetSpeed)
    }

    /**
     * Ensure the loop is running, centred on [velocity]. Starts device motion
     * first if needed. If already running this just retargets. Returns false
     * only if the device refused to start.
     */
    suspend fun start(velocity: Int): Boolean {
        setTarget(velocity)
        if (isRunning) return true
        if (!api.isRunning.value && !api.start()) return false
        job = scope.launch {
            try {
                loop()
            } catch (_: CancellationException) {
                // expected on stop / retarget-to-pattern
            } catch (_: Throwable) {
                // A transient device error shouldn't tear down the scope; the
                // loop just ends and the user can begin again.
            }
        }
        return true
    }

    /** Stop modulating and leave the device on the user's real band (not a
     *  drifted inset). Caller handles the actual stop (rampStop). */
    suspend fun cancel() {
        job?.let { j ->
            if (j.isActive) {
                j.cancel()
                runCatching { j.join() }
            }
        }
        job = null
        runCatching { api.setStrokeRangeTransient(api.strokeMin.value, api.strokeMax.value) }
    }

    private suspend fun loop() {
        // Random phases so consecutive runs don't drift identically.
        val pSpeedA = Random.nextDouble(0.0, TWO_PI)
        val pSpeedB = Random.nextDouble(0.0, TWO_PI)
        val pDepthLo = Random.nextDouble(0.0, TWO_PI)
        val pDepthHi = Random.nextDouble(0.0, TWO_PI)

        var walk = 0.0             // slow, mean-reverting random walk in [-1, 1]
        var easedBase = 0.0        // glides toward targetSpeed (ramp-in + faster/slower glide)
        var t = 0.0                // seconds since loop start
        var sinceDepth = DEPTH_EVERY_STEPS  // force a depth write on the first pass

        while (coroutineContext.isActive) {
            val s = strengthPct / 100.0

            // ---- Speed ----
            easedBase += (targetSpeed - easedBase) * SPEED_EASE
            val wander =
                0.55 * sin(TWO_PI * t / SPEED_PERIOD_A + pSpeedA) +
                0.30 * sin(TWO_PI * t / SPEED_PERIOD_B + pSpeedB) +
                0.15 * walk
            val speedMod = 1.0 + s * SPEED_AMPL * wander
            val v = (easedBase * speedMod).roundToInt().coerceIn(0, 100)
            runCatching { api.setSpeedModulated(v) }

            // ---- Depth (less often; /slide is heavier than a velocity write) ----
            if (++sinceDepth >= DEPTH_EVERY_STEPS) {
                sinceDepth = 0
                val baseMin = api.strokeMin.value
                val baseMax = api.strokeMax.value
                val span = baseMax - baseMin
                if (span >= MIN_SPAN) {
                    // Cap the per-end inset so the two ends together can't
                    // squeeze the stroke below MIN_SPAN.
                    val maxInset = (s * DEPTH_AMPL_POINTS).coerceAtMost((span - MIN_SPAN) / 2.0)
                    val eLo = 0.5 - 0.5 * cos(TWO_PI * t / DEPTH_PERIOD_LO + pDepthLo)
                    val eHi = 0.5 - 0.5 * cos(TWO_PI * t / DEPTH_PERIOD_HI + pDepthHi)
                    val dMin = (baseMin + maxInset * eLo).roundToInt()
                    val dMax = (baseMax - maxInset * eHi).roundToInt()
                    if (dMax - dMin >= MIN_SPAN) {
                        runCatching { api.setStrokeRangeTransient(dMin, dMax) }
                    }
                }
            }

            // Advance the random walk for the next tick (bounded, mean-reverting).
            walk += (Random.nextDouble(-1.0, 1.0) - walk) * WALK_PULL
            walk = walk.coerceIn(-1.0, 1.0)

            delay(STEP_MS)
            t += STEP_MS / 1000.0
        }
    }

    companion object {
        private const val TWO_PI = 2.0 * PI

        private const val STEP_MS = 180L             // velocity update cadence
        private const val DEPTH_EVERY_STEPS = 5      // depth write ~ every 0.9 s
        private const val MIN_SPAN = 12              // never collapse the stroke below this

        private const val SPEED_AMPL = 0.30          // ±30% of base at strength 100
        private const val DEPTH_AMPL_POINTS = 20.0   // up to 20-pt inset per end at strength 100
        private const val SPEED_EASE = 0.14          // per-step glide toward the target
        private const val WALK_PULL = 0.15           // random-walk mean reversion

        // Incommensurate periods (seconds) so the layered motion never repeats.
        private const val SPEED_PERIOD_A = 3.3
        private const val SPEED_PERIOD_B = 7.1
        private const val DEPTH_PERIOD_LO = 5.0
        private const val DEPTH_PERIOD_HI = 8.5
    }
}
