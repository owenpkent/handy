package com.handyvoice.v2.dispatch

/**
 * What a matched voice label should make the device do.
 *
 * The dispatch table maps labels (the spoken words the user records as
 * templates) to one of these. Mirrors vega/commands.py's DISPATCH dict.
 *
 * Patterns (heartbeat/wave/surge/breathe/storm) currently map to fixed
 * (speed + range) preset combinations. The proper pattern engine port
 * from vega/devices/handy.py is Phase 2 work; the recording UX does not
 * need to change when it lands because labels stay the same.
 */
sealed class VoiceAction {
    /** Stop the device. Sends hamp/stop and clears running state. */
    data object Stop : VoiceAction()

    /** Set absolute speed (0-100). Triggers the start sequence if needed. */
    data class SetSpeed(val percent: Int) : VoiceAction()

    /** Adjust speed by [delta] percentage points. Positive = faster. */
    data class AdjustSpeed(val delta: Int) : VoiceAction()

    /** Set stroke range (each 0-100). */
    data class SetRange(val min: Int, val max: Int) : VoiceAction()

    /** Set range + speed in one action. Used for one-shot tap-only presets
     *  (Pulse / Tease / Edge / etc) that don't have a time-based envelope. */
    data class Preset(val rangeMin: Int, val rangeMax: Int, val speed: Int) : VoiceAction()

    /** Run a named [PatternEngine] pattern at the caller's intensity. */
    data class Pattern(val name: String, val intensity: Int = 100) : VoiceAction()
}
