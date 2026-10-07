package com.wdwy90.pullupmenu.core

/**
 * An automatic check while a visit is on screen (the line moved on, or the car pulled into the lot
 * next door): is the car still at the visit's place? Pure logic.
 */
object VisitCheck {
    /**
     * True when the screen should stay on [visit], given the places [found] around the car now
     * (nearest first): none (no news), the visit is still the nearest, or the user picked it from the
     * nearby list ([chosen]) and it's still among them.
     */
    fun staysAt(visit: Restaurant, chosen: Boolean, found: List<Restaurant>): Boolean {
        val nearest = found.firstOrNull() ?: return true
        return nearest.id == visit.id || (chosen && found.any { it.id == visit.id })
    }
}
