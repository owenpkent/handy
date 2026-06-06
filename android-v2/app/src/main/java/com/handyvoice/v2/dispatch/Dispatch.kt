package com.handyvoice.v2.dispatch

import com.handyvoice.v2.api.HandyV2ApiClient

/**
 * Label -> [VoiceAction]. Single source of truth for what each spoken
 * command means. Adding a new command is: add a row here, ship the layout
 * text, retrain.
 *
 * Vocabulary matches vega/commands.py's DISPATCH so users familiar with
 * vega have one mental model.
 */
object Dispatch {

    // Design-space nudge for faster/slower (before globalMaxSpeed scaling).
    // At Owen's 30% cap this lands ~7 device-velocity points per press
    // (25 * 30 / 100), so ~4 presses span the whole band. 15 felt too
    // subtle (only ~4 points/press). Bump here if it still feels small.
    const val DEFAULT_STEP: Int = 25

    /**
     * Per-press deeper/shallow nudge in *design space* (0-100), scaled to the
     * configured stroke band at dispatch time (band 100 => 15 user points,
     * matching the old flat step; a narrower band steps proportionally less).
     * Mirrors how [DEFAULT_STEP] is design-space and scaled to globalMaxSpeed.
     */
    const val DEFAULT_STROKE_STEP: Int = 15

    /**
     * Floor on stroke width (design space, scaled to the band) so the
     * base/tip concentration phase can never collapse the stroke to a
     * zero-width range, which would stop the motion entirely.
     */
    const val MIN_STROKE_WIDTH: Int = 20

    /**
     * Voice labels. Picked for acoustic distinctness in MFCC+DTW space:
     * different syllable counts, vowel positions, and consonant types so
     * the matcher has wide gaps between any two labels.
     *  - "begin" stands in for "start" (start/stop collide).
     *  - "deeper" / "shallow" live-adjust the stroke length (the minimum /
     *    top of the range). "shallow" deliberately over "lower" — "lower"
     *    collides with "slower" for less-articulated speech. "deeper" and
     *    "shallow" sit far apart from each other and from faster/slower
     *    (different vowels and consonant families).
     *  - "random" fires a random motion envelope. The fixed pattern words
     *    (thunder / ripple / pulse) were dropped from the spoken set — they
     *    weren't useful by voice — but stay tap-able on the Manual tab.
     */
    val LABELS: List<String> = listOf(
        "begin", "stop", "faster", "slower",
        "deeper", "shallow", "random",
    )

    /** Human-readable prompt shown on the training card for each label. */
    val PROMPTS: Map<String, String> = mapOf(
        "begin"   to "begin",
        "stop"    to "stop",
        "faster"  to "faster",
        "slower"  to "slower",
        "deeper"  to "deeper",
        "shallow" to "shallow",
        "random"  to "random",
    )

    private val TABLE: Map<String, VoiceAction> = mapOf(
        "begin"   to VoiceAction.SetSpeed(25),
        "stop"    to VoiceAction.Stop,
        "faster"  to VoiceAction.AdjustSpeed(DEFAULT_STEP),
        "slower"  to VoiceAction.AdjustSpeed(-DEFAULT_STEP),
        // Step deeper / shallower. deeper lengthens toward the base, then
        // concentrates the stroke at the base; shallow mirrors toward the tip.
        "deeper"  to VoiceAction.AdjustDepth(deeper = true),
        "shallow" to VoiceAction.AdjustDepth(deeper = false),
        // Surprise envelope — picks a random pattern each time.
        "random"  to VoiceAction.RandomPattern(),
    )

    fun actionFor(label: String): VoiceAction? = TABLE[label]

