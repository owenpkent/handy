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
     * Run a label's action. Returns null if no action is bound. Caller is
     * responsible for the coroutine context — the Handy client suspends.
     *
     * @param globalMaxSpeed scale factor applied to speed in design space.
     *   Mirrors vega's GLOBAL_MAX_SPEED_PERCENT. Owen's default is 30.
     */
    suspend fun dispatch(
        label: String,
        api: HandyV2ApiClient,
        globalMaxSpeed: Int = 30,
    ): Boolean {
        val action = TABLE[label] ?: return false
        val scale = globalMaxSpeed.coerceIn(1, 100) / 100.0

        return when (action) {
            is VoiceAction.Stop -> api.stop()

            is VoiceAction.SetSpeed -> {
                val device = (action.percent * scale).toInt().coerceIn(0, 100)
                if (device == 0) {
                    api.stop()
                } else {
                    // mode/start handled by client.connect(); just (start then) velocity.
                    val started = if (!api.isRunning.value) api.start() else true
                    started && api.setSpeed(device)
                }
            }

            is VoiceAction.AdjustSpeed -> {
                val scaledDelta = (action.delta * scale).toInt()
                api.adjustSpeed(scaledDelta)
            }

            is VoiceAction.SetRange -> api.setStrokeRange(action.min, action.max)

            is VoiceAction.Preset -> {
                // Operation order: slide -> start -> velocity. See HANDY_API_NOTES.
                val rangeOk = api.setStrokeRange(action.rangeMin, action.rangeMax)
                if (!rangeOk) return false
                val started = if (!api.isRunning.value) api.start() else true
                if (!started) return false
                val device = (action.speed * scale).toInt().coerceIn(0, 100)
                api.setSpeed(device)
            }
        }
    }
}
