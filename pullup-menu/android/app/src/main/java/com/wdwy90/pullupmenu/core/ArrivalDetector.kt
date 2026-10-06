package com.wdwy90.pullupmenu.core

/**
 * Decides when the car has "pulled up" somewhere: speed stays below [stopSpeedMps]
 * for [dwellMs], and we haven't already looked up this spot. Pure logic so it can be
 * unit tested without Android.
 */
class ArrivalDetector(
    // Drive-thru lines creep forward a car length at a time, so "stopped" means
    // under ~6.7 mph rather than fully still.
    private val stopSpeedMps: Double = 3.0,
    private val dwellMs: Long = 20_000,           // in line/stopped for 20 s
    private val minMoveBetweenLookupsM: Double = 100.0,
) {
    data class Sample(val lat: Double, val lng: Double, val timeMs: Long, val speedMps: Double?)

    private var prev: Sample? = null
    private var stoppedSince: Long? = null
    private var lastLookupLat: Double? = null
    private var lastLookupLng: Double? = null

    /** Returns true when a restaurant lookup should be run for this sample's position. */
    fun onSample(s: Sample): Boolean {
        val speed = s.speedMps ?: prev?.let { p ->
            val dt = (s.timeMs - p.timeMs) / 1000.0
            if (dt > 0) Geo.distanceMeters(p.lat, p.lng, s.lat, s.lng) / dt else null
        } ?: 0.0
        prev = s

        if (speed > stopSpeedMps) {
            stoppedSince = null
            return false
        }
        val since = stoppedSince ?: s.timeMs.also { stoppedSince = it }
        if (s.timeMs - since < dwellMs) return false

        if (wasLookedUpNear(s.lat, s.lng)) return false

        markLookedUp(s.lat, s.lng)
        return true
    }

    /** Record a lookup made elsewhere (manual check, car app opening) so this spot isn't looked up again. */
    fun markLookedUp(lat: Double, lng: Double) {
        lastLookupLat = lat
        lastLookupLng = lng
    }

    /** True if the last lookup was within [minMoveBetweenLookupsM] of this spot. */
    fun wasLookedUpNear(lat: Double, lng: Double): Boolean {
        val lLat = lastLookupLat ?: return false
        val lLng = lastLookupLng ?: return false
        return Geo.distanceMeters(lLat, lLng, lat, lng) < minMoveBetweenLookupsM
    }

    /** Forget the last lookup spot so the next stop triggers a fresh lookup. */
    fun reset() {
        stoppedSince = null
        lastLookupLat = null
        lastLookupLng = null
    }
}
