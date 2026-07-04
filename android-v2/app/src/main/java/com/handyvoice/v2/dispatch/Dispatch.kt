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
     * configured stroke band at dispatch time (band 100 => 15 user points;
     * a narrower band steps proportionally less). Mirrors how [DEFAULT_STEP]
     * is design-space and scaled to globalMaxSpeed.
     *
     * This is how far the stroke *window slides* per press. The window's
     * length is held constant (see [VoiceAction.AdjustDepth]), so depth never
     * changes the cycle rate / felt speed.
     */
    const val DEFAULT_STROKE_STEP: Int = 15

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

    /**
     * Reserved "reject" label. NOT a command (no dispatch action, no tap
     * button) — it holds templates of the *device's own motor sound* (and
     * other background noise). When an incoming window matches this label
     * best, the caller drops it instead of dispatching, so the device's noise
     * can't false-trigger a command (notably "stop" halting itself). Trained
     * from Settings → "Record device sound to ignore" while the device runs.
     * Underscored so it can never collide with a real spoken label.
     */
    const val NOISE_LABEL: String = "__noise__"

    /** Human-readable prompt shown on the training card for each label. */
    val PROMPTS: Map<String, String> = mapOf(
        "begin"   to "begin",
        "stop"    to "stop",
        "faster"  to "faster",
        "slower"  to "slower",
        "deeper"  to "deeper",
        "shallow" to "shallow",
        "random"  to "random",
        // Shown on the training card for the reject class. Not a word to say —
        // the user stays quiet and lets the device run so we capture its noise.
        NOISE_LABEL to "device sound — let it run, stay quiet",
    )

    private val TABLE: Map<String, VoiceAction> = mapOf(
        "begin"   to VoiceAction.SetSpeed(25),
        "stop"    to VoiceAction.Stop,
        "faster"  to VoiceAction.AdjustSpeed(DEFAULT_STEP),
        "slower"  to VoiceAction.AdjustSpeed(-DEFAULT_STEP),
        // Step deeper / shallower. Slides the stroke window toward the base
        // (deeper) or the tip (shallow) at a fixed length, so depth doesn't
        // change the felt speed.
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
        humanizeEngine: HumanizeEngine? = null,
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
                // Cancel any modulation/pattern first (their loops keep writing
                // velocity), then ease the device down to 0 instead of a hard stop.
                humanizeEngine?.cancel()
                patternEngine?.cancel()
                api.rampStop()
            }

            is VoiceAction.SetSpeed -> {
                val device = scaleSpeed(action.percent)
                if (device == 0) {
                    humanizeEngine?.cancel()
                    api.stop()
                } else if (humanizeEngine?.enabled == true) {
                    // Hand the base speed to the humanize loop, which owns the
                    // device velocity (and depth) while it runs.
                    humanizeEngine.start(device)
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
                if (humanizeEngine?.isRunning == true) {
                    // Nudge the humanize loop's base target (stable value, not
                    // the wobbling device velocity); it eases to the new speed.
                    val target = (humanizeEngine.currentTarget + scaledDelta).coerceIn(0, cap)
                    humanizeEngine.setTarget(target)
                    true
                } else {
                    val target = (api.currentSpeed.value + scaledDelta).coerceIn(0, cap)
                    // Glide to the new speed instead of jumping — the device is
                    // stepped through intermediate velocities (see rampSpeed).
                    api.rampSpeed(target)
                }
            }

            is VoiceAction.AdjustDepth -> {
                // Slide the stroke *window* deeper (toward the base) or
                // shallower (toward the tip) while holding its length fixed.
                // Keeping the width constant keeps the cycle rate constant, so
                // depth never changes the felt speed — the same way faster /
                // slower never touch the range. Step is scaled to the band the
                // same way AdjustSpeed scales its step to globalMaxSpeed.
                //
                // (Earlier this *lengthened* then concentrated the stroke. A
                // longer stroke completes slower at the same velocity, so
                // "deeper" dropped the felt pace and a following "slower" then
                // crawled. Sliding a fixed-length window avoids that coupling.)
                val floor = globalMinStroke.coerceIn(0, 100)
                val ceil = globalMaxStroke.coerceIn(floor, 100)
                val band = (ceil - floor).coerceAtLeast(1)
                val step = (DEFAULT_STROKE_STEP * band / 100).coerceAtLeast(1)
                val curMin = api.strokeMin.value.coerceIn(floor, ceil)
                val curMax = api.strokeMax.value.coerceIn(floor, ceil)

                if (action.deeper) {
                    // Move toward the base; clamp at the floor, then shift the
                    // top down by the same amount so the length is preserved.
                    val newMin = (curMin - step).coerceAtLeast(floor)
                    val shift = curMin - newMin
                    if (shift > 0) api.setStrokeRange(newMin, curMax - shift) else true
                } else {
                    // Mirror toward the tip; clamp at the ceiling, shift the
                    // bottom up by the same amount.
                    val newMax = (curMax + step).coerceAtMost(ceil)
                    val shift = newMax - curMax
                    if (shift > 0) api.setStrokeRange(curMin + shift, newMax) else true
                }
            }

            is VoiceAction.SetRange -> {
                val (min, max) = squashRange(action.min, action.max)
                api.setStrokeRange(min, max)
            }

            is VoiceAction.Preset -> {
                // Operation order: slide -> start -> velocity. See HANDY_API_NOTES.
                humanizeEngine?.cancel()
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
                // A pattern owns the device from here; stop humanize modulating.
                humanizeEngine?.cancel()
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
                // A pattern owns the device from here; stop humanize modulating.
                humanizeEngine?.cancel()
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
