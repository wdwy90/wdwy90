package com.wdwy90.pullupmenu.core

/**
 * Catches false alarms from auto-detect: a red light next to a fast-food place looks like a
 * drive-thru stop for a while. A real drive-thru line never reaches [driveOffSpeedMps] (~13 mph)
 * within [windowMs] of the card appearing; a green light does. Such spots are remembered (in
 * memory only, at most [maxSpots]) and ignored for the rest of the drive. Pure logic.
 */
class RedLightFilter(
    private val driveOffSpeedMps: Double = 6.0,
    private val windowMs: Long = 45_000,
    private val ignoreRadiusM: Double = 40.0,
    private val maxSpots: Int = 50,
) {
    private class Point(val lat: Double, val lng: Double, val timeMs: Long)

    private var trigger: Point? = null
    private var prev: Point? = null
    private val spots = ArrayList<Point>()

    /** An automatic lookup just ran at this spot. */
    fun onTrigger(lat: Double, lng: Double, timeMs: Long) {
        trigger = Point(lat, lng, timeMs)
    }

    /**
     * Returns true once when the car drives off fast within [windowMs] after a trigger.
     * The trigger spot is then ignored from now on.
     */
    fun onSample(lat: Double, lng: Double, timeMs: Long, speedMps: Double?): Boolean {
        val p = prev
        val speed = speedMps ?: p?.let {
            val dt = (timeMs - it.timeMs) / 1000.0
            if (dt > 0) Geo.distanceMeters(it.lat, it.lng, lat, lng) / dt else null
        }
        prev = Point(lat, lng, timeMs)

        val t = trigger ?: return false
        val elapsed = timeMs - t.timeMs
        if (elapsed > windowMs) {
            trigger = null
            return false
        }
        if (elapsed < 0 || speed == null || speed <= driveOffSpeedMps) return false

        trigger = null
        if (spots.size >= maxSpots) spots.removeAt(0)
        spots += t
        return true
    }

    fun isIgnored(lat: Double, lng: Double): Boolean =
        spots.any { Geo.distanceMeters(it.lat, it.lng, lat, lng) <= ignoreRadiusM }

    fun clear() {
        trigger = null
        prev = null
        spots.clear()
    }
}
