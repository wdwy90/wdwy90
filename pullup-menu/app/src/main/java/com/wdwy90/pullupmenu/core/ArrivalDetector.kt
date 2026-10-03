package com.wdwy90.pullupmenu.core

/**
 * Decides when the car has "pulled up" somewhere: speed stays below [stopSpeedMps]
 * for [dwellMs], and we haven't already looked up this spot. Pure logic so it can be
 * unit tested without Android.
 */
class ArrivalDetector(
    private val stopSpeedMps: Double = 2.0,       // ~4.5 mph
    private val dwellMs: Long = 20_000,           // stopped for 20 s
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

        val lLat = lastLookupLat
        val lLng = lastLookupLng
        if (lLat != null && lLng != null &&
            Geo.distanceMeters(lLat, lLng, s.lat, s.lng) < minMoveBetweenLookupsM
        ) return false

        lastLookupLat = s.lat
        lastLookupLng = s.lng
        return true
    }

    /** Forget the last lookup spot so the next stop triggers a fresh lookup. */
    fun reset() {
        stoppedSince = null
        lastLookupLat = null
        lastLookupLng = null
    }
}
