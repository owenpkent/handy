package com.handyvoice.v2.voice

/**
 * Speaker-dependent template-matching engine.
 *
 * One implementation today (MFCC + DTW). A future TFLite-embedding
 * implementation will swap in behind this same interface so the rest of
 * the app does not change.
 */
interface TemplateEngine {
    /** Labels with at least one stored template. */
    fun labels(): List<String>

    /** Add a take. Multiple takes per label improve the match. */
    fun addTake(label: String, audio: ShortArray)

    /** Forget all takes for a label. */
    fun clearLabel(label: String)

    /** Forget everything. */
    fun clearAll()

    /** Number of stored takes for a label. */
    fun takeCount(label: String): Int

    /**
     * Best-matching label for [audio].
     *
     * Returns null when the engine has no templates loaded, or when the
     * audio is shorter than the feature window. Otherwise always returns a
     * Match; the caller compares [Match.score] to its threshold to decide
     * whether to act on it.
     */
    fun match(audio: ShortArray): Match?

    data class Match(val label: String, val score: Double, val runnerUp: Double = Double.POSITIVE_INFINITY) {
        /** Difference to the runner-up. Bigger gap = more confident pick. */
        val margin: Double get() = runnerUp - score
    }
}
