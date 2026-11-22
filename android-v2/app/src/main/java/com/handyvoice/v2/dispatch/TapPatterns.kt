package com.handyvoice.v2.dispatch

/**
 * The twelve tap-able pattern buttons (Manual grid and the Lovense toy page):
 * one [PatternEngine] envelope each, bracketed by a stroke range expressed as
 * user-percent of the global band. Single source of truth for both screens.
 * None is reachable by voice, so none goes through [Dispatch].
 */
object TapPatterns {
    data class Spec(val name: String, val label: String, val rangeMin: Int, val rangeMax: Int)

    val ALL: List<Spec> = listOf(
        Spec("pulse", "Pulse", 0, 100),
        Spec("ripple", "Ripple", 0, 100),
        Spec("thunder", "Thunder", 0, 100),
        Spec("tick", "Tick", 40, 60),
        Spec("surge", "Surge", 0, 100),
        Spec("breathe", "Breathe", 0, 100),
        Spec("tease", "Tease", 0, 50),
        Spec("edge", "Edge", 70, 90),
        Spec("rush", "Rush", 0, 100),
        Spec("throb", "Throb", 40, 70),
        Spec("bounce", "Bounce", 20, 80),
        Spec("climax", "Climax", 30, 90),
    )

    fun byName(name: String): Spec = ALL.first { it.name == name }
}
