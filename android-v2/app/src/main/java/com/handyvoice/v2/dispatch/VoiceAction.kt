package com.handyvoice.v2.dispatch

/**
 * What a matched voice label should make the device do.
 *
 * The dispatch table maps labels (the spoken words the user records as
 * templates) to one of these. Pattern labels (thunder / ripple / pulse)
 * route through [PatternEngine] for time-based envelopes; basic actions
 * hit [HandyV2ApiClient] directly. Preset stays around for callers that
 * want a static range+speed in a single VoiceAction, even though no
 * voice labels currently use it.
 */
sealed class VoiceAction {
    /** Stop the device. Eases velocity down to 0 (see rampStop) then sends
     *  hamp/stop and clears running state. */
    data object Stop : VoiceAction()

    /** Set absolute speed (0-100). Triggers the start sequence if needed. */
    data class SetSpeed(val percent: Int) : VoiceAction()

    /** Adjust speed by [delta] percentage points. Positive = faster. */
    data class AdjustSpeed(val delta: Int) : VoiceAction()

    /**
     * Slide the stroke window one notch deeper ([deeper] = true, toward the
     * base) or shallower (toward the tip). The window's *length* is held
     * constant — only its position moves — so depth never changes the cycle
     * rate / felt speed (a longer stroke completes slower at the same
     * velocity, which used to make "deeper" then "slower" crawl). The step is
     * computed at dispatch time from the configured stroke band, so a narrow
     * band moves in proportionally smaller steps, the same way [AdjustSpeed]
     * scales its step to the speed cap. The window clamps at the band edges.
     */
    data class AdjustDepth(val deeper: Boolean) : VoiceAction()

    /** Set stroke range (each 0-100). */
    data class SetRange(val min: Int, val max: Int) : VoiceAction()

    /** Set range + speed in one action. Used for one-shot tap-only presets
     *  (Pulse / Tease / Edge / etc) that don't have a time-based envelope. */
    data class Preset(val rangeMin: Int, val rangeMax: Int, val speed: Int) : VoiceAction()

    /** Run a named [PatternEngine] pattern at the caller's intensity. */
    data class Pattern(val name: String, val intensity: Int = 100) : VoiceAction()

    /** Run a randomly-chosen [PatternEngine] pattern at the caller's intensity. */
    data class RandomPattern(val intensity: Int = 100) : VoiceAction()
}