    /**
     * Run a label's action. Returns false if no action is bound or the call
     * sequence failed. Caller owns the coroutine context — the Handy client
     * suspends.
     *
     * The caps mirror what Settings exposes, so voice and tap paths produce
     * the same device state under the same prefs:
     *
     * @param globalMaxSpeed design-space velocity scale (vega's
     *   GLOBAL_MAX_SPEED_PERCENT). Owen's default is 30.
     * @param globalMinStroke / globalMaxStroke stroke-position band. Preset
     *   ranges are squashed into this band, mirroring v1's `applyPreset`.
     *   Default 0..100 means no squashing.
     */
    suspend fun dispatch(
        label: String,
        api: HandyV2ApiClient,
        globalMaxSpeed: Int = 30,
        globalMinStroke: Int = 0,
        globalMaxStroke: Int = 100,
        patternEngine: PatternEngine? = null,
    ): Boolean {
        val action = TABLE[label] ?: return false

        fun scaleSpeed(design: Int): Int {
            return (design * globalMaxSpeed.coerceIn(1, 100) / 100).coerceIn(0, 100)
        }

        fun squashRange(lo: Int, hi: Int): Pair<Int, Int> {
            val gMin = globalMinStroke.coerceIn(0, 100)
            val gMax = globalMaxStroke.coerceIn(0, 100)
            if (gMin >= gMax) return gMin to gMin
            val band = gMax - gMin
            return (gMin + lo * band / 100) to (gMin + hi * band / 100)
        }

        return when (action) {
            is VoiceAction.Stop -> {
                patternEngine?.cancel()
                api.stop()
            }

            is VoiceAction.SetSpeed -> {
                val device = scaleSpeed(action.percent)
                if (device == 0) {
                    api.stop()
                } else {
                    // mode/start handled by client.connect(); just (start then) velocity.
                    val started = if (!api.isRunning.value) api.start() else true
                    started && api.setSpeed(device)
                }
            }

            is VoiceAction.AdjustSpeed -> {
                // Scale the delta in design space, then clamp the result to
                // the configured cap so "faster" can't push past globalMaxSpeed.
                val cap = globalMaxSpeed.coerceIn(1, 100)
                val scaledDelta = action.delta * cap / 100
                val target = (api.currentSpeed.value + scaledDelta).coerceIn(0, cap)
                api.setSpeed(target)
            }

            is VoiceAction.AdjustDepth -> {
                // Step size + minimum width scaled to the configured band, the
                // same way AdjustSpeed scales its step to globalMaxSpeed, so the
                // feel is consistent whatever band the user has set.
                val floor = globalMinStroke.coerceIn(0, 100)
                val ceil = globalMaxStroke.coerceIn(floor, 100)
                val band = (ceil - floor).coerceAtLeast(1)
                val step = (DEFAULT_STROKE_STEP * band / 100).coerceAtLeast(1)
                val minWidth = (MIN_STROKE_WIDTH * band / 100).coerceAtLeast(1)
                val curMin = api.strokeMin.value.coerceIn(floor, ceil)
                val curMax = api.strokeMax.value.coerceIn(floor, ceil)

                if (action.deeper) {
                    if (curMin > floor) {
                        // Phase 1: lengthen the stroke toward the base (lower min).
                        api.setStrokeRange((curMin - step).coerceAtLeast(floor), curMax)
                    } else {
                        // Phase 2: min is on the floor — pull the top down so the
                        // motion concentrates into short strokes at the base.
                        val newMax = (curMax - step).coerceAtLeast(floor + minWidth)
                        if (newMax < curMax) api.setStrokeRange(floor, newMax) else true
                    }
                } else {
                    if (curMax < ceil) {
                        // Phase 1 (mirror): re-extend the top toward the tip (raise max).
                        api.setStrokeRange(curMin, (curMax + step).coerceAtMost(ceil))
                    } else {
                        // Phase 2 (mirror): max is at the ceiling — raise the floor
                        // so the motion concentrates into short strokes at the tip.
                        val newMin = (curMin + step).coerceAtMost(ceil - minWidth)
                        if (newMin > curMin) api.setStrokeRange(newMin, ceil) else true
                    }
                }
            }

            is VoiceAction.SetRange -> {
                val (min, max) = squashRange(action.min, action.max)
                api.setStrokeRange(min, max)
            }

            is VoiceAction.Preset -> {
                // Operation order: slide -> start -> velocity. See HANDY_API_NOTES.
                patternEngine?.cancel()
                val (min, max) = squashRange(action.rangeMin, action.rangeMax)
                val rangeOk = api.setStrokeRange(min, max)
                if (!rangeOk) return false
                val started = if (!api.isRunning.value) api.start() else true
                if (!started) return false
                api.setSpeed(scaleSpeed(action.speed))
            }

            is VoiceAction.Pattern -> {
                val engine = patternEngine ?: return false
                // Open the stroke range to the configured band so the pattern
                // can use its full envelope. Peak velocity = globalMaxSpeed
                // (intensity is scaled the same way SetSpeed/AdjustSpeed are).
                val (min, max) = squashRange(0, 100)
                val rangeOk = api.setStrokeRange(min, max)
                if (!rangeOk) return false
                val peak = scaleSpeed(action.intensity)
                engine.start(action.name, peak)
            }

            is VoiceAction.RandomPattern -> {
                val engine = patternEngine ?: return false
                // Same setup as Pattern, but the engine picks the envelope.
                val (min, max) = squashRange(0, 100)
                val rangeOk = api.setStrokeRange(min, max)
                if (!rangeOk) return false
                val peak = scaleSpeed(action.intensity)
                engine.startRandom(peak)
            }
        }
    }
}
