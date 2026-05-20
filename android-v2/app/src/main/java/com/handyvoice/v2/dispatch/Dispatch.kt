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

    /** Same nine labels as vega Phase 1. */
    val LABELS: List<String> = listOf(
        "start", "stop", "faster", "slower",
        "heartbeat", "wave", "surge", "breathe", "storm",
    )

    /** Human-readable prompt shown on the training card for each label. */
    val PROMPTS: Map<String, String> = mapOf(
        "start"     to "start",
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
        "start"     to VoiceAction.SetSpeed(25),
        "stop"      to VoiceAction.Stop,
        "faster"    to VoiceAction.AdjustSpeed(DEFAULT_STEP),
        "slower"    to VoiceAction.AdjustSpeed(-DEFAULT_STEP),
        // Pattern stand-ins until the pattern engine is ported. Each one
        // picks a distinctive range + speed so they're at least useful.
        // Speed is in design space and is scaled by the global max later.
        "heartbeat" to VoiceAction.Preset(rangeMin = 50, rangeMax = 90, speed = 25),
        "wave"      to VoiceAction.Preset(rangeMin = 0,  rangeMax = 100, speed = 50),
        "surge"     to VoiceAction.Preset(rangeMin = 0,  rangeMax = 100, speed = 80),
        "breathe"   to VoiceAction.Preset(rangeMin = 0,  rangeMax = 100, speed = 25),
        "storm"     to VoiceAction.Preset(rangeMin = 50, rangeMax = 90, speed = 80),
    )

    fun actionFor(label: String): VoiceAction? = TABLE[label]

    /**
     * Run a label's action. Returns false if no action is bound or the call
     * sequence failed. Caller owns the coroutine context — the Handy client
     * suspends.
     *
     * All three caps mirror what the manual screen exposes, so voice and tap
     * paths produce the same device state under the same prefs:
     *
     * @param globalMaxSpeed design-space velocity scale (vega's
     *   GLOBAL_MAX_SPEED_PERCENT). Owen's default is 30.
     * @param speedLimit hard ceiling on the device velocity, applied after
     *   the design-space scaling. Default 100 means no extra clamp.
     * @param globalMinStroke / globalMaxStroke stroke-position band. Preset
     *   ranges are squashed into this band, mirroring v1's `applyPreset`.
     *   Default 0..100 means no squashing.
     */
    suspend fun dispatch(
        label: String,
        api: HandyV2ApiClient,
        globalMaxSpeed: Int = 30,
        speedLimit: Int = 100,
        globalMinStroke: Int = 0,
        globalMaxStroke: Int = 100,
    ): Boolean {
        val action = TABLE[label] ?: return false

        fun scaleSpeed(design: Int): Int {
            val scaled = design * globalMaxSpeed.coerceIn(1, 100) / 100
            return scaled.coerceIn(0, speedLimit.coerceIn(1, 100))
        }

        fun squashRange(lo: Int, hi: Int): Pair<Int, Int> {
            val gMin = globalMinStroke.coerceIn(0, 100)
            val gMax = globalMaxStroke.coerceIn(0, 100)
            if (gMin >= gMax) return gMin to gMin
            val band = gMax - gMin
            return (gMin + lo * band / 100) to (gMin + hi * band / 100)
        }

        return when (action) {
            is VoiceAction.Stop -> api.stop()

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
                // Same shape as v1's applySpeedMultiplier: scale the delta in
                // design space, then clamp the resulting velocity by speedLimit.
                val scaledDelta = action.delta * globalMaxSpeed.coerceIn(1, 100) / 100
                val target = (api.currentSpeed.value + scaledDelta).coerceIn(0, speedLimit.coerceIn(1, 100))
                api.setSpeed(target)
            }

            is VoiceAction.SetRange -> {
                val (min, max) = squashRange(action.min, action.max)
                api.setStrokeRange(min, max)
            }

            is VoiceAction.Preset -> {
                // Operation order: slide -> start -> velocity. See HANDY_API_NOTES.
                val (min, max) = squashRange(action.rangeMin, action.rangeMax)
                val rangeOk = api.setStrokeRange(min, max)
                if (!rangeOk) return false
                val started = if (!api.isRunning.value) api.start() else true
                if (!started) return false
                api.setSpeed(scaleSpeed(action.speed))
            }
        }
    }
}
