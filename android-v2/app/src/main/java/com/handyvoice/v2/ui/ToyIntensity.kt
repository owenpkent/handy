package com.handyvoice.v2.ui

/**
 * Android-free intensity maths for the Lovense toy page. Intensity is a
 * design-space 0..100 value moved in steps of [STEP]; the device sees it
 * scaled by the per-device max speed, exactly like the Manual speed slider.
 */
object ToyIntensity {
    const val STEP = 5
    const val MAX = 100

    /** Snap to the nearest step and clamp to 0..100. */
    fun snap(design: Int): Int =
        (((design.coerceIn(0, MAX) + STEP / 2) / STEP) * STEP).coerceIn(0, MAX)

    /** One step up or down (direction > 0 / < 0). Off-grid values land on the next grid point in that direction. */
    fun step(current: Int, direction: Int): Int {
        val c = current.coerceIn(0, MAX)
        return when {
            direction > 0 -> ((c / STEP + 1) * STEP).coerceAtMost(MAX)
            direction < 0 -> (((c + STEP - 1) / STEP - 1) * STEP).coerceAtLeast(0)
            else -> snap(c)
        }
    }

    /** Design 0..100 to device velocity, capped by the global max speed (1..100). */
    fun toDevice(design: Int, globalMaxSpeed: Int): Int =
        (design.coerceIn(0, MAX) * globalMaxSpeed.coerceIn(1, 100) / 100).coerceIn(0, 100)

    /** Slider progress (0..[MAX]/[STEP]) to design intensity. */
    fun fromSlider(progress: Int): Int = (progress * STEP).coerceIn(0, MAX)

    /** Design intensity to slider progress. */
    fun toSlider(design: Int): Int = snap(design) / STEP
}
