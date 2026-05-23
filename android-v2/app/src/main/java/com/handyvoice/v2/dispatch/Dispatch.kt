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

    const val DEFAULT_STEP: Int = 15

    /** Phase-1 labels. "begin" stands in for "start" because the latter
     *  collides phonetically with "stop" in MFCC space for slower speakers. */
    val LABELS: List<String> = listOf(
        "begin", "stop", "faster", "slower",
        "heartbeat", "wave", "surge", "breathe", "storm",
    )

    /** Human-readable prompt shown on the training card for each label. */
    val PROMPTS: Map<String, String> = mapOf(
        "begin"     to "begin",
        "stop"      to "stop",
        "faster"    to "faster",
        "slower"    to "slower",
        "heartbeat" to "heartbeat",
        "wave"      to "wave",
        "surge"     to "surge",
        "breathe"   to "breathe",
        "storm"     to "storm",
    )

    private val TABLE: Map<String, VoiceAction> = mapOf(
        "begin"     to VoiceAction.SetSpeed(25),
        "stop"      to VoiceAction.Stop,
        "faster"    to VoiceAction.AdjustSpeed(DEFAULT_STEP),
        "slower"    to VoiceAction.AdjustSpeed(-DEFAULT_STEP),
        // True looping envelopes — ported from HA `patterns.py`. The
        // intensity 100 means "drive the pattern's peak at globalMaxSpeed";
        // dispatch() scales that down before passing to the engine.
        "heartbeat" to VoiceAction.Pattern("heartbeat"),
        "wave"      to VoiceAction.Pattern("wave"),
        "surge"     to VoiceAction.Pattern("surge"),
        "breathe"   to VoiceAction.Pattern("breathe"),
        "storm"     to VoiceAction.Pattern("storm"),
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
        }
    }
}
